package com.simplecast.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/**
 * Re-encodes a video so the rotation metadata is baked into the pixels.
 *
 * Some DLNA renderers (Android TV / Google TV) ignore the rotation flag stored in the
 * MP4 container and play the video sideways. Since Android O+ (minSdk 26) the decoder
 * applies the rotation automatically when rendering onto a Surface, we feed the decoder
 * output straight into the encoder input Surface and remux the result with orientation 0.
 * Audio is copied through unchanged.
 */
class VideoRotationTranscoder {

    data class Result(val file: File, val width: Int, val height: Int)

    suspend fun transcode(
        context: Context,
        contentUri: Uri,
        rotationDegrees: Int,
        onProgress: (Float) -> Unit = {}
    ): Result? {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        return try {
            val cacheDir = File(context.cacheDir, "rotated")
            cacheDir.mkdirs()
            val outputFile = File(cacheDir, "rotated_${System.currentTimeMillis()}.mp4")

            extractor = MediaExtractor()
            extractor.setDataSource(context, contentUri, null)

            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoTrackIndex == -1) {
                    videoTrackIndex = i
                    videoFormat = format
                } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                    audioTrackIndex = i
                    audioFormat = format
                }
            }

            if (videoTrackIndex == -1 || videoFormat == null) return null

            val srcWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val srcHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val isPortrait = rotationDegrees == 90 || rotationDegrees == 270

            // The decoder applies rotation on the Surface, so output dimensions swap for 90/270.
            val outWidth = if (isPortrait) srcHeight else srcWidth
            val outHeight = if (isPortrait) srcWidth else srcHeight

            val mime = videoFormat.getString(MediaFormat.KEY_MIME)
                ?: MediaFormat.MIMETYPE_VIDEO_AVC
            val frameRate = if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
            } else {
                30
            }

            val bitrate = (outWidth * outHeight * 6).coerceIn(1_000_000, 20_000_000)

            val encoderFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                outWidth,
                outHeight
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderInputSurface = encoder.createInputSurface()
            encoder.start()

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(videoFormat, encoderInputSurface, null, 0)
            decoder.start()

            extractor.selectTrack(videoTrackIndex)

            val localMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = localMuxer
            localMuxer.setOrientationHint(0)

            var videoMuxerTrack = -1
            var audioMuxerTrack = -1
            var muxerStarted = false
            var videoDone = false

            val durationUs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
                videoFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var inputSignaled = false

            fun startMuxer() {
                if (!muxerStarted) {
                    if (audioTrackIndex != -1 && audioFormat != null) {
                        audioMuxerTrack = localMuxer.addTrack(audioFormat)
                    }
                    localMuxer.start()
                    muxerStarted = true
                }
            }

            while (!videoDone) {
                // Feed decoder input
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val inBuf = decoder.getInputBuffer(inIndex)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                val pts = extractor.sampleTime
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                                extractor.advance()
                                if (durationUs > 0) {
                                    onProgress((pts.toFloat() / durationUs).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                }

                // Drain decoder output to encoder surface
                var decDrained = false
                while (!decDrained) {
                    val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                    when (outIndex) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> decDrained = true
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                        else -> {
                            if (outIndex >= 0) {
                                val eos =
                                    bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                // Render to encoder input surface (rotation applied by decoder)
                                decoder.releaseOutputBuffer(outIndex, true)
                                if (eos && !inputSignaled) {
                                    inputSignaled = true
                                    encoder.signalEndOfInputStream()
                                }
                                decDrained = true
                            }
                        }
                    }
                }

                // Drain encoder output to muxer
                var encDrained = false
                while (!encDrained) {
                    val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                    when (outIndex) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> encDrained = true
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            videoMuxerTrack = localMuxer.addTrack(encoder.outputFormat)
                            startMuxer()
                        }
                        else -> {
                            if (outIndex >= 0) {
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    bufferInfo.size = 0
                                }
                                val outBuf = encoder.getOutputBuffer(outIndex)
                                if (bufferInfo.size > 0 && muxerStarted && videoMuxerTrack != -1 && outBuf != null) {
                                    outBuf.position(bufferInfo.offset)
                                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                    localMuxer.writeSampleData(videoMuxerTrack, outBuf, bufferInfo)
                                }
                                val eos =
                                    bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                encoder.releaseOutputBuffer(outIndex, false)
                                if (eos) {
                                    videoDone = true
                                    encDrained = true
                                }
                            }
                        }
                    }
                }
            }

            // Copy audio track through unchanged
            if (audioTrackIndex != -1 && audioFormat != null && muxerStarted) {
                extractor.unselectTrack(videoTrackIndex)
                extractor.selectTrack(audioTrackIndex)
                val audioInfo = MediaCodec.BufferInfo()
                var audioInputDone = false
                val audioBuf = ByteBuffer.allocate(1 shl 20)
                while (!audioInputDone) {
                    audioBuf.clear()
                    val size = extractor.readSampleData(audioBuf, 0)
                    if (size < 0) {
                        audioInputDone = true
                    } else {
                        audioInfo.offset = 0
                        audioInfo.size = size
                        audioInfo.presentationTimeUs = extractor.sampleTime
                        audioInfo.flags =
                            if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                                MediaCodec.BUFFER_FLAG_KEY_FRAME
                            } else {
                                0
                            }
                        muxer.writeSampleData(audioMuxerTrack, audioBuf, audioInfo)
                        extractor.advance()
                    }
                }
            }

            muxer.stop()

            Result(outputFile, outWidth, outHeight)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }
}
package com.simplecast.network.server

import android.content.Context
import android.net.Uri
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream

class LocalHttpServer(
    private val context: Context,
    port: Int = 8080
) : NanoHTTPD(port) {

    private val mediaMap = mutableMapOf<String, Uri>()

    fun registerMedia(id: String, uri: Uri) {
        mediaMap[id] = uri
    }

    fun clearMedia() {
        mediaMap.clear()
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        if (!uri.startsWith("/media/")) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found")
        }

        val mediaId = uri.substringAfter("/media/")
        val contentUri = mediaMap[mediaId]
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Media Not Found")

        return try {
            val contentResolver = context.contentResolver
            var mimeType = contentResolver.getType(contentUri)

            // Infer mimeType if ContentResolver returns null
            if (mimeType.isNullOrEmpty()) {
                val strUri = contentUri.toString().lowercase()
                mimeType = when {
                    strUri.contains("jpg") || strUri.contains("jpeg") -> "image/jpeg"
                    strUri.contains("png") -> "image/png"
                    strUri.contains("mp4") -> "video/mp4"
                    strUri.contains("mkv") -> "video/x-matroska"
                    else -> "video/mp4"
                }
            }

            val isImage = mimeType.startsWith("image/")

            // Determine DLNA features header for LG webOS
            val dlnaFeatures = if (isImage) {
                val pn = if (mimeType.contains("png")) "PNG_LRG" else "JPEG_LRG"
                "DLNA.ORG_PN=$pn;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=00D00000000000000000000000000000"
            } else {
                "DLNA.ORG_PN=MP4_MED;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
            }
            val transferMode = if (isImage) "Interactive" else "Streaming"

            val assetFileDescriptor = try {
                contentResolver.openAssetFileDescriptor(contentUri, "r")
            } catch (e: Exception) {
                null
            }

            val fileLength = assetFileDescriptor?.length ?: -1L
            val rangeHeader = session.headers["range"] ?: session.headers["Range"]

            val response = if (rangeHeader != null && rangeHeader.startsWith("bytes=") && fileLength > 0) {
                var start: Long = 0
                var end: Long = fileLength - 1

                val rangeValue = rangeHeader.substring(6)
                val minusIndex = rangeValue.indexOf('-')
                if (minusIndex > 0) {
                    try {
                        start = rangeValue.substring(0, minusIndex).toLong()
                        if (minusIndex < rangeValue.length - 1) {
                            end = rangeValue.substring(minusIndex + 1).toLong()
                        }
                    } catch (e: NumberFormatException) {
                        e.printStackTrace()
                    }
                }

                if (start >= fileLength) {
                    val res = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                    res.addHeader("Content-Range", "bytes */$fileLength")
                    res
                } else {
                    val contentLength = end - start + 1
                    val inputStream = contentResolver.openInputStream(contentUri)
                    inputStream?.skip(start)

                    val res = newFixedLengthResponse(
                        Response.Status.PARTIAL_CONTENT,
                        mimeType,
                        inputStream,
                        contentLength
                    )
                    res.addHeader("Content-Range", "bytes $start-$end/$fileLength")
                    res.addHeader("Content-Length", contentLength.toString())
                    res
                }
            } else {
                val inputStream: InputStream? = contentResolver.openInputStream(contentUri)
                val res = newFixedLengthResponse(
                    Response.Status.OK,
                    mimeType,
                    inputStream,
                    if (fileLength > 0) fileLength else inputStream?.available()?.toLong() ?: -1L
                )
                if (fileLength > 0) {
                    res.addHeader("Content-Length", fileLength.toString())
                }
                res
            }

            // Mandatory DLNA HTTP headers for LG webOS 4.5+ compatibility
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("transferMode.dlna.org", transferMode)
            response.addHeader("contentFeatures.dlna.org", dlnaFeatures)
            response.addHeader("Server", "Linux/2.6.0 UPnP/1.0 DLNADOC/1.50 SimpleCast/1.0")
            response.addHeader("Connection", "keep-alive")

            response
        } catch (e: Exception) {
            e.printStackTrace()
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Server error: ${e.message}")
        }
    }
}

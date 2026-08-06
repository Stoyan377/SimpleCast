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
            val mimeType = contentResolver.getType(contentUri) ?: "application/octet-stream"

            val assetFileDescriptor = contentResolver.openAssetFileDescriptor(contentUri, "r")
                ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "File opening failed")

            val fileLength = assetFileDescriptor.length
            val rangeHeader = session.headers["range"] ?: session.headers["Range"]

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
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
                    return res
                }

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
                res.addHeader("Accept-Ranges", "bytes")
                res.addHeader("Content-Length", contentLength.toString())
                res
            } else {
                val inputStream: InputStream? = contentResolver.openInputStream(contentUri)
                val res = newFixedLengthResponse(
                    Response.Status.OK,
                    mimeType,
                    inputStream,
                    fileLength
                )
                res.addHeader("Accept-Ranges", "bytes")
                res.addHeader("Content-Length", fileLength.toString())
                res
            }
        } catch (e: Exception) {
            e.printStackTrace()
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Server error: ${e.message}")
        }
    }
}

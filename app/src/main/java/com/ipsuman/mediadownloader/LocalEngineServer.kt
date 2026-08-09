package com.ipsuman.mediadownloader

import fi.iki.elonen.NanoHTTPD

class LocalEngineServer : NanoHTTPD(8765) {

    private fun cors(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        return response
    }

    override fun serve(session: IHTTPSession): Response {

        if (session.method == Method.OPTIONS) {
            return cors(
                newFixedLengthResponse(
                    Response.Status.OK,
                    "text/plain",
                    ""
                )
            )
        }

        val response = when {
            session.method == Method.GET &&
                session.uri == "/api/status" -> {

                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """
                    {
                      "ok": true,
                      "engine": "media-downloader",
                      "platform": "android",
                      "version": "0.1.0"
                    }
                    """.trimIndent()
                )
            }

            session.method == Method.GET &&
                session.uri == "/api/versions" -> {

                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """
                    {
                      "ytdlp": {
                        "installed": "Not installed",
                        "latest": "Unknown"
                      },
                      "ffmpeg": {
                        "installed": "Not installed",
                        "latest": "Unknown"
                      }
                    }
                    """.trimIndent()
                )
            }

            else -> {

                newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "application/json",
                    """{"ok":false,"error":"Endpoint not found"}"""
                )
            }
        }

        return cors(response)
    }
}
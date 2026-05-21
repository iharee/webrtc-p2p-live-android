package io.github.iharee.webrtcp2pliveandroid.signaling

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class SignalingClient(
    private val serverUrl: String,
    private val onEvent: (SignalingMessage) -> Unit
) {
    private val okHttpClient = OkHttpClient()
    private val gson = Gson()
    private var webSocket: WebSocket? = null

    fun connect() {
        // Normalize ws:// → http:// and wss:// → https:// —
        // OkHttp handles HTTP→WS upgrade automatically.
        val normalizedUrl = serverUrl
            .replaceFirst("ws://", "http://")
            .replaceFirst("wss://", "https://")

        val request = try {
            Request.Builder().url(normalizedUrl).build()
        } catch (e: IllegalArgumentException) {
            onEvent(SignalingMessage(
                type = "error",
                error = "Invalid URL: ${e.message}"
            ))
            return
        }

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onEvent(SignalingMessage(type = "open"))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = gson.fromJson(text, SignalingMessage::class.java)
                    onEvent(message)
                } catch (e: Exception) {
                    onEvent(SignalingMessage(type = "parse-error"))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onEvent(SignalingMessage(type = "close", error = "code=$code reason=$reason"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val detail = buildString {
                    append(t.javaClass.simpleName)
                    append(": ")
                    append(t.message ?: "unknown")
                    if (response != null) {
                        append(" (httpCode=")
                        append(response.code)
                        append(", ")
                        append(response.message)
                        append(")")
                    }
                }
                onEvent(SignalingMessage(type = "error", error = detail))
            }
        })
    }

    fun join(role: String, roomId: String, token: String?) {
        val payload = mutableMapOf<String, Any?>(
            "role" to role,
            "roomId" to roomId
        )
        if (token != null) {
            payload["token"] = token
        }
        send("join", payload)
    }

    fun sendOffer(sdp: String) {
        send("offer", mapOf("sdp" to sdp))
    }

    fun sendAnswer(sdp: String) {
        send("answer", mapOf("sdp" to sdp))
    }

    fun sendIceCandidate(candidate: IceCandidatePayload) {
        send("ice-candidate", mapOf("candidate" to candidate))
    }

    fun close() {
        webSocket?.close(1000, null)
        webSocket = null
    }

    private fun send(type: String, payload: Map<String, Any?>) {
        val merged = payload.toMutableMap()
        merged["type"] = type
        val json = gson.toJson(merged)
        webSocket?.send(json)
    }
}

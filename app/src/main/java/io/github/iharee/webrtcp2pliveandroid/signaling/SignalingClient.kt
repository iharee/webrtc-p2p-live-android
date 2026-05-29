package io.github.iharee.webrtcp2pliveandroid.signaling

import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.webrtc.PeerConnection

class SignalingClient(
    private val serverUrl: String,
    private val onEvent: (SignalingMessage) -> Unit,
    private val okHttpClient: OkHttpClient = OkHttpClient()
) {
    private val gson = Gson()
    private var webSocket: WebSocket? = null

    /**
     * Callback invoked when the signaling server delivers dynamic ICE/TURN servers
     * inside a "joined" message. Clients should store these servers and use them
     * when creating a PeerConnection, instead of locally configured static credentials.
     */
    var onIceServers: ((List<PeerConnection.IceServer>) -> Unit)? = null

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

                    // If this is a "joined" message, extract dynamic ICE servers
                    // from the raw JSON and deliver via the dedicated callback.
                    if (message.type == "joined") {
                        val root = JsonParser.parseString(text).asJsonObject
                        if (root.has("iceServers") && root.get("iceServers").isJsonArray) {
                            val serversJson = root.getAsJsonArray("iceServers").toString()
                            val servers = parseIceServersJson(serversJson)
                            onIceServers?.invoke(servers)
                        }
                    }
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

    /**
     * Parses a JSON array of ICE server descriptors (the format emitted by the
     * signaling server's "joined" message) into a list of [PeerConnection.IceServer]
     * objects suitable for [PeerConnection.RTCConfiguration].
     *
     * Expected format:
     * ```json
     * [
     *   { "urls": "stun:stun.example.com:3478" },
     *   {
     *     "urls": ["turn:host:3478?transport=udp", "turn:host:3478?transport=tcp"],
     *     "username": "...",
     *     "credential": "..."
     *   }
     * ]
     * ```
     */
    private fun parseIceServersJson(json: String): List<PeerConnection.IceServer> {
        val servers = mutableListOf<PeerConnection.IceServer>()
        val arr = JsonParser.parseString(json).asJsonArray
        for (element in arr) {
            val obj = element.asJsonObject

            // "urls" can be a single string or an array of strings
            val urlsElement = obj.get("urls")
            val urlList: List<String> = when {
                urlsElement.isJsonArray -> urlsElement.asJsonArray.map { it.asString }
                urlsElement.isJsonPrimitive -> listOf(urlsElement.asString)
                else -> continue
            }

            // IceServer.builder() requires at least one URL; pass the first one
            // then override with the full list via setUrls().
            val builder = PeerConnection.IceServer.builder(urlList.first())
            if (urlList.size > 1) {
                builder.setUrls(urlList)
            }

            // Optional TURN credentials
            if (obj.has("username")) builder.setUsername(obj.get("username").asString)
            if (obj.has("credential")) builder.setPassword(obj.get("credential").asString)

            servers.add(builder.createIceServer())
        }
        return servers
    }

    private fun send(type: String, payload: Map<String, Any?>) {
        val merged = payload.toMutableMap()
        merged["type"] = type
        val json = gson.toJson(merged)
        webSocket?.send(json)
    }
}

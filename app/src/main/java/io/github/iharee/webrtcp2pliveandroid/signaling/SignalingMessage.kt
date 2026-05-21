package io.github.iharee.webrtcp2pliveandroid.signaling

data class SignalingMessage(
    val type: String,
    val sdp: String? = null,
    val candidate: IceCandidatePayload? = null,
    val token: String? = null,
    val reason: String? = null,
    val quality: String? = null,
    val maxBitrate: Long? = null,
    val error: String? = null
)

data class IceCandidatePayload(
    val sdpMid: String,
    val sdpMLineIndex: Int,
    val candidate: String
)

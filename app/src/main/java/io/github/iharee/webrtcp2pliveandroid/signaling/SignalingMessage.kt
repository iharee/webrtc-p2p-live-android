package io.github.iharee.webrtcp2pliveandroid.signaling

// NOTE: The `iceServers` field from the "joined" signaling message is NOT included
// in this data class because it is a complex nested JSON structure. Instead,
// SignalingClient extracts iceServers from the raw JSON and delivers them via a
// dedicated `onIceServers` callback. This avoids fragile nested-data-class mapping.

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

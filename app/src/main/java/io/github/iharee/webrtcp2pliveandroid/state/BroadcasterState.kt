package io.github.iharee.webrtcp2pliveandroid.state

sealed class BroadcasterState {
    data object Idle : BroadcasterState()

    data class Configuring(
        val serverUrl: String,
        val roomId: String,
        val token: String?
    ) : BroadcasterState()

    data object ConnectingSignal : BroadcasterState()
    data object WaitingViewer : BroadcasterState()

    data class Negotiating(val step: String = "creating-offer") : BroadcasterState()

    data class Connected(
        val quality: String = "auto",
        val maxBitrate: Long? = null
    ) : BroadcasterState()

    data class Failed(val reason: String) : BroadcasterState()
    data object Closed : BroadcasterState()
}

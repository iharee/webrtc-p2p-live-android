package io.github.iharee.webrtcp2pliveandroid.capture

import io.github.iharee.webrtcp2pliveandroid.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import io.github.iharee.webrtcp2pliveandroid.signaling.IceCandidatePayload
import io.github.iharee.webrtcp2pliveandroid.signaling.SignalingClient
import io.github.iharee.webrtcp2pliveandroid.signaling.SignalingMessage
import io.github.iharee.webrtcp2pliveandroid.state.BroadcasterState
import io.github.iharee.webrtcp2pliveandroid.webrtc.WebRTCManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpSender
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class ScreenCaptureService : Service() {

    // ======= Threading =======
    private lateinit var rtcThread: HandlerThread
    private lateinit var rtcHandler: Handler

    // ======= State (observed by Activity) =======
    private val _state = MutableStateFlow<BroadcasterState>(BroadcasterState.Idle)
    val state: StateFlow<BroadcasterState> = _state

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines

    // ======= Core components =======
    private var signalingClient: SignalingClient? = null
    private var webRTCManager: WebRTCManager? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var eglBase: EglBase? = null
    private var localSdp: String? = null
    private var pendingCandidates = mutableListOf<IceCandidatePayload>()
    private var videoSender: RtpSender? = null

    // ======= Stored config for signaling =======
    private var currentConfig: StreamConfig? = null

    // ======= Quality state =======
    private var captureWidth: Int = 0
    private var captureHeight: Int = 0
    private var baselineBitrate: Long = 0

    // ======= Binder =======
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    data class StreamConfig(
        val serverUrl: String,
        val roomId: String,
        val token: String?,
        val turnServer: String? = null,
        val turnUser: String? = null,
        val turnPass: String? = null
    )

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "streaming"
        private const val CHANNEL_NAME = "Screen Streaming"
        private const val STATS_INTERVAL_MS = 5000L
    }

    // ======= Lifecycle =======

    override fun onCreate() {
        super.onCreate()
        rtcThread = HandlerThread("RTC").apply { start() }
        rtcHandler = Handler(rtcThread.looper)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopStreaming()
        rtcThread.quitSafely()
        super.onDestroy()
    }

    // ======= Public API (called from Activity via binder) =======

    fun startStreaming(config: StreamConfig, resultCode: Int, data: Intent) {
        // PeerConnectionFactory.initialize() and factory creation must be on main thread.
        // Create a shared EGL context on main thread for both factory and capturer.
        WebRTCManager.initFactory(applicationContext)
        webRTCManager = WebRTCManager(this)
        val sharedEgl = EglBase.create()
        val factory = webRTCManager!!.createFactory(sharedEgl.eglBaseContext)
        rtcHandler.post {
            startStreamingInternal(config, resultCode, data, factory, sharedEgl)
        }
    }

    fun stopStreaming() {
        rtcHandler.post {
            stopStreamingInternal()
        }
    }

    // ======= Internal implementation =======

    private fun startStreamingInternal(config: StreamConfig, resultCode: Int, data: Intent, factory: PeerConnectionFactory, sharedEgl: EglBase) {
        currentConfig = config
        log("Starting stream to ${config.serverUrl} room=${config.roomId}")
        transitionTo(BroadcasterState.ConnectingSignal)

        // 1. Start foreground (must happen before getMediaProjection on Android 14+)
        startForeground(NOTIFICATION_ID, buildNotification())

        // 2. Use shared EglBase (created on main thread for factory)
        eglBase = sharedEgl

        // 3. PeerConnectionFactory was already created on the main thread
        peerConnectionFactory = factory

        // 4. Create video source
        videoSource = peerConnectionFactory!!.createVideoSource(false)

        // 5. Set up screen capture
        val displayMetrics = resources.displayMetrics
        captureWidth = displayMetrics.widthPixels
        captureHeight = displayMetrics.heightPixels
        baselineBitrate = computeBaselineBitrate(captureWidth, captureHeight)

        surfaceTextureHelper = SurfaceTextureHelper.create(
            "capture",
            eglBase!!.eglBaseContext
        )

        screenCapturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() {
                rtcHandler.post { stopStreaming() }
            }
        })
        screenCapturer!!.initialize(
            surfaceTextureHelper,
            this,
            videoSource!!.capturerObserver
        )
        screenCapturer!!.startCapture(captureWidth, captureHeight, 30)
        log("Screen capture started ${captureWidth}x${captureHeight} @30fps, baseline bitrate=$baselineBitrate")

        // 6. Connect signaling
        signalingClient = SignalingClient(config.serverUrl, { msg -> onSignalingEvent(msg) }, createOkHttpClient())
        signalingClient!!.connect()
        log("Signaling client connecting...")
    }

    private fun stopStreamingInternal() {
        log("Stopping stream...")
        stopStatsLoop()
        reset()
        transitionTo(BroadcasterState.Closed)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    // ======= Signaling event bridge =======

    private fun onSignalingEvent(msg: SignalingMessage) {
        rtcHandler.post { handleSignalingEvent(msg) }
    }

    private fun handleSignalingEvent(msg: SignalingMessage) {
        log("Signaling event: ${msg.type}")

        when (msg.type) {
            "open" -> {
                val roomId = currentConfig?.roomId ?: ""
                signalingClient?.join("broadcaster", roomId, msg.token ?: currentConfig?.token)
                log("Joined room as broadcaster")
            }

            "joined" -> {
                transitionTo(BroadcasterState.WaitingViewer)
                log("Waiting for viewer...")
            }

            "viewer-joined" -> {
                transitionTo(BroadcasterState.Negotiating("creating-offer"))
                createPeerConnectionAndOffer()
            }

            "answer" -> {
                if (msg.sdp != null && peerConnection != null) {
                    log("Received answer, setting remote description")
                    val answer = SessionDescription(SessionDescription.Type.ANSWER, msg.sdp)
                    peerConnection!!.setRemoteDescription(
                        object : SdpObserverAdapter() {
                            override fun onCreateSuccess(p0: SessionDescription?) {
                                log("Remote description set successfully")
                            }

                            override fun onSetSuccess() {
                                flushPendingCandidates()
                            }
                        },
                        answer
                    )
                }
            }

            "ice-candidate" -> {
                msg.candidate?.let { payload ->
                    val iceCandidate = IceCandidate(
                        payload.sdpMid,
                        payload.sdpMLineIndex,
                        payload.candidate
                    )
                    if (pendingCandidates.isEmpty() && peerConnection?.remoteDescription != null) {
                        peerConnection?.addIceCandidate(iceCandidate)
                    } else {
                        pendingCandidates.add(payload)
                    }
                }
            }

            "peer-left" -> {
                log("Peer left, returning to waiting")
                closePeerConnection()
                transitionTo(BroadcasterState.WaitingViewer)
            }

            "quality-change" -> {
                val quality = msg.quality ?: "auto"
                val maxBitrate = msg.maxBitrate
                videoSender?.let { sender ->
                    webRTCManager?.setQuality(sender, quality, baselineBitrate, maxBitrate)
                }
                val newState = BroadcasterState.Connected(quality = quality, maxBitrate = maxBitrate)
                transitionTo(newState)
            }

            "rejected" -> {
                val reason = msg.reason ?: "Unknown reason"
                log("Connection rejected: $reason")
                transitionTo(BroadcasterState.Failed(reason))
            }

            "close", "error" -> {
                val detail = msg.error ?: ""
                log("Signaling ${msg.type}: $detail")
                stopStreaming()
            }

            "parse-error" -> {
                log("Signaling parse error")
            }
        }
    }

    // ======= Peer connection management =======

    private fun createPeerConnectionAndOffer() {
        val factory = peerConnectionFactory ?: run {
            log("ERROR: PeerConnectionFactory is null")
            return
        }

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let { ice ->
                    val payload = IceCandidatePayload(
                        sdpMid = ice.sdpMid,
                        sdpMLineIndex = ice.sdpMLineIndex,
                        candidate = ice.sdp
                    )
                    rtcHandler.post {
                        signalingClient?.sendIceCandidate(payload)
                    }
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                rtcHandler.post {
                    log("Signaling state: $state")
                }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                rtcHandler.post {
                    log("ICE connection state: $state")
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                rtcHandler.post {
                    log("Connection state: $newState")
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            transitionTo(
                                BroadcasterState.Connected(
                                    quality = (_state.value as? BroadcasterState.Connected)?.quality
                                        ?: "auto",
                                    maxBitrate = (_state.value as? BroadcasterState.Connected)?.maxBitrate
                                )
                            )
                            startStatsLoop()
                        }

                        PeerConnection.PeerConnectionState.FAILED,
                        PeerConnection.PeerConnectionState.DISCONNECTED -> {
                            log("Connection failed/disconnected, resetting")
                            closePeerConnection()
                            transitionTo(BroadcasterState.WaitingViewer)
                        }

                        else -> {}
                    }
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                rtcHandler.post {
                    log("ICE gathering state: $state")
                }
            }

            override fun onAddStream(stream: org.webrtc.MediaStream?) {}

            override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}

            override fun onDataChannel(channel: org.webrtc.DataChannel?) {}

            override fun onRenegotiationNeeded() {
                rtcHandler.post {
                    log("Renegotiation needed")
                }
            }

            override fun onAddTrack(
                receiver: org.webrtc.RtpReceiver?,
                streams: Array<out org.webrtc.MediaStream>?
            ) {}
        }

        val cfg = currentConfig
        val iceServers = buildList {
            add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
            val turnHost = cfg?.turnServer?.removePrefix("turn:")?.removePrefix("turns:")?.split("?")?.first()?.trim()
            if (!turnHost.isNullOrBlank()) {
                val user = cfg.turnUser?.ifBlank { "test" } ?: "test"
                val pass = cfg.turnPass?.ifBlank { "test" } ?: "test"
                add(PeerConnection.IceServer.builder("turn:$turnHost:3478")
                    .setUsername(user)
                    .setPassword(pass)
                    .createIceServer())
            }
        }

        peerConnection = webRTCManager!!.createPeerConnection(factory, iceServers, observer)
        if (peerConnection == null) {
            log("ERROR: Failed to create PeerConnection")
            transitionTo(BroadcasterState.Failed("PeerConnection creation failed"))
            return
        }
        log("PeerConnection created")

        // Create audio source / track and add to peer connection
        val audioConstraints = MediaConstraints()
        audioSource = factory.createAudioSource(audioConstraints)
        audioTrack = factory.createAudioTrack("audio", audioSource)
        peerConnection!!.addTrack(audioTrack, listOf("audio"))

        // Create video track and add to peer connection
        videoTrack = factory.createVideoTrack("video", videoSource)
        videoSender = peerConnection!!.addTrack(videoTrack, listOf("video"))
        log("Tracks added to peer connection")

        // Create offer
        val offerConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("offerToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("offerToReceiveAudio", "false"))
        }

        peerConnection!!.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let { sessionDescription ->
                    rtcHandler.post {
                        localSdp = sessionDescription.description
                        peerConnection?.setLocalDescription(
                            object : SdpObserverAdapter() {
                                override fun onSetSuccess() {
                                    log("Local description set, sending offer")
                                    signalingClient?.sendOffer(sessionDescription.description)
                                }
                            },
                            sessionDescription
                        )
                    }
                }
            }
        }, offerConstraints)
    }

    private fun flushPendingCandidates() {
        if (pendingCandidates.isEmpty()) return

        log("Flushing ${pendingCandidates.size} pending ICE candidates")
        val pc = peerConnection ?: return

        for (payload in pendingCandidates) {
            val iceCandidate = IceCandidate(
                payload.sdpMid,
                payload.sdpMLineIndex,
                payload.candidate
            )
            pc.addIceCandidate(iceCandidate)
        }
        pendingCandidates.clear()
    }

    private fun closePeerConnection() {
        peerConnection?.close()
        peerConnection = null
        videoTrack = null
        audioTrack = null
        audioSource = null
        videoSender = null
        localSdp = null
        pendingCandidates.clear()
        log("Peer connection closed")
    }

    private fun reset() {
        closePeerConnection()

        screenCapturer?.stopCapture()
        screenCapturer?.dispose()
        screenCapturer = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        signalingClient?.close()
        signalingClient = null

        videoSource = null

        eglBase?.release()
        eglBase = null

        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        webRTCManager = null

        pendingCandidates.clear()
        localSdp = null
        currentConfig = null

        log("Reset complete")
    }

    // ======= Periodic stats =======

    private var statsActive = false

    private val statsRunnable = object : Runnable {
        override fun run() {
            if (!statsActive) return
            val pc = peerConnection
            val manager = webRTCManager
            if (pc != null && manager != null) {
                manager.getStats(pc) { statsJson ->
                    log("Stats: $statsJson")
                }
            }
            rtcHandler.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    private fun startStatsLoop() {
        if (statsActive) return
        statsActive = true
        rtcHandler.postDelayed(statsRunnable, STATS_INTERVAL_MS)
    }

    private fun stopStatsLoop() {
        statsActive = false
        rtcHandler.removeCallbacks(statsRunnable)
    }

    // ======= Helpers =======

    private fun log(msg: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val entry = "$timestamp | $msg"
        _logLines.value = (_logLines.value + entry).takeLast(500)
    }

    private fun transitionTo(newState: BroadcasterState) {
        _state.value = newState
        log("[State] $newState")
    }

    private fun computeBaselineBitrate(width: Int, height: Int): Long {
        val pixels = width.toLong() * height.toLong()
        val raw = pixels * 30L / 20L
        return raw.coerceIn(1_000_000L, 10_000_000L)
    }

    // ======= Notification =======

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while screen streaming is active"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            pendingIntentFlags
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Streaming active")
                .setContentText("Screen is being broadcast")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Streaming active")
                .setContentText("Screen is being broadcast")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    // ======= OkHttp with custom CA trust =======

    private fun createOkHttpClient(): OkHttpClient {
        val cf = CertificateFactory.getInstance("X.509")
        val caCert = resources.openRawResource(R.raw.webrtc_ca).use { cf.generateCertificate(it) }

        val customKeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("webrtc-ca", caCert)
        }

        val customTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(customKeyStore)
        }
        val systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(null as KeyStore?)
        }

        val customTM = customTmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
        val systemTM = systemTmf.trustManagers.first { it is X509TrustManager } as X509TrustManager

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(CompositeTrustManager(customTM, systemTM)), SecureRandom())
        }

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, customTM)
            .build()
    }

    private class CompositeTrustManager(
        private val custom: X509TrustManager,
        private val system: X509TrustManager
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            try { custom.checkClientTrusted(chain, authType) } catch (_: Exception) { system.checkClientTrusted(chain, authType) }
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            try { custom.checkServerTrusted(chain, authType) } catch (_: Exception) { system.checkServerTrusted(chain, authType) }
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = custom.acceptedIssuers
    }

    // ======= SdpObserver adapter for concise setRemote/setLocal calls =======

    private open class SdpObserverAdapter : org.webrtc.SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }
}

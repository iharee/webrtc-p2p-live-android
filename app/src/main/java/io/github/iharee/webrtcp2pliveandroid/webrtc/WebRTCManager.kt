package io.github.iharee.webrtcp2pliveandroid.webrtc

import android.content.Context
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RTCStatsReport
import org.webrtc.RtpSender
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import org.json.JSONObject

class WebRTCManager(private val context: Context) {

    companion object {
        @Volatile
        private var initialized = false

        fun initFactory(appContext: Context) {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                val options = PeerConnectionFactory.InitializationOptions.builder(appContext)
                    .createInitializationOptions()
                PeerConnectionFactory.initialize(options)
                initialized = true
                android.util.Log.i("WebRTCManager", "PeerConnectionFactory.initialize OK")
            }
        }
    }

    fun createFactory(eglContext: EglBase.Context? = null): PeerConnectionFactory {
        val adm = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        val encoderFactory = DefaultVideoEncoderFactory(eglContext, true, false)
        val decoderFactory = DefaultVideoDecoderFactory(eglContext)

        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun createVideoTrack(factory: PeerConnectionFactory, source: VideoSource): VideoTrack {
        return factory.createVideoTrack("video", source)
    }

    fun createAudioTrack(factory: PeerConnectionFactory, source: AudioSource): AudioTrack {
        return factory.createAudioTrack("audio", source)
    }

    fun createPeerConnection(
        factory: PeerConnectionFactory,
        iceServers: List<PeerConnection.IceServer>,
        observer: PeerConnection.Observer
    ): PeerConnection? {
        val config = PeerConnection.RTCConfiguration(iceServers)
        val pc = factory.createPeerConnection(config, observer)
        if (pc == null) {
            android.util.Log.e("WebRTCManager", "createPeerConnection returned null")
        }
        return pc
    }

    fun setQuality(
        sender: RtpSender,
        quality: String,
        baselineBitrate: Long,
        maxBitrate: Long?
    ) {
        try {
            val params = sender.parameters
            val targetBitrate: Long? = when (quality) {
                "auto" -> null
                "high" -> baselineBitrate
                "low" -> (baselineBitrate * 0.5).toLong()
                "custom" -> maxBitrate
                else -> null
            }

            for (encoding in params.encodings) {
                encoding.maxBitrateBps = targetBitrate?.toInt()
            }

            sender.parameters = params
        } catch (_: Exception) { }
    }

    fun getStats(connection: PeerConnection, callback: (String) -> Unit) {
        connection.getStats(object : RTCStatsCollectorCallback {
            override fun onStatsDelivered(report: RTCStatsReport) {
                val json = JSONObject()
                for ((key, stat) in report.statsMap) {
                    val memberJson = JSONObject()
                    for (member in stat.members) {
                        memberJson.put(member.key, member.value.toString())
                    }
                    json.put(key, memberJson)
                }
                callback(json.toString())
            }
        })
    }
}

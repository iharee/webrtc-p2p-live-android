package io.github.iharee.webrtcp2pliveandroid.webrtc

import android.content.Context
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
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

    fun initialize(): PeerConnectionFactory {
        val adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(
                    null,
                    true,
                    true
                )
            )
            .setVideoDecoderFactory(
                DefaultVideoDecoderFactory(null)
            )
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
    ): PeerConnection {
        val config = PeerConnection.RTCConfiguration(iceServers)
        val pc = factory.createPeerConnection(config, observer)
            ?: throw IllegalStateException("Failed to create PeerConnection")
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
        } catch (_: Exception) {
            // Setting parameters failed; silently ignore
        }
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

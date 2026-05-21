# WebRTC JNI classes — must never be obfuscated or stripped
-keep class org.webrtc.** { *; }
-keep class io.getstream.webrtc.** { *; }

# Gson data classes
-keep class io.github.iharee.webrtcp2pliveandroid.signaling.SignalingMessage { *; }

# webrtc-p2p-live-android

Android screen broadcaster for [webrtc-p2p-live](https://github.com/iharee/webrtc-p2p-live) — a 1-to-1 WebRTC P2P live streaming system.

Capture your Android screen and stream it in real time to a web viewer. No account, no cloud relay — just a direct peer connection.

## Features

- **Screen capture** via `MediaProjection` API, with foreground service
- **Screen rotation handling** — flip from portrait to landscape without dropping the connection. The capturer pipeline is resized in-place via reflection; no PeerConnection rebuild, no renegotiation
- **P2P streaming** — direct WebRTC connection between broadcaster and viewer
- **STUN / TURN** — configurable TURN credentials for NAT traversal
- **TLS signaling** — custom CA certificate support for self-signed signaling servers
- **Quality adaptation** — viewer can request auto / high / low / custom bitrate
- **Token-based rooms** — share a token with your viewer for private access
- **Jetpack Compose UI** — Material 3 with config form, live status, and scrollable debug log
- **Background streaming** — foreground service keeps the stream alive when the app is backgrounded

## Architecture

```
MainActivity (Compose UI)
    │
    ▼
ScreenCaptureService (foreground service)
    ├── ScreenCapturerAndroid   ──  MediaProjection → VirtualDisplay
    ├── PeerConnection          ──  WebRTC video + audio track
    ├── SignalingClient         ──  OkHttp WebSocket signaling
    └── WebRTCManager           ──  factory, stats, quality control
```

The service owns the full WebRTC lifecycle. The Activity binds to it and observes state/logs via `StateFlow`. The WebSocket signaling server relays SDP and ICE candidates between broadcaster and viewer.

## Companion project

This is the Android broadcaster half of the system. You also need:

- **[webrtc-p2p-live](https://github.com/iharee/webrtc-p2p-live)** — Node.js signaling server + web broadcaster/viewer client

## Getting started

### Prerequisites

- Android Studio (Hedgehog or later)
- JDK 21
- A device or emulator running Android 8.0+ (API 26)

### Build

*Windows PowerShell*

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
.\gradlew assembleDebug
```

*Bash*

```bash
export JAVA_HOME="/path/to/jdk-21"
./gradlew assembleDebug
```

### Run

1. Start the signaling server from [webrtc-p2p-live](https://github.com/iharee/webrtc-p2p-live) (`node server/server.js`)
2. Open the viewer page on a PC browser (`https://<server>:8080/live/<room>/viewer.html`)
3. Launch the Android app, enter the server URL and room ID, tap **Start Streaming**
4. Grant screen capture permission when prompted

## Configuration

| Field | Description |
|---|---|
| Server URL | WebSocket signaling server (e.g. `wss://192.168.1.100:8080`) |
| Room ID | Shared room name, matches the viewer's URL path |
| Token | Optional room password — viewer must supply the same token |
| TURN Server / Username / Password | Optional TURN relay for NAT traversal |

## How screen rotation works

Android's `MediaProjection.createVirtualDisplay()` can only be called once per token. Rather than recreating the capturer (which would require re-authorization), the app:

1. Detects the orientation change via `onConfigurationChanged`
2. Resizes the existing `VirtualDisplay` via reflection
3. Updates the `SurfaceTexture` buffer and `SurfaceTextureHelper` internal size tracking
4. The WebRTC encoder detects the new frame resolution and sends a fresh keyframe

The viewer sees a brief freeze (~0.5s) then the stream resumes at the correct aspect ratio. The PeerConnection stays up the entire time.

## Tech stack

| Component | Library |
|---|---|
| WebRTC | `io.getstream:stream-webrtc-android:1.3.9` |
| Signaling | `com.squareup.okhttp3:okhttp:4.12.0` |
| JSON | `com.google.code.gson:gson:2.11.0` |
| UI | Jetpack Compose + Material 3 |
| Language | Kotlin 2.0.21 |
| Build | AGP 8.11.1, Gradle |

## License

MIT

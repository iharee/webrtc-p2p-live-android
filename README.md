# webrtc-p2p-live-android

Android screen broadcaster for [webrtc-p2p-live](https://github.com/iharee/webrtc-p2p-live) — a 1-to-1 WebRTC P2P live streaming system.

Capture your Android screen and stream it in real time to a web viewer. No account, no cloud relay — just a direct peer connection.

## Motivation

[webrtc-p2p-live](https://github.com/iharee/webrtc-p2p-live) relies on the web platform's `getDisplayMedia()` API for screen capture. This API is unavailable on Android Chrome — the browser simply does not expose it. As a result, the web-based broadcaster cannot function on Android devices.

This application replaces the browser-based capture path with Android's native `MediaProjection` API, feeding captured frames directly into a WebRTC peer connection. The signaling protocol, P2P topology, and viewer experience remain identical to the web broadcaster.

## Features

- **Screen capture** via `MediaProjection` API, with foreground service
- **Screen rotation handling** — flip from portrait to landscape without dropping the connection. The capturer pipeline is resized in-place via reflection; no PeerConnection rebuild, no renegotiation
- **P2P streaming** — direct WebRTC connection between broadcaster and viewer
- **Dynamic TURN credentials** — ICE servers delivered by signaling server after room join, no local config needed
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

## Custom CA certificates

When connecting to a signaling server that uses a self-signed or private CA certificate, place the CA's PEM file in the `certs/` directory at the project root:

```
certs/
  webrtc_ca.pem
  my-org-ca.pem        ← drop additional certs here
```

The build system (Gradle `processCerts` task) scans `certs/*.pem` and automatically:

- Includes every certificate in the Android **network security config** (trusted by `HttpsURLConnection`, WebView, etc.)
- Loads every certificate into the **OkHttp `SSLSocketFactory`** (trusted by the WebRTC signaling WebSocket)
- Generates a `CertResources.kt` index so the app trusts all certs at runtime — no code changes needed

**Adding a new cert** is just dropping a `.pem` file into `certs/` and rebuilding. **Removing a cert** is deleting the file and rebuilding.

If the `certs/` directory is empty, the app trusts only the system CA store (standard Android behavior).

## How screen rotation works

**Problem**: when the device rotates, the capturer resolution remains unchanged. The viewer receives frames at the original dimensions — landscape content rendered into a portrait frame, with the active picture region reduced to a narrow strip.

### Other approaches attempted

**Re-create the capturer with a new `ScreenCapturerAndroid`**: passing the existing `mediaProjectionData` to a second `ScreenCapturerAndroid` instance fails because `MediaProjectionManager.getMediaProjection()` enforces a single invocation per token.

**Stop and restart the existing capturer**: invoking `stopCapture()` followed by `startCapture(newWidth, newHeight)` on the same instance fails because `startCapture()` internally calls `MediaProjection.createVirtualDisplay()`, which is limited to one call per `MediaProjection` lifetime regardless of prior release.

### Implemented solution: reflection-based in-place resize

The capturer remains running. Three components in the capture pipeline are resized via Java reflection without tearing down the peer connection (`ScreenCaptureService.rotateCapturer()`):

| Component | Reflection target | Purpose |
|---|---|---|
| `VirtualDisplay` | `resize(w, h, dpi)` | Resize the system-level rendering surface |
| `SurfaceTexture` | `setDefaultBufferSize(w, h)` | Resize the GPU buffer to match |
| `SurfaceTextureHelper` | `textureWidth` / `textureHeight` fields | Update frame dimension metadata sent to the encoder |

All three modifications are necessary. Resizing only the `VirtualDisplay` produces no error, but frames continue to carry the original dimensions — `SurfaceTextureHelper` constructs `VideoFrame` instances using its cached `textureWidth` and `textureHeight` fields, so the encoder is never notified of the resolution change. The pipeline forms a chain (system rendering → GPU buffer → encoder metadata), and each link must be updated for the encoder to emit correctly-sized frames.

### Associated changes

**Android (this repository)**:
- `ScreenCaptureService.kt` — `rotateCapturer()` resizes the three components via reflection and calls `WebRTCManager.setQuality()` to recompute the bitrate for the new resolution
- `MainActivity.kt` — `onConfigurationChanged()` detects the orientation and delegates to `service.onRotation()`
- `AndroidManifest.xml` — `configChanges="orientation|screenSize|..."` suppresses Activity destruction on configuration change, keeping the service binding intact

**Web viewer (webrtc-p2p-live)**:
- `style.css` — the fixed `aspect-ratio: 16/9` rule is removed; `object-fit: contain` is retained so the video element adapts to the stream's native aspect ratio

**Unnecessary operations**: the peer connection is neither closed nor renegotiated (no offer/answer exchange); no track replacement occurs. The viewer requires no JavaScript changes — rotation is handled entirely on the sender side.

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

# Android Broadcaster App — Implementation Plan (V2)

> **Goal:** Build a production-quality Kotlin Android app that replaces the web broadcaster page, reusing the existing signaling server and interoperating with the web viewer.

## Motivation

Android browsers (Chrome, Samsung Internet, Firefox) do not support `getDisplayMedia()`. The web broadcaster page is unusable on Android — attempting to start streaming shows "当前浏览器不支持屏幕共享". A native Android app using `MediaProjection` API fully solves this. The Web viewer remains unchanged (mobile browsers can play WebRTC streams without issue).

## Architecture: Service-Driven

**Core insight:** An Activity-centric design (Web mindset) collapses on Android. The Activity can be destroyed at any time (rotation, memory pressure, background). If the Activity owns `PeerConnection` and `SignalingClient`, the stream dies with it — even though a foreground service is running.

**Solution:** The foreground service owns everything. The Activity is a thin UI shell that binds to the service and observes state.

```
┌──────────────────────────────────────────────────────────────┐
│ ScreenCaptureService (Foreground Service)                    │
│                                                              │
│  StateMachine ──(StateFlow)──> UI (MainActivity)             │
│      │                                                       │
│      ▼ (rtcHandler.post)                                     │
│  ┌──────────────────────────────────┐                        │
│  │  RTC Thread (HandlerThread)      │                        │
│  │                                  │                        │
│  │  WebRTCManager ── PeerConnection │                        │
│  │  ScreenCapturer ── MediaProj.    │                        │
│  │  SignalingClient ── OkHttp WS    │◄─── JSON ──► Server    │
│  │  AudioDeviceModule ── Mic        │                        │
│  └──────────────────────────────────┘                        │
└──────────────────────────────────────────────────────────────┘
```

| Principle | Rule |
|-----------|------|
| **Service ownership** | `ScreenCaptureService` owns `SignalingClient`, `WebRTCManager`, `ScreenCapturer`, `StateMachine` |
| **Thread isolation** | All WebRTC calls go through a dedicated `HandlerThread`. OkHttp callbacks only forward JSON to the RTC thread — never touch WebRTC objects directly |
| **State machine** | Every async transition (connect, join, negotiate, stream, fail) flows through a sealed-class state machine — no scattered `if-else` flags |
| **Activity = UI shell** | `MainActivity` binds to the service, sends user intents, and renders `StateFlow<State>`. No media logic lives here |

## Environment (confirmed)

| Item | Value |
|------|-------|
| JDK | Eclipse Temurin 21.0.11 |
| AGP | 8.11.1 |
| Kotlin | 2.0.21 |
| Gradle | 8.13 |
| compileSdk / targetSdk | 36 |
| minSdk | 26 |
| Android SDK | 35 + 36 installed |
| build-tools | 35.0.1 + 36.0.0 |

## Tech Stack

| Concern | Choice |
|---------|--------|
| **Language** | Kotlin |
| **UI** | Jetpack Compose + Material 3 (`AndroidView` for WebRTC SurfaceViewRenderer when needed) |
| **WebRTC** | `io.getstream:stream-webrtc-android:1.3.9` |
| **WebSocket** | OkHttp WebSocket |
| **JSON** | Gson 2.11.0 |
| **Screen capture** | `MediaProjection` API, captured inside foreground service |
| **Audio** | `JavaAudioDeviceModule` — libwebrtc manages mic, echo cancellation, audio routing |
| **Thread model** | `HandlerThread("RTC")` — dedicated single thread for all WebRTC operations |
| **State management** | `StateFlow<BroadcasterState>` exposed from service, collected by Activity |

## Dependencies

```kotlin
// Add to app/build.gradle.kts:
implementation("io.getstream:stream-webrtc-android:1.3.9")
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("com.google.code.gson:gson:2.11.0")
```

## Manifest

Current manifest needs these additions:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- Inside <application>: -->
<service
    android:name=".capture.ScreenCaptureService"
    android:foregroundServiceType="mediaProjection"
    android:exported="false" />
```

---

## Tasks

### Task 1: Project Scaffolding + ProGuard

- [ ] **Step 1: Add dependencies** — OkHttp, Gson, stream-webrtc-android to `app/build.gradle.kts` and `gradle/libs.versions.toml`

- [ ] **Step 2: Add Manifest permissions + service declaration** (see above)

- [ ] **Step 3: ProGuard rules**

  ```proguard
  # app/proguard-rules.pro
  # WebRTC JNI classes — must never be obfuscated or stripped
  -keep class org.webrtc.** { *; }
  -keep class io.getstream.webrtc.** { *; }

  # Gson data classes
  -keep class io.github.iharee.webrtcp2pliveandroid.signaling.SignalingMessage { *; }
  ```

- [ ] **Step 4: Strip template code** — Remove `Greeting` composable. Replace `MainActivity` body with an empty Compose shell (just a `Scaffold` + placeholder `Text`)

- [ ] **Step 5: Verify build** — `./gradlew assembleDebug` must succeed

- [ ] **Step 6: Commit**

### Task 2: State Machine + Signaling Client

**Files:**
- Create: `app/src/main/java/io/github/iharee/webrtcp2pliveandroid/signaling/SignalingClient.kt`
- Create: `app/src/main/java/io/github/iharee/webrtcp2pliveandroid/signaling/SignalingMessage.kt`
- Create: `app/src/main/java/io/github/iharee/webrtcp2pliveandroid/state/BroadcasterState.kt`

#### 2a. Finite State Machine

Define a strict sealed class. Every state transition is explicit — no loose boolean flags:

```kotlin
// state/BroadcasterState.kt
sealed class BroadcasterState {
    data object Idle : BroadcasterState()

    // Config → user inputs server/room, but hasn't started yet
    data class Configuring(
        val serverUrl: String,
        val roomId: String,
        val token: String?
    ) : BroadcasterState()

    data object ConnectingSignal : BroadcasterState()
    data object WaitingViewer : BroadcasterState()

    // SDP/ICE exchange in progress
    data class Negotiating(val step: String = "creating-offer") : BroadcasterState()

    data class Connected(
        val quality: String = "auto",
        val maxBitrate: Long? = null
    ) : BroadcasterState()

    data class Failed(val reason: String) : BroadcasterState()
    data object Closed : BroadcasterState()
}

// Allowed transitions:
// Idle → Configuring
// Configuring → ConnectingSignal
// ConnectingSignal → WaitingViewer | Failed
// WaitingViewer → Negotiating | Failed
// Negotiating → Connected | Failed
// Connected → Failed | Closed
// any → Closed (user stop)
```

#### 2b. Signaling Message Types

```kotlin
// signaling/SignalingMessage.kt
data class SignalingMessage(
    val type: String,
    val sdp: String? = null,
    val candidate: IceCandidatePayload? = null,
    val token: String? = null,
    val reason: String? = null,
    val quality: String? = null,
    val maxBitrate: Long? = null
)

data class IceCandidatePayload(
    val sdpMid: String,
    val sdpMLineIndex: Int,
    val candidate: String
)
```

#### 2c. SignalingClient

Runs on OkHttp's internal thread. **Never touches WebRTC objects directly.** Receives JSON, deserializes with error protection, forwards to caller via a callback. The caller is responsible for posting into the RTC thread.

```kotlin
// signaling/SignalingClient.kt
class SignalingClient(
    private val serverUrl: String,
    private val onEvent: (SignalingMessage) -> Unit
) {
    private val client = OkHttpClient()
    private var ws: WebSocket? = null
    private val gson = Gson()

    fun connect() {
        val request = Request.Builder().url(serverUrl).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onEvent(SignalingMessage("open"))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                // Defensive parse — bad JSON must not crash the process
                val msg = try {
                    gson.fromJson(text, SignalingMessage::class.java)
                } catch (e: Exception) {
                    SignalingMessage("parse-error")
                }
                onEvent(msg)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onEvent(SignalingMessage("error"))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onEvent(SignalingMessage("close"))
            }
        })
    }

    fun join(role: String, roomId: String, token: String?) {
        send("join", mapOf("role" to role, "roomId" to roomId, "token" to token))
    }

    fun sendOffer(sdp: String)      { send("offer", mapOf("sdp" to sdp)) }
    fun sendAnswer(sdp: String)     { send("answer", mapOf("sdp" to sdp)) }
    fun sendIceCandidate(candidate: IceCandidatePayload) {
        send("ice-candidate", mapOf("candidate" to candidate))
    }

    private fun send(type: String, payload: Map<String, Any?>) {
        val merged = payload.toMutableMap()
        merged["type"] = type
        ws?.send(gson.toJson(merged))
    }

    fun close() { ws?.close(1000, null) }
}
```

- [ ] **Step 1: Write `BroadcasterState` sealed class + `SignalingMessage` data class**
- [ ] **Step 2: Write `SignalingClient` with OkHttp WebSocket + defensive JSON parse**
- [ ] **Step 3: Commit**

### Task 3: ScreenCaptureService — The Core

**Files:**
- Create: `app/src/main/java/io/github/iharee/webrtcp2pliveandroid/capture/ScreenCaptureService.kt`
- Create: `app/src/main/java/io/github/iharee/webrtcp2pliveandroid/webrtc/WebRTCManager.kt`

This is where everything lives. The service is the owner of all core components.

```
ScreenCaptureService
├── rtcHandler (HandlerThread "RTC")
├── state: MutableStateFlow<BroadcasterState>
├── logLines: MutableStateFlow<List<String>>     ← survives Activity death
├── signalingClient: SignalingClient
├── webRTCManager: WebRTCManager
│   ├── peerConnectionFactory
│   ├── peerConnection
│   ├── videoSource / videoTrack
│   └── audioSource / audioTrack
└── screenCapturer: ScreenCapturerAndroid
```

#### 3a. Thread Model

```kotlin
class ScreenCaptureService : Service() {

    // The single thread where ALL WebRTC calls happen
    private val rtcThread = HandlerThread("RTC").apply { start() }
    private val rtcHandler = Handler(rtcThread.looper)

    // All state mutations happen inside rtcHandler.post {}
    private val _state = MutableStateFlow<BroadcasterState>(BroadcasterState.Idle)
    val state: StateFlow<BroadcasterState> = _state

    // Log survives Activity destruction — stored in service, not ViewModel
    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines

    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _logLines.value = _logLines.value + "$ts $msg"
    }

    private fun transitionTo(newState: BroadcasterState) {
        _state.value = newState
        log("State → ${newState::class.simpleName}")
    }
}
```

**Rule:** Every method after `onEvent` receives a message follows this pattern:

```kotlin
private fun onSignalingEvent(msg: SignalingMessage) {
    rtcHandler.post {
        // SAFE: we are now on the RTC thread
        handleEvent(msg)
    }
}
```

OkHttp callbacks → `rtcHandler.post { }` → WebRTC operations. The bridge never crosses thread boundaries.

#### 3b. MediaProjection Flow (strict ordering)

```
1. Activity: create NotificationChannel (one-time)
2. Activity: request POST_NOTIFICATIONS permission (API 33+)
3. Activity: start foreground service (service starts, shows notification)
4. Activity: launch MediaProjection intent → system dialog appears
5. User taps "Start now"
6. Activity.onActivityResult: forward resultCode + data to service
7. Service (on RTC thread): getMediaProjection() → init capturer → start capture
8. Service: connect WebSocket → join room → wait for viewer
```

**Critical:** Step 7 must happen inside the already-running foreground service, on the RTC thread. Calling `getMediaProjection()` before `startForeground()` is a `SecurityException` on Android 14+.

#### 3c. WebRTCManager

```kotlin
class WebRTCManager(context: Context) {
    // Initialize PeerConnectionFactory with hardware codec preferences
    // and JavaAudioDeviceModule for automatic mic management:

    fun initialize(): PeerConnectionFactory {
        val adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(
                EglBase.CONTEXT_RELEASE, true, true  // enable HW encoder
            ))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(
                EglBase.CONTEXT_RELEASE
            ))
            .createPeerConnectionFactory()
    }

    fun createVideoTrack(factory: PeerConnectionFactory, source: VideoSource): VideoTrack
    fun createAudioTrack(factory: PeerConnectionFactory, source: AudioSource): AudioTrack
    fun createPeerConnection(factory: PeerConnectionFactory, iceServers: List<PeerConnection.IceServer>, observer: PeerConnectionObserver): PeerConnection
    fun setQuality(sender: RtpSender, quality: String, baselineBitrate: Long, maxBitrate: Long?)
    fun getStats(connection: PeerConnection, callback: (String) -> Unit)
}
```

Screen capture:
```kotlin
// RTC thread only
val mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
val surfaceTextureHelper = SurfaceTextureHelper.create("capture", eglContext)
val capturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() { ... })
val videoSource = factory.createVideoSource(false)
capturer.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
capturer.startCapture(width, height, fps)
```

#### 3d. Service Lifecycle (bind from Activity)

```kotlin
class ScreenCaptureService : Service() {
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // Start streaming: called from Activity after MediaProjection result
    fun startStreaming(config: StreamConfig, resultCode: Int, data: Intent) {
        rtcHandler.post {
            transitionTo(BroadcasterState.ConnectingSignal)
            // 1. Init WebRTC
            // 2. Start capture
            // 3. Connect signaling
            // 4. Join room → WaitingViewer
        }
    }

    fun stopStreaming() {
        rtcHandler.post {
            capturer?.stopCapture()
            peerConnection?.close()
            signalingClient?.close()
            transitionTo(BroadcasterState.Closed)
        }
    }

    override fun onDestroy() {
        stopStreaming()
        rtcThread.quitSafely()
        super.onDestroy()
    }
}
```

- [ ] **Step 1: Write `WebRTCManager`** — factory init, track creation, peer connection, quality control, stats
- [ ] **Step 2: Write `ScreenCaptureService` skeleton** — HandlerThread, StateFlow, log, transitionTo, notification channel, startForeground
- [ ] **Step 3: Implement MediaProjection capture flow** inside the service on RTC thread
- [ ] **Step 4: Wire signaling** — onSignalingEvent → rtcHandler.post → state transitions
- [ ] **Step 5: Peer connection lifecycle** — create offer, set local, send, receive answer, set remote, ICE trickle
- [ ] **Step 6: Commit**

### Task 4: UI Shell (MainActivity + Compose)

**Files:**
- Modify: `app/src/main/java/io/github/iharee/webrtcp2pliveandroid/MainActivity.kt`

The Activity is now minimal. It binds to the service, observes state, and renders. Zero media logic.

```kotlin
class MainActivity : ComponentActivity() {

    private var service: ScreenCaptureService? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as ScreenCaptureService.LocalBinder).getService()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bindService(Intent(this, ScreenCaptureService::class.java), connection, Context.BIND_AUTO_CREATE)

        setContent {
            val serviceState = service?.state?.collectAsState() ?: return@setContent
            val logLines = service?.logLines?.collectAsState() ?: return@setContent

            BroadcasterApp(
                state = serviceState.value,
                logLines = logLines.value,
                onStart = { config -> startStreaming(config) },
                onStop = { service?.stopStreaming() }
            )
        }
    }

    private fun startStreaming(config: StreamConfig) {
        // 1. Create notification channel
        // 2. Request POST_NOTIFICATIONS (API 33+)
        // 3. Start foreground service
        // 4. Launch MediaProjection intent
        // 5. onActivityResult: service.startStreaming(config, resultCode, data)
    }

    override fun onDestroy() {
        unbindService(connection)
        super.onDestroy()
    }
}
```

#### Compose UI

Two zones:

**Config zone (top):**
```
┌──────────────────────────────┐
│  Server: [182.92.168.150    ]│
│  Room:   [myroom           ] │
│  Token:  [a8f2c...] [Copy]   │
│          [Start Streaming]   │
└──────────────────────────────┘
```

**Status zone (bottom, scrollable, driven by service.logLines):**
```
┌──────────────────────────────┐
│ ── Status ──                 │
│ 14:30:01 State → Connecting  │
│ 14:30:02 Signaling connected │
│ 14:30:03 Joined room         │
│ 14:30:05 Viewer joined       │
│ 14:30:05 State → Negotiating │
│ 14:30:06 Offer created       │
│ 14:30:07 State → Connected   │
│ 14:30:15 Stats: 30fps 2.1Mbps│
└──────────────────────────────┘
```

`LazyColumn` driven by `service.logLines.collectAsState()`. State lives in the service — screen rotation or Activity recreation never loses log history.

- [ ] **Step 1: Service binding + state observation in MainActivity**
- [ ] **Step 2: Config zone Compose** — server, room, token fields + start button
- [ ] **Step 3: Status log LazyColumn** — driven by service's `logLines: StateFlow<List<String>>`
- [ ] **Step 4: MediaProjection permission flow** — POST_NOTIFICATIONS request → foreground service start → MediaProjection intent → onActivityResult → service.startStreaming()
- [ ] **Step 5: Commit**

### Task 5: Polish & Release

- [ ] **Step 1: ProGuard verification** — confirm `-keep class org.webrtc.**` and Gson data classes are in rules
- [ ] **Step 2: Release build config** — signing, minify enabled
- [ ] **Step 3: Smoke test** — stream to a real web viewer, verify audio + video + quality-change
- [ ] **Step 4: Commit**

---

## V1 Acceptances (deliberate scoping)

| Limitation | Rationale | V2 Plan |
|------------|-----------|---------|
| **Mic-only audio** (no system audio) | `JavaAudioDeviceModule` captures microphone. System audio capture requires API 29+ `AudioPlaybackCaptureConfiguration` + custom JNI PCM injection | V2: explore system audio for gaming/movie streaming |
| **No automatic reconnection** | Full WebSocket reconnect + ICE restart with Glare handling is complex. Manual re-trigger via button is acceptable for V1 | V2: leverage existing `StateMachine` to add reconnect transitions |
| **App-foreground-only streaming** | Service holds state but we don't optimize for prolonged background operation | V2: sustained background streaming if needed |
| **No SurfaceViewRenderer preview** | Broadcaster doesn't need to see its own stream. Viewer is in web browser | Later if local preview is desired |

These are not gaps — they are explicit scoping decisions. The architecture (Service ownership, HandlerThread, StateMachine) was designed so that each can be added without a rewrite.

---

## Non-Changes

- Signaling server (`server.js`) — zero changes
- Web viewer (`viewer.html` / `viewer.js`) — zero changes
- Web broadcaster — stays as-is for desktop use
- Token protocol, quality-change signaling, ICE relay — all identical

## Signaling Protocol (reference)

Extracted from `server.js`:

| Direction | Message | Key Fields |
|-----------|---------|------------|
| App → Server | `join` | `role: "broadcaster"`, `roomId`, `token?` |
| Server → App | `joined` | `token` |
| Server → App | `viewer-joined` | — |
| Server → App | `peer-left` | — |
| Server → App | `rejected` | `reason` |
| App → Server | `offer` | `sdp` |
| Server → App | `answer` | `sdp` |
| App → Server | `ice-candidate` | `candidate` |
| Server → App | `ice-candidate` | `candidate` |
| Server → App | `quality-change` | `quality`, `maxBitrate?` |

## File Tree

```
webrtc-p2p-live-android/
├── plan.md
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── local.properties              (git-ignored, auto-generated)
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/io/github/iharee/webrtcp2pliveandroid/
│           ├── MainActivity.kt              ← UI shell only
│           ├── state/
│           │   └── BroadcasterState.kt      ← sealed state machine
│           ├── signaling/
│           │   ├── SignalingClient.kt       ← OkHttp WS + defensive JSON
│           │   └── SignalingMessage.kt
│           ├── webrtc/
│           │   └── WebRTCManager.kt         ← factory, peer, capture, stats
│           └── capture/
│               └── ScreenCaptureService.kt  ← owns everything, RTC thread
```

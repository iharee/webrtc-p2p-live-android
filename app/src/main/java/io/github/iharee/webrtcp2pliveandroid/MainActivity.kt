package io.github.iharee.webrtcp2pliveandroid

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.iharee.webrtcp2pliveandroid.capture.ScreenCaptureService
import io.github.iharee.webrtcp2pliveandroid.state.BroadcasterState
import io.github.iharee.webrtcp2pliveandroid.ui.theme.Webrtcp2pliveandroidTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private var serviceConnection: ServiceConnection? = null
    private val serviceFlow = MutableStateFlow<ScreenCaptureService?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createNotificationChannel()
        bindToService()

        setContent {
            Webrtcp2pliveandroidTheme {
                val service by serviceFlow.collectAsState()

                val state: BroadcasterState by (service?.state
                    ?: MutableStateFlow(BroadcasterState.Idle)).collectAsState()

                val logLines: List<String> by (service?.logLines
                    ?: MutableStateFlow(emptyList())).collectAsState()

                val context = LocalContext.current
                val mediaProjectionManager = remember {
                    context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                }

                // Configuration form state
                var serverUrl by remember { mutableStateOf("ws://172.16.40.148:8080") }
                var roomId by remember { mutableStateOf("testroom") }
                var token by remember { mutableStateOf(generateRandomToken()) }
                val listState = rememberLazyListState()

                // Pending config held across permission/MediaProjection flow
                var pendingConfig by remember {
                    mutableStateOf<ScreenCaptureService.StreamConfig?>(null)
                }

                // Auto-scroll log to bottom when new lines appear
                LaunchedEffect(logLines.size) {
                    if (logLines.isNotEmpty()) {
                        listState.animateScrollToItem(logLines.size - 1)
                    }
                }

                // ---- Permission / MediaProjection launchers ----
                // Order matters: each launcher can only reference launchers defined ABOVE it.

                val mediaProjectionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    val config = pendingConfig ?: return@rememberLauncherForActivityResult
                    pendingConfig = null
                    if (result.resultCode == RESULT_OK && result.data != null) {
                        service?.startStreaming(config, result.resultCode, result.data!!)
                    }
                }

                val audioPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { _ ->
                    pendingConfig?.let {
                        mediaProjectionLauncher.launch(
                            mediaProjectionManager.createScreenCaptureIntent()
                        )
                    }
                }

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { _ ->
                    if (ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        pendingConfig?.let {
                            mediaProjectionLauncher.launch(
                                mediaProjectionManager.createScreenCaptureIntent()
                            )
                        }
                    }
                }

                // ---- Start streaming helper ----

                fun startStreamingFlow() {
                    val config = ScreenCaptureService.StreamConfig(
                        serverUrl = serverUrl,
                        roomId = roomId,
                        token = token.ifBlank { null }
                    )
                    pendingConfig = config

                    val serviceIntent = Intent(context, ScreenCaptureService::class.java)
                    ContextCompat.startForegroundService(context, serviceIntent)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(
                                context, Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(
                                Manifest.permission.POST_NOTIFICATIONS
                            )
                            return
                        }
                    }

                    if (ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        mediaProjectionLauncher.launch(
                            mediaProjectionManager.createScreenCaptureIntent()
                        )
                    }
                }

                // ---- UI ----

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    if (service == null) {
                        // Service not yet bound
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Connecting to service...")
                        }
                    } else {
                        ConfigZone(
                            state = state,
                            serverUrl = serverUrl,
                            onServerUrlChange = { serverUrl = it },
                            roomId = roomId,
                            onRoomIdChange = { roomId = it },
                            token = token,
                            onTokenChange = { token = it },
                            onStartStreaming = { startStreamingFlow() }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        StatusZone(
                            state = state,
                            logLines = logLines,
                            listState = listState,
                            onStopStreaming = { service?.stopStreaming() }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        unbindFromService()
        super.onDestroy()
    }

    // --- Service binding ---

    private fun bindToService() {
        val intent = Intent(this, ScreenCaptureService::class.java)
        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val localBinder = binder as? ScreenCaptureService.LocalBinder
                serviceFlow.value = localBinder?.getService()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceFlow.value = null
            }
        }
        bindService(intent, serviceConnection!!, Context.BIND_AUTO_CREATE)
    }

    private fun unbindFromService() {
        serviceConnection?.let {
            unbindService(it)
            serviceConnection = null
        }
        serviceFlow.value = null
    }

    // --- Notification channel ---

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "streaming",
            "Streaming",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "WebRTC streaming foreground service"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    // --- Helpers ---

    private fun generateRandomToken(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..12).map { chars.random() }.joinToString("")
    }
}

// --- UI Composables ---

@Composable
private fun ConfigZone(
    state: BroadcasterState,
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    roomId: String,
    onRoomIdChange: (String) -> Unit,
    token: String,
    onTokenChange: (String) -> Unit,
    onStartStreaming: () -> Unit
) {
    val canConfigure = state is BroadcasterState.Idle || state is BroadcasterState.Failed
    val isLive = state is BroadcasterState.Connected

    Column {
        Text(
            text = "Configuration",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = onServerUrlChange,
            label = { Text("Server URL") },
            enabled = canConfigure || isLive,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = roomId,
            onValueChange = onRoomIdChange,
            label = { Text("Room ID") },
            enabled = canConfigure,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = token,
                onValueChange = onTokenChange,
                label = { Text("Token") },
                enabled = canConfigure,
                singleLine = true,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            val context = LocalContext.current
            Button(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Token", token))
            }) {
                Text("Copy Token")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (isLive) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "LIVE",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = Color(0xFF00AA00),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onStartStreaming,
            enabled = canConfigure && serverUrl.isNotBlank() && roomId.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start Streaming")
        }
    }
}

@Composable
private fun StatusZone(
    state: BroadcasterState,
    logLines: List<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onStopStreaming: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Status",
                style = MaterialTheme.typography.titleLarge
            )

            if (state is BroadcasterState.Connected) {
                Button(
                    onClick = onStopStreaming,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Stop Streaming")
                }
            }

            if (state is BroadcasterState.Failed) {
                Button(onClick = onStopStreaming) {
                    Text("Retry")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val statusText = when (state) {
            is BroadcasterState.Idle -> "Idle — ready to start."
            is BroadcasterState.Configuring -> "Configuring ${state.serverUrl}/${state.roomId}..."
            is BroadcasterState.ConnectingSignal -> "Connecting to signaling server..."
            is BroadcasterState.WaitingViewer -> "Waiting for a viewer to join..."
            is BroadcasterState.Negotiating -> "Negotiating: ${state.step}"
            is BroadcasterState.Connected -> "Streaming LIVE (${state.quality}, max bitrate: ${state.maxBitrate})"
            is BroadcasterState.Failed -> "Error: ${state.reason}"
            is BroadcasterState.Closed -> "Stream stopped."
        }

        when (state) {
            is BroadcasterState.Failed -> Text(
                text = statusText,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
            is BroadcasterState.Connected -> Text(
                text = statusText,
                color = Color(0xFF00AA00),
                style = MaterialTheme.typography.bodyMedium
            )
            else -> Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                if (logLines.isEmpty()) {
                    item {
                        Text(
                            text = "No log entries yet.",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(logLines) { line ->
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}

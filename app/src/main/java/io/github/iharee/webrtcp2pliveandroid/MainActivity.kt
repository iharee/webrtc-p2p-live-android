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
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.clickable
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
                val s = state // local snapshot so Kotlin can smart-cast in when branches

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
                var turnServer by remember { mutableStateOf("") }
                var turnUser by remember { mutableStateOf("") }
                var turnPass by remember { mutableStateOf("") }
                var showTurnConfig by remember { mutableStateOf(false) }
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
                        token = token.ifBlank { null },
                        turnServer = turnServer.ifBlank { null },
                        turnUser = turnUser.ifBlank { null },
                        turnPass = turnPass.ifBlank { null }
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

                val canConfigure = state is BroadcasterState.Idle || state is BroadcasterState.Failed || state is BroadcasterState.Closed
                val isLive = state is BroadcasterState.Connected
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

                if (service == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Connecting to service...")
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        // ── Configuration ──
                        Text("Configuration", style = MaterialTheme.typography.titleLarge)

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            label = { Text("Server URL") },
                            enabled = canConfigure || isLive,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = roomId,
                            onValueChange = { roomId = it },
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
                                onValueChange = { token = it },
                                label = { Text("Token") },
                                enabled = canConfigure,
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = {
                                clipboard.setPrimaryClip(ClipData.newPlainText("Token", token))
                            }) {
                                Text("Copy")
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        TextButton(onClick = { showTurnConfig = !showTurnConfig }) {
                            Text(if (showTurnConfig) "▼ TURN Config" else "▶ TURN Config")
                            Spacer(modifier = Modifier.width(4.dp))
                            if (!turnServer.isBlank()) {
                                Text(
                                    text = "(configured)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF00AA00)
                                )
                            }
                        }

                        if (showTurnConfig) {
                            OutlinedTextField(
                                value = turnServer,
                                onValueChange = { turnServer = it },
                                label = { Text("TURN Server (e.g. 203.0.113.1)") },
                                enabled = canConfigure,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = turnUser,
                                    onValueChange = { turnUser = it },
                                    label = { Text("TURN Username", fontSize = 13.sp) },
                                    enabled = canConfigure,
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                OutlinedTextField(
                                    value = turnPass,
                                    onValueChange = { turnPass = it },
                                    label = { Text("TURN Password", fontSize = 13.sp) },
                                    enabled = canConfigure,
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                        }

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

                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        Button(
                            onClick = { startStreamingFlow() },
                            enabled = canConfigure && serverUrl.isNotBlank() && roomId.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Start Streaming")
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // ── Status header ──
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Status", style = MaterialTheme.typography.titleLarge)
                            Row {
                                if (logLines.isNotEmpty()) {
                                    TextButton(onClick = {
                                        clipboard.setPrimaryClip(
                                            ClipData.newPlainText("Logs", logLines.joinToString("\n"))
                                        )
                                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                    }) { Text("Copy log") }
                                }
                                if (isLive) {
                                    Button(
                                        onClick = { service?.stopStreaming() },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error
                                        )
                                    ) { Text("Stop") }
                                }
                                if (state is BroadcasterState.Failed) {
                                    Button(onClick = { service?.stopStreaming() }) { Text("Retry") }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        val statusText = when (s) {
                            is BroadcasterState.Idle -> "Idle — ready to start."
                            is BroadcasterState.Configuring -> "Configuring ${s.serverUrl}/${s.roomId}..."
                            is BroadcasterState.ConnectingSignal -> "Connecting to signaling server..."
                            is BroadcasterState.WaitingViewer -> "Waiting for a viewer to join..."
                            is BroadcasterState.Negotiating -> "Negotiating: ${s.step}"
                            is BroadcasterState.Connected -> "Streaming LIVE (${s.quality}, max bitrate: ${s.maxBitrate})"
                            is BroadcasterState.Failed -> "Error: ${s.reason}"
                            is BroadcasterState.Closed -> "Stream stopped."
                        }
                        val statusColor = when (s) {
                            is BroadcasterState.Failed -> MaterialTheme.colorScheme.error
                            is BroadcasterState.Connected -> Color(0xFF00AA00)
                            else -> Color.Unspecified
                        }
                        Text(
                            text = statusText,
                            color = statusColor,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // ── Log LazyColumn (fills remaining space, true lazy loading) ──
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            state = listState
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
        }
    }

    override fun onDestroy() {
        unbindFromService()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val orientation = newConfig.orientation
        if (orientation == Configuration.ORIENTATION_PORTRAIT ||
            orientation == Configuration.ORIENTATION_LANDSCAPE
        ) {
            serviceFlow.value?.onRotation()
        }
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

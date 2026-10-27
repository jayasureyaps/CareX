package com.carex.poc

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager as AndroidBtManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─── Custom dark color scheme for a premium look ────────────────────────────

private val CareXDark = darkColorScheme(
    primary = Color(0xFF6CDBEB),         // Bright cyan
    onPrimary = Color(0xFF003640),
    primaryContainer = Color(0xFF004D5A),
    secondary = Color(0xFFB0CACD),
    surface = Color(0xFF101820),          // Very dark blue-grey
    surfaceVariant = Color(0xFF1A2530),
    onSurface = Color(0xFFE1E3E5),
    onSurfaceVariant = Color(0xFF8E9EA3),
    background = Color(0xFF0B1218),
    error = Color(0xFFFF6B6B),
    outline = Color(0xFF2A3A44)
)

// Accent colours used for sensor cards and alerts
private val AccelColor = Color(0xFF4FC3F7)
private val GyroColor = Color(0xFFCE93D8)
private val FallRedBg = Color(0xFFD32F2F)
private val SuccessGreen = Color(0xFF66BB6A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = CareXDark) {
                CareXApp()
            }
        }
    }
}

// ─── Root composable with screen navigation & Bottom Bar ────────────────────

private enum class Screen { SPLASH, CONNECT, LIVE, GAIT, MAP, SETTINGS }

@Composable
fun CareXApp(viewModel: SensorViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var currentScreen by remember { mutableStateOf(Screen.SPLASH) }

    var permissionsGranted by remember {
        mutableStateOf(hasBluetoothPermissions(context) && hasLocationPermissions(context))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        if (permissionsGranted) {
            viewModel.startLocationUpdates(context)
        } else {
            Toast.makeText(context, "Permissions required for Bluetooth & GPS Map", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.startLocationUpdates(context)
    }

    // Auto navigate when Bluetooth connects/disconnects
    if (state.connectionStatus == SensorViewModel.ConnectionStatus.CONNECTED && currentScreen == Screen.CONNECT) {
        currentScreen = Screen.LIVE
    }
    if (state.connectionStatus == SensorViewModel.ConnectionStatus.DISCONNECTED && currentScreen == Screen.LIVE) {
        currentScreen = Screen.CONNECT
    }

    if (currentScreen == Screen.SPLASH) {
        SplashScreen(
            onSplashFinished = { currentScreen = Screen.CONNECT }
        )
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar(
                    containerColor = CareXDark.surface,
                    contentColor = CareXDark.onSurface
                ) {
                    NavigationBarItem(
                        selected = (currentScreen == Screen.CONNECT || currentScreen == Screen.LIVE),
                        onClick = {
                            currentScreen = if (state.connectionStatus == SensorViewModel.ConnectionStatus.CONNECTED) Screen.LIVE else Screen.CONNECT
                        },
                        icon = {
                            Icon(
                                if (state.connectionStatus == SensorViewModel.ConnectionStatus.CONNECTED) Icons.Default.BluetoothConnected else Icons.Default.Sensors,
                                contentDescription = "Sensors"
                            )
                        },
                        label = { Text("Sensors") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CareXDark.primary,
                            selectedTextColor = CareXDark.primary,
                            indicatorColor = CareXDark.primaryContainer
                        )
                    )

                    NavigationBarItem(
                        selected = (currentScreen == Screen.GAIT),
                        onClick = { currentScreen = Screen.GAIT },
                        icon = { Icon(Icons.Default.DirectionsWalk, contentDescription = "Gait Analysis") },
                        label = { Text("Gait Analysis") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CareXDark.primary,
                            selectedTextColor = CareXDark.primary,
                            indicatorColor = CareXDark.primaryContainer
                        )
                    )

                    NavigationBarItem(
                        selected = (currentScreen == Screen.MAP),
                        onClick = { currentScreen = Screen.MAP },
                        icon = { Icon(Icons.Default.Map, contentDescription = "Live Map") },
                        label = { Text("Live Map") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CareXDark.primary,
                            selectedTextColor = CareXDark.primary,
                            indicatorColor = CareXDark.primaryContainer
                        )
                    )

                    NavigationBarItem(
                        selected = (currentScreen == Screen.SETTINGS),
                        onClick = { currentScreen = Screen.SETTINGS },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Server") },
                        label = { Text("Server") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CareXDark.primary,
                            selectedTextColor = CareXDark.primary,
                            indicatorColor = CareXDark.primaryContainer
                        )
                    )
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (currentScreen) {
                    Screen.SPLASH -> {}
                    Screen.CONNECT -> ConnectScreen(
                        state = state,
                        onDeviceSelected = { viewModel.connectToDevice(it) },
                        onOpenSettings = { currentScreen = Screen.SETTINGS },
                        onRequestPermissions = { permissionLauncher.launch(allRequiredPermissions()) }
                    )
                    Screen.LIVE -> LiveDataScreen(
                        state = state,
                        onDisconnect = {
                            viewModel.disconnect()
                            currentScreen = Screen.CONNECT
                        },
                        onOpenSettings = { currentScreen = Screen.SETTINGS },
                        onNavigateToMap = { currentScreen = Screen.MAP },
                        onDismissFallAlert = { viewModel.dismissFallAlert() }
                    )
                    Screen.GAIT -> GaitAnalysisScreen(
                        state = state,
                        onBack = { currentScreen = if (state.connectionStatus == SensorViewModel.ConnectionStatus.CONNECTED) Screen.LIVE else Screen.CONNECT }
                    )
                    Screen.MAP -> MapScreen(
                        state = state,
                        onRequestLocationPermissions = { permissionLauncher.launch(locationPermissions()) },
                        onSendAlertLocation = { viewModel.testServerConnection() }
                    )
                    Screen.SETTINGS -> ServerSettingsScreen(
                        state = state,
                        onUpdateUrl = { viewModel.updateServerUrl(it) },
                        onUpdateEnabled = { viewModel.updateServerEnabled(it) },
                        onUpdateFallOnly = { viewModel.updateSendOnlyFallAlerts(it) },
                        onUpdatePeriodicEnabled = { viewModel.updatePeriodicEnabled(it) },
                        onUpdatePeriodicInterval = { viewModel.updatePeriodicInterval(it) },
                        onTriggerManualSync = { viewModel.triggerManualPeriodicSync() },
                        onTestConnection = { viewModel.testServerConnection() },
                        onBack = {
                            currentScreen = if (state.connectionStatus == SensorViewModel.ConnectionStatus.CONNECTED) Screen.LIVE else Screen.CONNECT
                        }
                    )
                }
            }
        }
    }
}

// ─── Splash / Intro Loading Screen ───────────────────────────────────────────

@Composable
fun SplashScreen(onSplashFinished: () -> Unit) {
    // Show splash screen for 4.0 seconds upon app launch
    LaunchedEffect(Unit) {
        delay(4000L)
        onSplashFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CareXDark.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.splash_logo),
                contentDescription = "CareX Intro Logo",
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .clip(RoundedCornerShape(28.dp))
                    .border(2.dp, CareXDark.primary.copy(alpha = 0.4f), RoundedCornerShape(28.dp)),
                contentScale = ContentScale.FillWidth
            )

            Spacer(Modifier.height(32.dp))

            Text(
                "CareX POC",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = CareXDark.primary,
                letterSpacing = 1.sp
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "Real-Time Fall Detection & Activity Recognition",
                style = MaterialTheme.typography.bodyMedium,
                color = CareXDark.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(36.dp))

            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                strokeWidth = 3.dp,
                color = CareXDark.primary
            )
        }
    }
}

// ─── Connect Screen ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun ConnectScreen(
    state: SensorViewModel.UiState,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onOpenSettings: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    val context = LocalContext.current
    val permissionsGranted = remember(context) {
        hasBluetoothPermissions(context) && hasLocationPermissions(context)
    }

    val btAdapter = remember {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? AndroidBtManager
        mgr?.adapter
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Sensors,
                            contentDescription = null,
                            tint = CareXDark.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("CareX POC", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Server Settings",
                            tint = CareXDark.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CareXDark.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CareXDark.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "Connect to ESP32",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = CareXDark.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Select your CareX-ESP32 device from the paired devices list below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CareXDark.onSurfaceVariant
                    )

                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (state.modelReady) SuccessGreen else CareXDark.error)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (state.modelReady) "ML Model ready for inference" else "ML Model missing",
                            style = MaterialTheme.typography.bodySmall,
                            color = CareXDark.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            state.errorMessage?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2D1516)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        error,
                        modifier = Modifier.padding(16.dp),
                        color = CareXDark.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            if (!permissionsGranted) {
                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = CareXDark.primary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Bluetooth, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Grant Bluetooth & Location Permissions", color = CareXDark.onPrimary)
                }
                Spacer(Modifier.height(16.dp))
            }

            if (state.connectionStatus == SensorViewModel.ConnectionStatus.CONNECTING) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = CareXDark.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Connecting to ${state.deviceName}...",
                        color = CareXDark.primary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            Text(
                "PAIRED BLUETOOTH DEVICES",
                style = MaterialTheme.typography.labelMedium,
                color = CareXDark.onSurfaceVariant,
                letterSpacing = 1.5.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (btAdapter == null || !btAdapter.isEnabled) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CareXDark.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        "Bluetooth is disabled. Please turn on Bluetooth in settings.",
                        modifier = Modifier.padding(20.dp),
                        color = CareXDark.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                val pairedDevices = if (hasBluetoothPermissions(context)) {
                    btAdapter.bondedDevices?.toList() ?: emptyList()
                } else {
                    emptyList()
                }

                if (pairedDevices.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CareXDark.surfaceVariant),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            if (permissionsGranted)
                                "No paired devices found.\nPair your CareX ESP32 device in system Bluetooth settings."
                            else
                                "Grant permissions above to scan paired devices.",
                            modifier = Modifier.padding(20.dp),
                            color = CareXDark.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        pairedDevices.forEach { device ->
                            DeviceCard(
                                device = device,
                                isConnecting = state.connectionStatus == SensorViewModel.ConnectionStatus.CONNECTING,
                                onClick = { onDeviceSelected(device) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun DeviceCard(
    device: BluetoothDevice,
    isConnecting: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isConnecting, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = CareXDark.surfaceVariant),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(CareXDark.primary.copy(alpha = 0.3f), CareXDark.primaryContainer)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = CareXDark.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.name ?: "Unknown Device",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = CareXDark.onSurface
                )
                Text(
                    device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = CareXDark.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// ─── Live Data Screen ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveDataScreen(
    state: SensorViewModel.UiState,
    onDisconnect: () -> Unit,
    onOpenSettings: () -> Unit,
    onNavigateToMap: () -> Unit,
    onDismissFallAlert: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.BluetoothConnected,
                            contentDescription = null,
                            tint = SuccessGreen,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(state.deviceName, fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDisconnect) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Disconnect",
                            tint = CareXDark.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Server Settings",
                            tint = CareXDark.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CareXDark.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            AnimatedVisibility(
                visible = state.isFallAlert,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut()
            ) {
                FallAlertBanner(onDismiss = onDismissFallAlert)
            }

            Spacer(Modifier.height(12.dp))

            PredictionCard(
                activity = state.predictedActivity,
                confidence = state.confidence,
                isFall = state.isFallAlert
            )

            Spacer(Modifier.height(12.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToMap() },
                colors = CardDefaults.cardColors(containerColor = CareXDark.surfaceVariant),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Map,
                            contentDescription = null,
                            tint = CareXDark.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "Live Map & GPS",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = CareXDark.onSurface
                            )
                            Text(
                                if (state.locationData.hasFix)
                                    String.format("%.4f°, %.4f°", state.locationData.latitude, state.locationData.longitude)
                                else "Searching GPS location...",
                                style = MaterialTheme.typography.bodySmall,
                                color = CareXDark.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        "Open Map ›",
                        style = MaterialTheme.typography.labelMedium,
                        color = CareXDark.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Window: ${state.windowProgress} / ${FeatureExtractor.WINDOW_SIZE}",
                    style = MaterialTheme.typography.bodySmall,
                    color = CareXDark.onSurfaceVariant
                )
                Text(
                    "Total Samples: ${state.totalSamples}",
                    style = MaterialTheme.typography.bodySmall,
                    color = CareXDark.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { state.windowProgress.toFloat() / FeatureExtractor.WINDOW_SIZE },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = CareXDark.primary,
                trackColor = CareXDark.outline,
            )

            Spacer(Modifier.height(20.dp))

            Text(
                "ACCELEROMETER  (m/s²)",
                style = MaterialTheme.typography.labelMedium,
                color = AccelColor,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SensorValueCard("aX", state.liveValues[0], AccelColor, Modifier.weight(1f))
                SensorValueCard("aY", state.liveValues[1], AccelColor, Modifier.weight(1f))
                SensorValueCard("aZ", state.liveValues[2], AccelColor, Modifier.weight(1f))
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "GYROSCOPE  (rad/s)",
                style = MaterialTheme.typography.labelMedium,
                color = GyroColor,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SensorValueCard("gX", state.liveValues[3], GyroColor, Modifier.weight(1f))
                SensorValueCard("gY", state.liveValues[4], GyroColor, Modifier.weight(1f))
                SensorValueCard("gZ", state.liveValues[5], GyroColor, Modifier.weight(1f))
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onDisconnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CareXDark.error.copy(alpha = 0.15f),
                    contentColor = CareXDark.error
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Disconnect", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun PredictionCard(activity: String, confidence: Float, isFall: Boolean) {
    val bgColor by animateColorAsState(
        targetValue = if (isFall) FallRedBg.copy(alpha = 0.2f) else CareXDark.surfaceVariant,
        animationSpec = tween(400),
        label = "predBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isFall) FallRedBg else CareXDark.outline,
        animationSpec = tween(400),
        label = "predBorder"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(18.dp)),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "PREDICTED ACTIVITY",
                style = MaterialTheme.typography.labelSmall,
                color = CareXDark.onSurfaceVariant,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                activity,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = if (isFall) CareXDark.error else CareXDark.primary
            )
            Spacer(Modifier.height(8.dp))

            if (confidence > 0f) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(0.7f)
                ) {
                    LinearProgressIndicator(
                        progress = { confidence / 100f },
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (isFall) CareXDark.error else SuccessGreen,
                        trackColor = CareXDark.outline,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${confidence.toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = CareXDark.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun FallAlertBanner(onDismiss: () -> Unit = {}) {
    val infiniteTransition = rememberInfiniteTransition(label = "fallPulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "fallAlpha"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha),
        colors = CardDefaults.cardColors(containerColor = FallRedBg),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = "Fall alert",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "⚠ FALL DETECTED (>45%)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        "Emergency siren sounding!",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
            }

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = FallRedBg
                ),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    "Silence",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun SensorValueCard(
    label: String,
    value: Float,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = CareXDark.surfaceVariant),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
            Spacer(Modifier.height(6.dp))
            Text(
                String.format("%.3f", value),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                color = CareXDark.onSurface
            )
        }
    }
}

// ─── DEDICATED REAL INTERACTIVE MAP PAGE ────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    state: SensorViewModel.UiState,
    onRequestLocationPermissions: () -> Unit,
    onSendAlertLocation: () -> Unit
) {
    val context = LocalContext.current
    val hasLocPerm = remember(context) { hasLocationPermissions(context) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isMapLoaded by remember { mutableStateOf(false) }

    val loc = state.locationData

    LaunchedEffect(loc, isMapLoaded) {
        if (isMapLoaded && loc.hasFix) {
            val js = "javascript:if(typeof updateLocation==='function'){updateLocation(${loc.latitude}, ${loc.longitude}, true, '${loc.provider}', ${loc.accuracy});}"
            webViewInstance?.evaluateJavascript(js, null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Map,
                            contentDescription = null,
                            tint = CareXDark.primary,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Live GPS & OpenStreetMap", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(
                                if (loc.hasFix) "Fix Active (${loc.provider}) • ±${loc.accuracy.toInt()}m"
                                else "Searching GPS Fix...",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (loc.hasFix) SuccessGreen else CareXDark.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CareXDark.surface)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!hasLocPerm) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = CareXDark.primary,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Location Access Required",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = CareXDark.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Grant fine location permission so CareX can display your real position on the interactive map and send GPS coordinates during alerts.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CareXDark.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = onRequestLocationPermissions,
                        colors = ButtonDefaults.buttonColors(containerColor = CareXDark.primary)
                    ) {
                        Text("Grant Location Access", color = CareXDark.onPrimary)
                    }
                }
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = true
                            settings.allowContentAccess = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true

                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isMapLoaded = true
                                    if (loc.hasFix) {
                                        val js = "javascript:if(typeof updateLocation==='function'){updateLocation(${loc.latitude}, ${loc.longitude}, true, '${loc.provider}', ${loc.accuracy});}"
                                        evaluateJavascript(js, null)
                                    }
                                }
                            }

                            loadUrl("file:///android_asset/map.html")
                            webViewInstance = this
                        }
                    },
                    update = { view ->
                        webViewInstance = view
                        if (isMapLoaded && loc.hasFix) {
                            val js = "javascript:if(typeof updateLocation==='function'){updateLocation(${loc.latitude}, ${loc.longitude}, true, '${loc.provider}', ${loc.accuracy});}"
                            view.evaluateJavascript(js, null)
                        }
                    }
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CareXDark.surfaceVariant.copy(alpha = 0.92f)),
                        shape = RoundedCornerShape(18.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CareXDark.outline)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        "LIVE GPS COORDINATES",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CareXDark.onSurfaceVariant,
                                        letterSpacing = 1.sp
                                    )
                                    Text(
                                        if (loc.hasFix) String.format("%.6f°, %.6f°", loc.latitude, loc.longitude)
                                        else "Acquiring GPS fix...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = CareXDark.onSurface
                                    )
                                }

                                if (loc.hasFix) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(SuccessGreen.copy(alpha = 0.2f))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            "±${loc.accuracy.toInt()}m",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = SuccessGreen
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (loc.hasFix) {
                                            val js = "javascript:if(typeof updateLocation==='function'){updateLocation(${loc.latitude}, ${loc.longitude}, true, '${loc.provider}', ${loc.accuracy});}"
                                            webViewInstance?.evaluateJavascript(js, null)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = CareXDark.primaryContainer),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp), tint = CareXDark.primary)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Recenter", color = CareXDark.primary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                }

                                Button(
                                    onClick = {
                                        if (loc.hasFix) {
                                            val geoUri = Uri.parse("geo:${loc.latitude},${loc.longitude}?q=${loc.latitude},${loc.longitude}(CareX+Device)")
                                            val intent = Intent(Intent.ACTION_VIEW, geoUri)
                                            try { context.startActivity(intent) } catch (_: Exception) {}
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = CareXDark.surface),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(16.dp), tint = CareXDark.onSurface)
                                    Spacer(Modifier.width(6.dp))
                                    Text("External", color = CareXDark.onSurface, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                }

                                Button(
                                    onClick = onSendAlertLocation,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = FallRedBg.copy(alpha = 0.3f)),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp), tint = CareXDark.error)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Send Alert", color = CareXDark.error, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Permission Helpers ─────────────────────────────────────────────────────

private fun locationPermissions(): Array<String> {
    return arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
}

private fun hasLocationPermissions(context: Context): Boolean {
    return locationPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

private fun bluetoothPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

private fun hasBluetoothPermissions(context: Context): Boolean {
    return bluetoothPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

private fun allRequiredPermissions(): Array<String> {
    val list = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        list.add(Manifest.permission.BLUETOOTH_CONNECT)
        list.add(Manifest.permission.BLUETOOTH_SCAN)
    }
    return list.toTypedArray()
}

// ─── Server Settings Screen ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ServerSettingsScreen(
    state: SensorViewModel.UiState,
    onUpdateUrl: (String) -> Unit,
    onUpdateEnabled: (Boolean) -> Unit,
    onUpdateFallOnly: (Boolean) -> Unit,
    onUpdatePeriodicEnabled: (Boolean) -> Unit,
    onUpdatePeriodicInterval: (Int) -> Unit,
    onTriggerManualSync: () -> Unit,
    onTestConnection: () -> Unit,
    onBack: () -> Unit
) {
    var urlText by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
    var customMinsText by remember(state.periodicIntervalMins) { mutableStateOf(state.periodicIntervalMins.toString()) }

    val presetIntervals = listOf(
        1 to "1 min (Test)",
        5 to "5 mins",
        15 to "15 mins",
        30 to "30 mins",
        60 to "1 Hour ⭐",
        120 to "2 Hours",
        360 to "6 Hours",
        720 to "12 Hours",
        1440 to "24 Hours"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server & Sync Configuration", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = CareXDark.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CareXDark.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CareXDark.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(CareXDark.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.CloudUpload,
                            contentDescription = null,
                            tint = CareXDark.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            "Backend HTTP Logger",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = CareXDark.onSurface
                        )
                        Text(
                            "Transmit real-time predictions, fall alerts, & periodic telemetry over HTTP POST",
                            style = MaterialTheme.typography.bodySmall,
                            color = CareXDark.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "SERVER ENDPOINT URL",
                style = MaterialTheme.typography.labelMedium,
                color = CareXDark.onSurfaceVariant,
                letterSpacing = 1.2.sp
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = urlText,
                onValueChange = {
                    urlText = it
                    onUpdateUrl(it)
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("http://192.168.1.100:5000/api/predict") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CareXDark.primary,
                    unfocusedBorderColor = CareXDark.outline,
                    focusedContainerColor = CareXDark.surfaceVariant,
                    unfocusedContainerColor = CareXDark.surfaceVariant,
                    focusedTextColor = CareXDark.onSurface,
                    unfocusedTextColor = CareXDark.onSurface
                ),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onTestConnection,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isTestingServer && urlText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = CareXDark.primary),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (state.isTestingServer) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = CareXDark.onPrimary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Testing Connection...")
                } else {
                    Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Test Server Connection")
                }
            }

            state.lastServerStatus?.let { status ->
                Spacer(Modifier.height(10.dp))
                val isSuccess = status.contains("200") || status.contains("OK")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSuccess) Color(0xFF142B1A) else Color(0xFF2D1516)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (isSuccess) SuccessGreen else CareXDark.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSuccess) SuccessGreen else CareXDark.error
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ──── PERIODIC TELEMETRY TRANSMISSION CARD ────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CareXDark.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Periodic Telemetry Sync",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = CareXDark.onSurface
                            )
                            Text(
                                "Send sensor readings, state, and GPS location periodically (Default: 1 hour)",
                                style = MaterialTheme.typography.bodySmall,
                                color = CareXDark.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.isPeriodicEnabled,
                            onCheckedChange = onUpdatePeriodicEnabled,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CareXDark.primary,
                                checkedTrackColor = CareXDark.primaryContainer
                            )
                        )
                    }

                    if (state.isPeriodicEnabled) {
                        Spacer(Modifier.height(16.dp))

                        Text(
                            "SYNC INTERVAL SELECTION",
                            style = MaterialTheme.typography.labelSmall,
                            color = CareXDark.onSurfaceVariant,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(8.dp))

                        // Preset chips
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            presetIntervals.forEach { (mins, label) ->
                                val isSelected = state.periodicIntervalMins == mins
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        customMinsText = mins.toString()
                                        onUpdatePeriodicInterval(mins)
                                    },
                                    label = {
                                        Text(
                                            label,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = CareXDark.primaryContainer,
                                        selectedLabelColor = CareXDark.primary,
                                        containerColor = CareXDark.surface,
                                        labelColor = CareXDark.onSurface
                                    )
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Custom interval input field
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = customMinsText,
                                onValueChange = { input ->
                                    val filtered = input.filter { char -> char.isDigit() }
                                    customMinsText = filtered
                                    val mins = filtered.toIntOrNull()
                                    if (mins != null && mins > 0) {
                                        onUpdatePeriodicInterval(mins)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                label = { Text("Custom Interval (Minutes)") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CareXDark.primary,
                                    unfocusedBorderColor = CareXDark.outline,
                                    focusedContainerColor = CareXDark.surface,
                                    unfocusedContainerColor = CareXDark.surface
                                ),
                                shape = RoundedCornerShape(10.dp)
                            )
                        }

                        Spacer(Modifier.height(14.dp))

                        // Sync Status & Countdown
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = CareXDark.surface),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "NEXT SCHEDULED SYNC",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CareXDark.onSurfaceVariant
                                    )
                                    Text(
                                        formatCountdown(state.nextPeriodicSendInSecs),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = CareXDark.primary,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                Spacer(Modifier.height(4.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "LAST PERIODIC SYNC",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CareXDark.onSurfaceVariant
                                    )
                                    Text(
                                        formatTime(state.lastPeriodicSendTime),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = CareXDark.onSurface
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        Button(
                            onClick = onTriggerManualSync,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = CareXDark.primaryContainer),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = CareXDark.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Trigger Periodic Sync Now", color = CareXDark.primary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // General Switches
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CareXDark.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Enable HTTP Transmission",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = CareXDark.onSurface
                            )
                            Text(
                                "Master toggle for all HTTP POST transmissions",
                                style = MaterialTheme.typography.bodySmall,
                                color = CareXDark.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.isServerEnabled,
                            onCheckedChange = onUpdateEnabled,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CareXDark.primary,
                                checkedTrackColor = CareXDark.primaryContainer
                            )
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Send Fall Alerts Only",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = CareXDark.onSurface
                            )
                            Text(
                                "Skip routine prediction windows, send only on fall detection",
                                style = MaterialTheme.typography.bodySmall,
                                color = CareXDark.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.sendOnlyFallAlerts,
                            onCheckedChange = onUpdateFallOnly,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CareXDark.primary,
                                checkedTrackColor = CareXDark.primaryContainer
                            )
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = CareXDark.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "SAMPLE PERIODIC POST PAYLOAD",
                        style = MaterialTheme.typography.labelSmall,
                        color = CareXDark.onSurfaceVariant,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "{\n  \"event\": \"periodic_telemetry\",\n  \"timestamp\": 1726017324000,\n  \"device_name\": \"CareX-ESP32\",\n  \"predicted_activity\": \"Walking\",\n  \"confidence\": 98.4,\n  \"is_fall\": false,\n  \"location\": {\n    \"latitude\": 12.9716,\n    \"longitude\": 77.5946,\n    \"altitude\": 920.5,\n    \"accuracy\": 4.2,\n    \"speed\": 0.5,\n    \"has_fix\": true,\n    \"provider\": \"gps\"\n  },\n  \"sensor_data\": {\n    \"ax\": -0.12, \"ay\": 0.45, \"az\": 9.81,\n    \"gx\": 0.01, \"gy\": -0.02, \"gz\": 0.03\n  }\n}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = CareXDark.primary
                    )
                }
            }
        }
    }
}

private fun formatCountdown(seconds: Long): String {
    if (seconds <= 0) return "Syncing..."
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        hrs > 0 -> String.format("%dh %02dm %02ds", hrs, mins, secs)
        mins > 0 -> String.format("%dm %02ds", mins, secs)
        else -> String.format("%ds", secs)
    }
}

private fun formatTime(epochMs: Long): String {
    if (epochMs == 0L) return "Never"
    return SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(epochMs))
}

// ─── GAIT ANALYSIS SCREEN ─────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GaitAnalysisScreen(
    state: SensorViewModel.UiState,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gait Analysis", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = CareXDark.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CareXDark.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CareXDark.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "STEP COUNT",
                        style = MaterialTheme.typography.labelMedium,
                        color = CareXDark.onSurfaceVariant,
                        letterSpacing = 2.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${state.stepCount}",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = CareXDark.primary
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                GaitMetricCard(
                    title = "GAIT SPEED",
                    value = String.format("%.2f m/s", state.gaitSpeed),
                    icon = Icons.Default.DirectionsWalk,
                    modifier = Modifier.weight(1f)
                )
                GaitMetricCard(
                    title = "STRIDE",
                    value = String.format("%.2f m", state.strideLength),
                    icon = Icons.Default.DirectionsWalk,
                    modifier = Modifier.weight(1f)
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CareXDark.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "CADENCE",
                            style = MaterialTheme.typography.labelSmall,
                            color = CareXDark.onSurfaceVariant,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            String.format("%.1f steps/min", state.cadence),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = CareXDark.onSurface
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(CareXDark.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.DirectionsWalk,
                            contentDescription = null,
                            tint = CareXDark.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GaitMetricCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = CareXDark.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = CareXDark.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = CareXDark.onSurfaceVariant,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = CareXDark.onSurface
            )
        }
    }
}

package com.carex.poc

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ViewModel that orchestrates the full data pipeline:
 *   BT read → CSV parse → buffer → feature extract → ONNX inference → HTTP Server log → UI state
 *
 * UI state is exposed as a single [StateFlow<UiState>] that Compose observes.
 */
class SensorViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SensorViewModel"
        private const val PREFS_NAME = "carex_server_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_SERVER_ENABLED = "server_enabled"
        private const val KEY_FALL_ONLY = "fall_only"
        private const val KEY_PERIODIC_ENABLED = "periodic_enabled"
        private const val KEY_PERIODIC_INTERVAL_MINS = "periodic_interval_mins"
        private const val DEFAULT_SERVER_URL = "http://192.168.1.100:5000/api/predict"
        private const val DEFAULT_PERIODIC_INTERVAL_MINS = 60 // 1 hour default
        const val FALL_CONFIDENCE_THRESHOLD = 45f // Alert triggered only when fall confidence > 45%
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ----- UI State data class -----

    data class UiState(
        /** Current connection status. */
        val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
        /** Last error message, if any. */
        val errorMessage: String? = null,
        /** Name of the connected device. */
        val deviceName: String = "",
        /** Latest raw sensor values [ax, ay, az, gx, gy, gz]. */
        val liveValues: FloatArray = FloatArray(6),
        /** Number of samples collected in the current window (0–100). */
        val windowProgress: Int = 0,
        /** Predicted activity label after the last complete window. */
        val predictedActivity: String = "—",
        /** Confidence percentage (0–100) of the last prediction. */
        val confidence: Float = 0f,
        /** True when the last prediction was "Falling". */
        val isFallAlert: Boolean = false,
        /** Whether the ONNX model is loaded and ready. */
        val modelReady: Boolean = false,
        /** Total samples received since connection. */
        val totalSamples: Long = 0,
        /** Custom backend server URL. */
        val serverUrl: String = DEFAULT_SERVER_URL,
        /** Whether HTTP data transmission to server is enabled. */
        val isServerEnabled: Boolean = true,
        /** Send only fall alerts to server (or all predictions). */
        val sendOnlyFallAlerts: Boolean = false,
        /** Whether periodic data transmission (sensor, state, GPS) is enabled. */
        val isPeriodicEnabled: Boolean = true,
        /** Periodic transmission interval in minutes (default = 60 minutes / 1 hour). */
        val periodicIntervalMins: Int = DEFAULT_PERIODIC_INTERVAL_MINS,
        /** Timestamp of the last periodic transmission (in epoch ms). */
        val lastPeriodicSendTime: Long = 0L,
        /** Countdown seconds until the next periodic server sync. */
        val nextPeriodicSendInSecs: Long = 0L,
        /** Last HTTP response status string. */
        val lastServerStatus: String? = null,
        /** True while a server ping test is in progress. */
        val isTestingServer: Boolean = false,
        /** Live GPS location data fix. */
        val locationData: GpsData = GpsData(),
        /** Number of steps taken (increments while Walking). */
        val stepCount: Int = 0,
        /** Estimated walking speed in m/s. */
        val gaitSpeed: Float = 0f,
        /** Estimated stride length in m. */
        val strideLength: Float = 0f,
        /** Estimated cadence in steps/min. */
        val cadence: Float = 0f
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UiState) return false
            return connectionStatus == other.connectionStatus &&
                    errorMessage == other.errorMessage &&
                    deviceName == other.deviceName &&
                    liveValues.contentEquals(other.liveValues) &&
                    windowProgress == other.windowProgress &&
                    predictedActivity == other.predictedActivity &&
                    confidence == other.confidence &&
                    isFallAlert == other.isFallAlert &&
                    modelReady == other.modelReady &&
                    totalSamples == other.totalSamples &&
                    serverUrl == other.serverUrl &&
                    isServerEnabled == other.isServerEnabled &&
                    sendOnlyFallAlerts == other.sendOnlyFallAlerts &&
                    isPeriodicEnabled == other.isPeriodicEnabled &&
                    periodicIntervalMins == other.periodicIntervalMins &&
                    lastPeriodicSendTime == other.lastPeriodicSendTime &&
                    nextPeriodicSendInSecs == other.nextPeriodicSendInSecs &&
                    lastServerStatus == other.lastServerStatus &&
                    isTestingServer == other.isTestingServer &&
                    locationData == other.locationData &&
                    stepCount == other.stepCount &&
                    gaitSpeed == other.gaitSpeed &&
                    strideLength == other.strideLength &&
                    cadence == other.cadence
        }

        override fun hashCode(): Int {
            var result = connectionStatus.hashCode()
            result = 31 * result + liveValues.contentHashCode()
            result = 31 * result + windowProgress
            result = 31 * result + predictedActivity.hashCode()
            result = 31 * result + confidence.hashCode()
            result = 31 * result + isFallAlert.hashCode()
            result = 31 * result + serverUrl.hashCode()
            result = 31 * result + isServerEnabled.hashCode()
            result = 31 * result + isPeriodicEnabled.hashCode()
            result = 31 * result + periodicIntervalMins
            result = 31 * result + locationData.hashCode()
            result = 31 * result + stepCount
            result = 31 * result + gaitSpeed.hashCode()
            result = 31 * result + strideLength.hashCode()
            result = 31 * result + cadence.hashCode()
            return result
        }
    }

    enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED }

    // ----- Private state -----

    private val _uiState = MutableStateFlow(
        UiState(
            serverUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL,
            isServerEnabled = prefs.getBoolean(KEY_SERVER_ENABLED, true),
            sendOnlyFallAlerts = prefs.getBoolean(KEY_FALL_ONLY, false),
            isPeriodicEnabled = prefs.getBoolean(KEY_PERIODIC_ENABLED, true),
            periodicIntervalMins = prefs.getInt(KEY_PERIODIC_INTERVAL_MINS, DEFAULT_PERIODIC_INTERVAL_MINS)
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val btManager = BluetoothManager()
    private val gpsLocationManager = GpsLocationManager()
    private var classifier: OnnxClassifier? = null
    private var readJob: Job? = null
    private var periodicSyncJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null

    private val sampleBuffer = mutableListOf<FloatArray>()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val clf = OnnxClassifier(getApplication())
            classifier = clf
            _uiState.update { it.copy(modelReady = clf.isReady) }
        }

        startPeriodicSyncTimer()
    }


    // ----- GPS Location Tracker Methods -----

    fun startLocationUpdates(context: Context) {
        gpsLocationManager.startListening(context) { gpsData ->
            _uiState.update { it.copy(locationData = gpsData) }
        }
    }

    fun stopLocationUpdates() {
        gpsLocationManager.stopListening()
    }

    // ----- Server & Periodic Sync Settings Methods -----

    fun updateServerUrl(url: String) {
        val cleanUrl = url.trim()
        prefs.edit().putString(KEY_SERVER_URL, cleanUrl).apply()
        _uiState.update { it.copy(serverUrl = cleanUrl, lastServerStatus = null) }
    }

    fun updateServerEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SERVER_ENABLED, enabled).apply()
        _uiState.update { it.copy(isServerEnabled = enabled) }
        startPeriodicSyncTimer()
    }

    fun updateSendOnlyFallAlerts(fallOnly: Boolean) {
        prefs.edit().putBoolean(KEY_FALL_ONLY, fallOnly).apply()
        _uiState.update { it.copy(sendOnlyFallAlerts = fallOnly) }
    }

    fun updatePeriodicEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PERIODIC_ENABLED, enabled).apply()
        _uiState.update { it.copy(isPeriodicEnabled = enabled) }
        startPeriodicSyncTimer()
    }

    fun updatePeriodicInterval(minutes: Int) {
        val validMins = minutes.coerceAtLeast(1)
        prefs.edit().putInt(KEY_PERIODIC_INTERVAL_MINS, validMins).apply()
        _uiState.update { it.copy(periodicIntervalMins = validMins) }
        startPeriodicSyncTimer()
    }

    /**
     * Immediately triggers a periodic server telemetry update out of schedule.
     */
    fun triggerManualPeriodicSync() {
        viewModelScope.launch(Dispatchers.IO) {
            sendPeriodicTelemetryToServer()
            startPeriodicSyncTimer()
        }
    }

    /**
     * Start / Restart background periodic timer counting down to the next scheduled sync.
     */
    private fun startPeriodicSyncTimer() {
        periodicSyncJob?.cancel()

        periodicSyncJob = viewModelScope.launch(Dispatchers.IO) {
            var elapsedSecs = 0L
            while (isActive) {
                val state = uiState.value
                val totalSecs = (state.periodicIntervalMins * 60).toLong().coerceAtLeast(5L)
                val remainingSecs = totalSecs - elapsedSecs

                _uiState.update { it.copy(nextPeriodicSendInSecs = remainingSecs) }

                if (remainingSecs <= 0) {
                    if (state.isServerEnabled && state.isPeriodicEnabled) {
                        sendPeriodicTelemetryToServer()
                    }
                    elapsedSecs = 0L
                } else {
                    delay(1000L)
                    elapsedSecs++
                }
            }
        }
    }

    /**
     * Test connection to the configured Server URL by sending a ping JSON payload.
     */
    fun testServerConnection() {
        val targetUrl = uiState.value.serverUrl
        if (targetUrl.isBlank()) {
            _uiState.update { it.copy(lastServerStatus = "❌ URL cannot be empty") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isTestingServer = true, lastServerStatus = "Pinging $targetUrl...") }
            val status = postJsonToServer(
                targetUrl,
                buildJsonPayload(
                    event = "ping",
                    deviceName = "CareX-Test",
                    activity = "Test Ping",
                    confidence = 100f,
                    isFall = false,
                    liveValues = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
                )
            )
            _uiState.update { it.copy(isTestingServer = false, lastServerStatus = status) }
        }
    }

    // ----- Bluetooth Connection & Inference Pipeline -----

    fun connectToDevice(device: BluetoothDevice) {
        disconnect()

        readJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    connectionStatus = ConnectionStatus.CONNECTING,
                    errorMessage = null,
                    deviceName = device.name ?: device.address
                )
            }

            try {
                withContext(Dispatchers.IO) {
                    btManager.connect(device)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.DISCONNECTED,
                        errorMessage = "Connection failed: ${e.message}"
                    )
                }
                return@launch
            }

            _uiState.update { it.copy(connectionStatus = ConnectionStatus.CONNECTED) }

            sampleBuffer.clear()
            var totalSamples = 0L

            try {
                btManager.readLines().collect { line ->
                    val sample = parseCsvLine(line) ?: return@collect
                    totalSamples++

                    _uiState.update {
                        it.copy(
                            liveValues = sample.copyOf(),
                            totalSamples = totalSamples,
                            windowProgress = sampleBuffer.size + 1
                        )
                    }

                    sampleBuffer.add(sample)

                    if (sampleBuffer.size >= FeatureExtractor.WINDOW_SIZE) {
                        val window = sampleBuffer.toList()
                        sampleBuffer.clear()

                        val result = withContext(Dispatchers.IO) {
                            runInference(window)
                        }

                        if (result != null) {
                            val (label, conf) = result
                            val confPercent = conf * 100f
                            // Trigger fall alert ONLY if predicted as Falling AND confidence is above 60%
                            val isFall = label == "Falling" && confPercent > FALL_CONFIDENCE_THRESHOLD
                            val currentLive = sample.copyOf()

                            // CALCULATE GAIT METRICS FROM ESP32 DATA
                            var stepsInWindow = 0
                            var newCadence = 0f
                            var newStride = 0f
                            var newGaitSpeed = 0f
                            
                            if (label == "Walking") {
                                // Simple peak detection on total acceleration magnitude
                                val aMag = window.map { Math.sqrt((it[0]*it[0] + it[1]*it[1] + it[2]*it[2]).toDouble()) }
                                val mean = aMag.average()
                                for (i in 1 until aMag.size - 1) {
                                    if (aMag[i] > mean && aMag[i] > aMag[i-1] && aMag[i] > aMag[i+1]) {
                                        stepsInWindow++
                                    }
                                }
                                // Assuming 60 samples is approx 3 seconds
                                newCadence = stepsInWindow * 20f
                                if (newCadence < 40f) newCadence = 0f
                                if (newCadence > 180f) newCadence = 180f
                                
                                newStride = if (newCadence > 0) 0.4f * (newCadence / 100f) + 0.3f else 0f
                                newGaitSpeed = (newCadence / 60f) * newStride
                            } else {
                                // Non-walking placeholders
                                newCadence = (Math.random().toFloat() * 2f)
                                newStride = 0.1f + (Math.random().toFloat() * 0.05f)
                                newGaitSpeed = 0.1f + (Math.random().toFloat() * 0.05f)
                            }
                            
                            // SAVE TO JSON FILE
                            try {
                                val jsonObj = org.json.JSONObject()
                                jsonObj.put("timestamp", System.currentTimeMillis())
                                jsonObj.put("activity", label)
                                jsonObj.put("stepsInWindow", stepsInWindow)
                                jsonObj.put("cadence", newCadence)
                                jsonObj.put("strideLength", newStride)
                                jsonObj.put("gaitSpeed", newGaitSpeed)
                                val rawArray = org.json.JSONArray()
                                window.forEach { s ->
                                    val arr = org.json.JSONArray()
                                    s.forEach { v -> arr.put(v.toDouble()) }
                                    rawArray.put(arr)
                                }
                                jsonObj.put("rawSamples", rawArray)
                                
                                val file = java.io.File(getApplication<Application>().filesDir, "gait_data.json")
                                file.appendText(jsonObj.toString() + "\n")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to write json: ${e.message}")
                            }

                            if (isFall) {
                                playSiren()
                            }

                            _uiState.update {
                                it.copy(
                                    predictedActivity = label,
                                    confidence = confPercent,
                                    // Trigger alert on fall; retain active alert until user dismisses/silences
                                    isFallAlert = if (isFall) true else it.isFallAlert,
                                    windowProgress = 0,
                                    stepCount = it.stepCount + stepsInWindow,
                                    cadence = newCadence,
                                    strideLength = newStride,
                                    gaitSpeed = newGaitSpeed
                                )
                            }

                            // Trigger immediate HTTP Server fall alert logging asynchronously
                            val stateSnap = uiState.value
                            if (stateSnap.isServerEnabled) {
                                if (isFall || !stateSnap.sendOnlyFallAlerts) {
                                    sendTelemetryToServer(label, confPercent, isFall, currentLive)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Read loop error: ${e.message}", e)
            }

            _uiState.update {
                it.copy(
                    connectionStatus = ConnectionStatus.DISCONNECTED,
                    errorMessage = if (btManager.isConnected) null else "Device disconnected"
                )
            }
        }
    }

    private fun parseCsvLine(line: String): FloatArray? {
        return try {
            val parts = line.split(",")
            if (parts.size != 6) return null
            FloatArray(6) { parts[it].trim().toFloat() }
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun runInference(window: List<FloatArray>): Pair<String, Float>? {
        val clf = classifier ?: return null
        if (!clf.isReady) return null

        return try {
            val features = FeatureExtractor.extract(window)
            clf.predict(features)
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}", e)
            null
        }
    }

    /**
     * Start sounding the emergency siren (koiroylers-emergency-siren).
     */
    private fun playSiren() {
        try {
            if (mediaPlayer == null) {
                mediaPlayer = MediaPlayer.create(getApplication(), R.raw.emergency_siren)?.apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .build()
                    )
                    isLooping = true
                }
            }
            if (mediaPlayer?.isPlaying == false) {
                mediaPlayer?.start()
                Log.w(TAG, "🚨 Emergency siren sounding!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing emergency siren: ${e.message}", e)
        }
    }

    /**
     * Stop sounding the emergency siren.
     */
    private fun stopSiren() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
                mediaPlayer?.seekTo(0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping emergency siren: ${e.message}", e)
        }
    }

    /**
     * Dismiss the active fall alert banner and silence the emergency siren.
     */
    fun dismissFallAlert() {
        stopSiren()
        _uiState.update { it.copy(isFallAlert = false) }
    }

    fun disconnect() {
        stopSiren()
        readJob?.cancel()
        readJob = null
        sampleBuffer.clear()
        btManager.disconnect()
        _uiState.update {
            it.copy(
                connectionStatus = ConnectionStatus.DISCONNECTED,
                isFallAlert = false,
                windowProgress = 0
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopSiren()
        mediaPlayer?.release()
        mediaPlayer = null
        disconnect()
        stopLocationUpdates()
        periodicSyncJob?.cancel()
        classifier?.close()
    }

    // =========================================================================
    // HTTP Server Transmission Logic
    // =========================================================================

    private fun sendPeriodicTelemetryToServer() {
        val state = uiState.value
        val url = state.serverUrl
        if (url.isBlank()) return

        val jsonPayload = buildJsonPayload(
            event = "periodic_telemetry",
            deviceName = state.deviceName.ifBlank { "CareX-ESP32" },
            activity = state.predictedActivity,
            confidence = state.confidence,
            isFall = state.isFallAlert,
            liveValues = state.liveValues
        )

        val resultStatus = postJsonToServer(url, jsonPayload)
        val now = System.currentTimeMillis()
        _uiState.update {
            it.copy(
                lastServerStatus = resultStatus,
                lastPeriodicSendTime = now
            )
        }
    }

    private fun sendTelemetryToServer(
        label: String,
        confPercent: Float,
        isFall: Boolean,
        liveValues: FloatArray
    ) {
        val state = uiState.value
        val url = state.serverUrl
        if (url.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            val jsonPayload = buildJsonPayload(
                event = if (isFall) "fall_detected" else "prediction",
                deviceName = state.deviceName.ifBlank { "CareX-ESP32" },
                activity = label,
                confidence = confPercent,
                isFall = isFall,
                liveValues = liveValues
            )

            val resultStatus = postJsonToServer(url, jsonPayload)
            _uiState.update { it.copy(lastServerStatus = resultStatus) }
        }
    }

    private fun postJsonToServer(targetUrl: String, json: String): String {
        return try {
            val url = URL(targetUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(json)
                writer.flush()
            }

            val code = conn.responseCode
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            if (code in 200..299) {
                "[$timeStr] Sent -> HTTP $code OK"
            } else {
                "[$timeStr] Server returned HTTP $code"
            }
        } catch (e: Exception) {
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            "[$timeStr] Error: ${e.localizedMessage ?: e.message}"
        }
    }

    private fun buildJsonPayload(
        event: String,
        deviceName: String,
        activity: String,
        confidence: Float,
        isFall: Boolean,
        liveValues: FloatArray
    ): String {
        val timestamp = System.currentTimeMillis()
        val loc = uiState.value.locationData

        val ax = if (liveValues.size > 0) liveValues[0] else 0f
        val ay = if (liveValues.size > 1) liveValues[1] else 0f
        val az = if (liveValues.size > 2) liveValues[2] else 0f
        val gx = if (liveValues.size > 3) liveValues[3] else 0f
        val gy = if (liveValues.size > 4) liveValues[4] else 0f
        val gz = if (liveValues.size > 5) liveValues[5] else 0f

        return """
            {
              "event": "$event",
              "timestamp": $timestamp,
              "device_name": "$deviceName",
              "predicted_activity": "$activity",
              "confidence": $confidence,
              "is_fall": $isFall,
              "location": {
                "latitude": ${loc.latitude},
                "longitude": ${loc.longitude},
                "altitude": ${loc.altitude},
                "accuracy": ${loc.accuracy},
                "speed": ${loc.speed},
                "has_fix": ${loc.hasFix},
                "provider": "${loc.provider}"
              },
              "sensor_data": {
                "ax": $ax,
                "ay": $ay,
                "az": $az,
                "gx": $gx,
                "gy": $gy,
                "gz": $gz
              }
            }
        """.trimIndent()
    }
}

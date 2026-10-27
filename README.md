<p align="center">
  <img src="app/src/main/res/drawable/splash_logo.png" width="140" alt="CareX Logo"/>
</p>

<h1 align="center">CareX</h1>
<p align="center">
  <strong>AI-Powered Fall Prediction &amp; Activity Recognition for Elderly Care</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-brightgreen?logo=android" alt="Platform"/>
  <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose" alt="Compose"/>
  <img src="https://img.shields.io/badge/ML-Random%20Forest%20(ONNX)-FF6F00" alt="ML"/>
  <img src="https://img.shields.io/badge/Min%20SDK-26-blue" alt="Min SDK"/>
  <img src="https://img.shields.io/badge/Target%20SDK-36-blue" alt="Target SDK"/>
</p>

---

## 📋 Overview

**CareX** is a real-time, on-device fall detection and human activity recognition (HAR) Android application designed for elderly care monitoring. It connects to an **ESP32 + MPU6500** wearable sensor via **Bluetooth Classic (SPP)**, streams 6-axis IMU data at 30 Hz, and classifies activities using a **Random Forest** machine learning model running locally via **ONNX Runtime** — no cloud dependency required for inference.

When a fall is detected with high confidence (>45%), CareX triggers an **emergency siren alert** and can automatically transmit the event (with GPS coordinates) to a remote server for caregiver notification.

---

## ✨ Key Features

| Feature | Description |
|---|---|
| 🤖 **On-Device ML Inference** | Random Forest classifier converted to ONNX, runs entirely on-device via ONNX Runtime — zero latency, works offline |
| 📡 **Bluetooth SPP Streaming** | Connects to ESP32 + MPU6500 sensor over Bluetooth Classic, reads 6-axis IMU data at 30 Hz |
| 🏃 **Activity Recognition** | Classifies 4 activities: **Sitting**, **Standing**, **Walking**, **Falling** |
| 🚨 **Fall Alert System** | Visual + audio emergency siren alert when fall confidence exceeds 45% threshold |
| 🗺️ **Live GPS Map** | Real-time location tracking with interactive Leaflet.js map showing user position |
| 🚶 **Gait Analysis** | Step counting, cadence, stride length, and walking speed estimation during walking |
| ☁️ **Server Data Logging** | Configurable HTTP POST to custom backend — supports fall-only alerts or periodic bulk transmission |
| 🎨 **Premium Dark UI** | Jetpack Compose Material 3 with custom dark color scheme, animations, and glassmorphism-inspired cards |

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────┐
│                     ESP32 + MPU6500                      │
│              (Wearable Wrist Sensor Device)              │
│         Streams CSV: ax,ay,az,gx,gy,gz @ 30Hz           │
└────────────────────────┬────────────────────────────────┘
                         │ Bluetooth SPP (RFCOMM)
                         ▼
┌─────────────────────────────────────────────────────────┐
│                  CareX Android App                       │
│                                                          │
│  ┌──────────────┐  ┌────────────────┐  ┌──────────────┐ │
│  │ Bluetooth     │  │ Feature        │  │ ONNX         │ │
│  │ Manager       │──│ Extractor      │──│ Classifier   │ │
│  │ (SPP Socket)  │  │ (40 features)  │  │ (RandomForest│ │
│  └──────────────┘  └────────────────┘  └──────┬───────┘ │
│                                                │         │
│  ┌──────────────┐  ┌────────────────┐          ▼         │
│  │ GPS Location  │  │ Sensor         │  ┌──────────────┐ │
│  │ Manager       │  │ ViewModel      │◀─│ Prediction   │ │
│  └──────┬───────┘  │ (Orchestrator)  │  │ Result       │ │
│         │          └───────┬────────┘  └──────────────┘ │
│         │                  │                             │
│         ▼                  ▼                             │
│  ┌──────────────────────────────────────────────────┐   │
│  │              Jetpack Compose UI                    │   │
│  │  Splash → Connect → Live Data → Gait → Map → Settings│
│  └──────────────────────────────────────────────────┘   │
│                         │                                │
│                         ▼ (HTTP POST)                    │
│              ┌─────────────────────┐                     │
│              │ Remote Server (opt.) │                     │
│              └─────────────────────┘                     │
└─────────────────────────────────────────────────────────┘
```

---

## 📂 Project Structure

```
CareX/
├── app/
│   ├── build.gradle.kts              # App-level build config & dependencies
│   ├── proguard-rules.pro            # ProGuard rules
│   └── src/main/
│       ├── AndroidManifest.xml       # Permissions & activity declaration
│       ├── assets/
│       │   ├── activity_rf.onnx      # Trained RandomForest model (ONNX format)
│       │   ├── map.html              # Leaflet.js map for GPS visualization
│       │   └── *.mp3                 # Emergency siren sound
│       ├── java/com/carex/poc/
│       │   ├── MainActivity.kt       # All Compose UI screens (~1900 lines)
│       │   ├── SensorViewModel.kt    # Data pipeline orchestrator (ViewModel)
│       │   ├── BluetoothManager.kt   # Bluetooth Classic SPP connection
│       │   ├── FeatureExtractor.kt   # 40-feature statistical extraction
│       │   ├── GpsLocationManager.kt # GPS/Network location tracking
│       │   └── OnnxClassifier.kt     # ONNX Runtime inference wrapper
│       └── res/
│           ├── drawable/             # Splash screen logo
│           ├── mipmap-*/             # App launcher icons
│           └── raw/                  # Emergency siren audio
├── temp/                             # ML training scripts (Python)
│   ├── train.py                      # Full training pipeline
│   ├── convert_to_onnx.py           # sklearn → ONNX conversion
│   └── live_predict.py              # Desktop live prediction testing
├── build.gradle.kts                  # Root build file (AGP 9.4 + Kotlin 2.1)
├── settings.gradle.kts               # Project settings
├── gradle.properties                 # Gradle JVM & build settings
├── gradle/wrapper/                   # Gradle wrapper (auto-downloads Gradle)
└── .gitignore                        # Git ignore rules
```

---

## 🧠 Machine Learning Pipeline

### Training (`temp/train.py`)

The ML model is trained offline using Python and scikit-learn:

1. **Data Collection**: IMU data collected from ESP32 + MPU6500 sensor worn on the wrist
2. **Preprocessing**: Accelerometer scale normalization (÷8 if median magnitude > 40)
3. **Segmentation**: Timestamp gap splitting → resampling to 30 Hz → 2-second sliding windows (60 samples)
4. **Feature Extraction**: 40 statistical features per window
   - **8 signals**: `acc_mag`, `gyr_mag`, `ax`, `ay`, `az`, `gx`, `gy`, `gz`
   - **5 statistics each**: `mean`, `std`, `min`, `max`, `ptp` (peak-to-peak)
5. **Model**: `RandomForestClassifier` with Stratified Group K-Fold cross-validation
6. **Export**: `activity_rf.joblib` → converted to ONNX via `skl2onnx`

### Activity Classes

| Class ID | Activity | Description |
|----------|----------|-------------|
| 1 | Sitting | Stationary, seated posture |
| 2 | Standing | Stationary, upright posture |
| 3 | Walking | Ambulatory motion with gait patterns |
| 4 | Falling | Sudden acceleration spike indicating a fall event |

### On-Device Inference

- **Runtime**: [ONNX Runtime for Android](https://onnxruntime.ai/) v1.29.0
- **Model size**: ~547 KB (`.onnx`)
- **Input**: 40 float features → shape `[1, 40]`
- **Output**: Predicted class label + per-class probabilities
- **Latency**: Real-time (< 5ms per window on modern devices)

---

## 📱 App Screens

| Screen | Description |
|--------|-------------|
| **Splash** | Animated splash screen with CareX branding |
| **Connect** | Bluetooth device scanner, shows paired ESP32 devices for one-tap connection |
| **Live Data** | Real-time sensor values (accelerometer + gyroscope), activity prediction with confidence gauge, fall alert banner |
| **Gait Analysis** | Step count, cadence (steps/min), stride length (m), walking speed (m/s) — active during Walking |
| **Map** | Interactive Leaflet.js WebView map showing real-time GPS location with accuracy circle |
| **Settings** | Server URL configuration, periodic transmission intervals, fall-only mode, connection test |

---

## 🔧 Tech Stack

| Component | Technology |
|-----------|-----------|
| **Language** | Kotlin 2.1 |
| **UI Framework** | Jetpack Compose + Material 3 |
| **Build System** | Gradle 9.4 (AGP 9.4.0) with Kotlin DSL |
| **ML Runtime** | ONNX Runtime for Android 1.29.0 |
| **ML Training** | Python, scikit-learn, skl2onnx |
| **Bluetooth** | Android Bluetooth Classic (SPP / RFCOMM) |
| **Location** | Android LocationManager (GPS + Network) |
| **Map** | Leaflet.js via WebView |
| **Architecture** | MVVM (ViewModel + StateFlow + Compose) |
| **Concurrency** | Kotlin Coroutines + Flow |
| **Min SDK** | API 26 (Android 8.0 Oreo) |
| **Target SDK** | API 36 (Android 16) |

---

## 🔌 Hardware Requirements

### Sensor Device
- **MCU**: ESP32 (any variant with Bluetooth Classic)
- **IMU**: MPU6500 (6-axis: 3-axis accelerometer + 3-axis gyroscope)
- **Data Format**: CSV over Bluetooth SPP at 30 Hz
  ```
  ax,ay,az,gx,gy,gz\n
  0.1234,-0.4321,9.7123,0.0123,-0.0234,0.0345\n
  ```
- **Placement**: Wrist-worn for optimal fall detection accuracy

### Android Device
- Android 8.0+ (API 26+)
- Bluetooth Classic support
- GPS/Location services
- Internet (optional, for server data logging)

---

## 🚀 Getting Started

### Prerequisites

- [Android Studio](https://developer.android.com/studio) (Ladybug or newer recommended)
- JDK 17+
- Android SDK 36
- An ESP32 + MPU6500 sensor device running the companion firmware

### Build & Run

1. **Clone the repository**:
   ```bash
   git clone https://github.com/jayasureyaps/CareX.git
   cd CareX
   ```

2. **Open in Android Studio**:
   - File → Open → Select the cloned directory

3. **Sync Gradle**:
   - Android Studio will auto-sync; if not, click "Sync Now"

4. **Run on device**:
   - Connect your Android phone via USB
   - Enable USB Debugging in Developer Options
   - Click ▶ Run

### Pair the Sensor

1. Pair your ESP32 device via Android Bluetooth settings
2. Open CareX → Connect screen → Tap your ESP32 device
3. Data streaming starts automatically after connection

---

## ⚙️ Server Configuration (Optional)

CareX can send prediction data and GPS location to a custom backend:

1. Navigate to **Settings** screen
2. Enter your server URL (e.g., `http://192.168.1.100:5000/api/predict`)
3. Configure transmission options:
   - **Fall-only alerts**: Send data only when falls are detected
   - **Periodic transmission**: Send bulk data at configurable intervals (default: 60 min)
4. Use **Test Connection** to verify server reachability

### Expected Server Endpoint

The app sends HTTP POST requests with JSON body:

```json
{
  "activity": "Walking",
  "confidence": 0.92,
  "is_fall": false,
  "sensor_data": {
    "ax": 0.12, "ay": -0.43, "az": 9.71,
    "gx": 0.01, "gy": -0.02, "gz": 0.03
  },
  "location": {
    "latitude": 12.9716,
    "longitude": 77.5946,
    "altitude": 920.0,
    "accuracy": 8.5,
    "speed": 1.2
  },
  "timestamp": "2026-10-27T18:30:00"
}
```

---

## 🔬 Retraining the Model

To retrain with your own data:

1. **Collect IMU data** from the ESP32 sensor in CSV format
2. **Organize files**:
   - `mpu_dataset_sitting.csv`
   - `mpu_dataset_standing.csv`
   - `mpu_dataset_walking.csv`
   - `mpu_dataset_YYYYMMDD_HHMMSS.csv` (each timestamped file = one fall recording)
3. **Train**:
   ```bash
   cd temp/
   pip install numpy pandas scikit-learn joblib
   python train.py /path/to/data/
   ```
4. **Convert to ONNX**:
   ```bash
   pip install skl2onnx onnxruntime
   python convert_to_onnx.py
   ```
5. **Deploy**: Copy `activity_rf.onnx` to `app/src/main/assets/`

---

## 📄 License

This project is open source. Feel free to use, modify, and distribute.

---

## 🙏 Acknowledgments

- [ONNX Runtime](https://onnxruntime.ai/) — Cross-platform ML inference
- [scikit-learn](https://scikit-learn.org/) — RandomForest training
- [Leaflet.js](https://leafletjs.com/) — Interactive map visualization
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — Modern Android UI
- [Material Design 3](https://m3.material.io/) — Design system

---

<p align="center">
  Built with ❤️ for elderly care and safety
</p>

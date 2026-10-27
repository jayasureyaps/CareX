import serial
import time
import joblib
import numpy as np
from collections import deque

import os
import subprocess

# =========================
# CONFIG
# =========================

PORT = "COM4"
BAUD = 115200

MODEL_FILE = "activity_rf.joblib"
SIREN_SOUND_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "koiroylers-emergency-siren-351963.mp3")
FALL_CONFIDENCE_THRESHOLD = 60.0  # Alert only if fall confidence is above 60%

FS = 30
WINDOW_SIZE = FS * 2       # 2 seconds = 60 samples
STEP = FS                  # predict every 1 second

LABELS = {
    1: "SITTING",
    2: "STANDING",
    3: "WALKING",
    4: "FALLING"
}

_siren_proc = None

def play_siren():
    """Play emergency siren sound in background without blocking live stream."""
    global _siren_proc
    if not os.path.isfile(SIREN_SOUND_FILE):
        return
    # Avoid overlapping sirens if one is already sounding
    if _siren_proc is not None and _siren_proc.poll() is None:
        return
    try:
        ps_cmd = (
            f"$p = New-Object -ComObject wmplayer.ocx; "
            f"$p.URL = '{SIREN_SOUND_FILE}'; "
            f"$p.controls.play(); "
            f"Start-Sleep -Seconds 6; "
            f"$p.controls.stop()"
        )
        _siren_proc = subprocess.Popen(
            ["powershell", "-NoProfile", "-NonInteractive", "-Command", ps_cmd],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL
        )
    except Exception as err:
        print(f"\n[Warning] Could not play siren audio: {err}")


# =========================
# LOAD MODEL
# =========================

print("Loading model...")

model = joblib.load(MODEL_FILE)

print("Model loaded.")
print("Classes:", model.classes_)


# =========================
# FEATURE EXTRACTION
# =========================

def extract_features(data):

    acc = data[:, :3]
    gyr = data[:, 3:]

    signals = [
        np.linalg.norm(acc, axis=1),
        np.linalg.norm(gyr, axis=1),
        *acc.T,
        *gyr.T
    ]

    features = []

    for signal in signals:
        features.extend([
            signal.mean(),
            signal.std(),
            signal.min(),
            signal.max(),
            np.ptp(signal)
        ])

    return np.array(features).reshape(1, -1)


# =========================
# CONNECT ESP32
# =========================

print()
print(f"Connecting to {PORT}...")

ser = serial.Serial(
    PORT,
    BAUD,
    timeout=1
)

time.sleep(2)

print("Connected.")
print()
print("Starting live prediction...")
print("Keep the sensor on the person.")
print("Press Ctrl+C to stop.")
print()


# =========================
# BUFFER
# =========================

buffer = deque(maxlen=WINDOW_SIZE)

sample_count = 0


# =========================
# LIVE LOOP
# =========================

try:

    while True:

        line = ser.readline().decode(
            errors="ignore"
        ).strip()

        if not line:
            continue

        try:

            values = list(
                map(float, line.split(","))
            )

        except ValueError:
            continue

        # Expected:
        # ax,ay,az,gx,gy,gz

        if len(values) != 6:
            continue

        ax, ay, az, gx, gy, gz = values

        # =========================
        # ACCELERATION SCALE
        # =========================

        acc_mag = np.sqrt(
            ax * ax +
            ay * ay +
            az * az
        )

        # Training data was automatically
        # divided by 8 when magnitude > 40.

        if acc_mag > 40:
            ax /= 8.0
            ay /= 8.0
            az /= 8.0

        # =========================
        # ADD SAMPLE
        # =========================

        buffer.append([
            ax, ay, az,
            gx, gy, gz
        ])

        sample_count += 1

        # =========================
        # WAIT FOR 2 SECONDS
        # =========================

        if len(buffer) < WINDOW_SIZE:

            print(
                f"\rCollecting: "
                f"{len(buffer)}/{WINDOW_SIZE}",
                end=""
            )

            continue

        # =========================
        # PREDICT EVERY 1 SECOND
        # =========================

        if sample_count % STEP != 0:
            continue

        data = np.array(buffer)

        # Extract same 40 features
        # used during training.

        features = extract_features(data)

        prediction = model.predict(features)[0]

        probabilities = model.predict_proba(features)[0]

        label = LABELS.get(
            int(prediction),
            "UNKNOWN"
        )

        # Find probability belonging
        # to predicted class.

        class_index = list(
            model.classes_
        ).index(prediction)

        confidence = (
            probabilities[class_index] * 100
        )

        print(
            f"\rActivity: {label:<10} "
            f"Confidence: {confidence:6.2f}%"
        )

        # =========================
        # FALL ALERT (Threshold > 60%)
        # =========================

        if int(prediction) == 4 and confidence > FALL_CONFIDENCE_THRESHOLD:

            print()
            print("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
            print(f"       FALL DETECTED! (Confidence: {confidence:.2f}%)")
            print("       Triggering emergency siren sound...")
            print("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
            play_siren()


except KeyboardInterrupt:

    print()
    print()
    print("Stopping live prediction...")


finally:

    ser.close()

    print("Serial connection closed.")
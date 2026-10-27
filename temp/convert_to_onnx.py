"""
Convert the scikit-learn RandomForest (activity_rf.joblib) to ONNX format.

Requirements:
    pip install joblib scikit-learn skl2onnx onnxruntime

Usage:
    python convert_to_onnx.py

Output:
    activity_rf.onnx  (in the same directory)
"""

import os
import joblib
import numpy as np
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType

# ============================================================
# LOAD THE TRAINED MODEL
# ============================================================

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MODEL_PATH = os.path.join(SCRIPT_DIR, "activity_rf.joblib")
OUTPUT_PATH = os.path.join(SCRIPT_DIR, "activity_rf.onnx")

print(f"Loading model from: {MODEL_PATH}")
model = joblib.load(MODEL_PATH)

print(f"Model type: {type(model).__name__}")
print(f"Classes: {model.classes_}")
print(f"Number of estimators: {model.n_estimators}")

# ============================================================
# DETERMINE NUMBER OF FEATURES
# ============================================================

# The model expects 40 features:
#   8 signals × 5 stats (mean, std, min, max, ptp)
#   Signals: acc_mag, gyr_mag, ax, ay, az, gx, gy, gz

n_features = model.n_features_in_
print(f"Number of input features: {n_features}")

# ============================================================
# CONVERT TO ONNX
# ============================================================

initial_type = [
    ("float_input", FloatTensorType([None, n_features]))
]

onnx_model = convert_sklearn(
    model,
    initial_types=initial_type,
    target_opset=12,
    options={
        type(model): {
            "zipmap": False  # Output raw probabilities array instead of dict
        }
    }
)

# ============================================================
# SAVE ONNX MODEL
# ============================================================

with open(OUTPUT_PATH, "wb") as f:
    f.write(onnx_model.SerializeToString())

print(f"\nONNX model saved to: {OUTPUT_PATH}")
print(f"File size: {os.path.getsize(OUTPUT_PATH):,} bytes")

# ============================================================
# VERIFY THE ONNX MODEL
# ============================================================

print("\nVerifying ONNX model...")

import onnxruntime as ort

session = ort.InferenceSession(OUTPUT_PATH)

print(f"Input name: {session.get_inputs()[0].name}")
print(f"Input shape: {session.get_inputs()[0].shape}")
print(f"Output names: {[o.name for o in session.get_outputs()]}")

# Run a test prediction with random data
test_input = np.random.randn(1, n_features).astype(np.float32)
results = session.run(None, {"float_input": test_input})

print(f"Test prediction label: {results[0]}")
print(f"Test prediction probabilities shape: {results[1].shape if hasattr(results[1], 'shape') else type(results[1])}")

print("\n✅ Conversion successful!")

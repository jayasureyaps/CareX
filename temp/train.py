
"""
MPU6500 Wrist Activity / Fall Detection

Labels:
    sitting  = 1
    standing = 2
    walking  = 3
    falling  = 4

Files:
    mpu_dataset_sitting.csv
    mpu_dataset_standing.csv
    mpu_dataset_walking.csv

    Every timestamped file:
    mpu_dataset_YYYYMMDD_HHMMSS.csv

    is treated as ONE FALL RECORDING.

Pipeline:
    1. Load recordings
    2. Fix accelerometer scale
    3. Split at timestamp gaps
    4. Resample to 30 Hz
    5. Create 2-second windows
    6. Extract 40 statistical features
    7. Random Forest
    8. Grouped cross-validation
    9. Train final model
    10. Save activity_rf.joblib
"""

import sys
import glob
import os

import numpy as np
import pandas as pd
import joblib

from sklearn.ensemble import RandomForestClassifier
from sklearn.model_selection import StratifiedGroupKFold
from sklearn.metrics import classification_report, confusion_matrix


# ============================================================
# SETTINGS
# ============================================================

DATA_DIR = sys.argv[1] if len(sys.argv) > 1 else "."

FS = 30
WIN = 2 * FS
STEP = WIN // 2

GAP_S = 0.5

BLOCK_S = 60

N_FOLDS = 5

COLS = [
    "ax",
    "ay",
    "az",
    "gx",
    "gy",
    "gz"
]

LABELS = {
    "sitting": 1,
    "standing": 2,
    "walking": 3,
    "falling": 4
}


# ============================================================
# LOAD ONE CSV
# ============================================================

def load_csv(path):

    d = pd.read_csv(path)

    required = [
        "timestamp",
        *COLS
    ]

    missing = [
        c for c in required
        if c not in d.columns
    ]

    if missing:

        raise ValueError(
            f"\n{os.path.basename(path)} "
            f"is missing columns: {missing}\n"
            f"Found columns: {list(d.columns)}"
        )

    d = d.dropna(
        subset=required
    )

    d = d.sort_values(
        "timestamp"
    )

    # Remove duplicate/backwards timestamps
    d = d[
        d["timestamp"].diff().fillna(1) > 0
    ]

    if len(d) == 0:

        raise ValueError(
            f"No usable data in {path}"
        )

    X = d[COLS].values.astype(float)

    # --------------------------------------------------------
    # Fix accelerometer scale
    # --------------------------------------------------------

    accel_mag = np.linalg.norm(
        X[:, :3],
        axis=1
    )

    med = np.median(
        accel_mag
    )

    if med > 40:

        X[:, :3] /= 8.0

        print(
            f"    Accel scaled /8 "
            f"(median |a| = {med:.2f})"
        )

    return (
        d["timestamp"].values,
        X
    )


# ============================================================
# RESAMPLE
# ============================================================

def resample_segments(t, X):

    breaks = np.where(
        np.diff(t) > GAP_S
    )[0] + 1

    segments = []

    t_parts = np.split(
        t,
        breaks
    )

    X_parts = np.split(
        X,
        breaks
    )

    for ts, Xs in zip(
        t_parts,
        X_parts
    ):

        if ts[-1] - ts[0] < WIN / FS:
            continue

        grid = np.arange(
            ts[0],
            ts[-1],
            1 / FS
        )

        seg = np.column_stack([
            np.interp(
                grid,
                ts,
                Xs[:, i]
            )
            for i in range(6)
        ])

        segments.append(seg)

    return segments


# ============================================================
# FEATURE EXTRACTION
# ============================================================

def window_features(w):

    acc = w[:, :3]

    gyr = w[:, 3:]

    signals = [

        # Acceleration magnitude
        np.linalg.norm(
            acc,
            axis=1
        ),

        # Gyroscope magnitude
        np.linalg.norm(
            gyr,
            axis=1
        ),

        # Individual accelerometer axes
        *acc.T,

        # Individual gyroscope axes
        *gyr.T
    ]

    features = []

    for s in signals:

        features.extend([
            s.mean(),
            s.std(),
            s.min(),
            s.max(),
            np.ptp(s)
        ])

    return features


# ============================================================
# BUILD DATASET
# ============================================================

def build_dataset(data_dir):

    F = []

    y = []

    groups = []

    # --------------------------------------------------------
    # NORMAL ACTIVITIES
    # --------------------------------------------------------

    normal_files = [
        (
            "mpu_dataset_sitting.csv",
            "sitting"
        ),
        (
            "mpu_dataset_standing.csv",
            "standing"
        ),
        (
            "mpu_dataset_walking.csv",
            "walking"
        )
    ]

    # --------------------------------------------------------
    # Add normal recordings
    # --------------------------------------------------------

    for filename, label_name in normal_files:

        path = os.path.join(
            data_dir,
            filename
        )

        if not os.path.isfile(path):

            print(
                f"Not found: {filename}"
            )

            continue

        print(
            f"\nLoading: "
            f"{filename} -> {label_name}"
        )

        t, X = load_csv(
            path
        )

        segments = resample_segments(
            t,
            X
        )

        label = LABELS[
            label_name
        ]

        for si, seg in enumerate(
            segments
        ):

            block = BLOCK_S * FS

            for bi, start in enumerate(
                range(
                    0,
                    len(seg),
                    block
                )
            ):

                chunk = seg[
                    start:start + block
                ]

                if len(chunk) < WIN:
                    continue

                gid = (
                    f"{label_name}"
                    f"_s{si}"
                    f"_b{bi}"
                )

                for i in range(
                    0,
                    len(chunk) - WIN + 1,
                    STEP
                ):

                    window = chunk[
                        i:i + WIN
                    ]

                    F.append(
                        window_features(
                            window
                        )
                    )

                    y.append(
                        label
                    )

                    groups.append(
                        gid
                    )

    # ========================================================
    # FALL RECORDINGS
    # ========================================================
    #
    # Every timestamped CSV is ONE separate fall recording.
    #
    # Example:
    #
    # mpu_dataset_20261006_111004.csv
    # mpu_dataset_20261006_111022.csv
    # mpu_dataset_20261006_111054.csv
    #
    # Each gets label 4.
    # ========================================================

    fall_pattern = os.path.join(
        data_dir,
        "mpu_dataset_????????_??????.csv"
    )

    fall_files = sorted(
        glob.glob(
            fall_pattern
        )
    )

    print(
        f"\nFall recordings found: "
        f"{len(fall_files)}"
    )

    fall_label = LABELS[
        "falling"
    ]

    for fall_number, path in enumerate(
        fall_files,
        1
    ):

        filename = os.path.basename(
            path
        )

        print(
            f"  Fall {fall_number}: "
            f"{filename}"
        )

        t, X = load_csv(
            path
        )

        segments = resample_segments(
            t,
            X
        )

        # ----------------------------------------------------
        # Each fall file remains a separate group.
        #
        # This is VERY important for validation.
        # ----------------------------------------------------

        for si, seg in enumerate(
            segments
        ):

            if len(seg) < WIN:
                continue

            gid = (
                f"fall_{fall_number}"
                f"_s{si}"
            )

            # ------------------------------------------------
            # Find strongest acceleration deviation.
            # ------------------------------------------------

            accel_mag = np.linalg.norm(
                seg[:, :3],
                axis=1
            )

            deviation = np.abs(
                accel_mag - 9.81
            )

            impact = int(
                np.argmax(
                    deviation
                )
            )

            # ------------------------------------------------
            # Create 2-second windows.
            #
            # Only windows containing the impact
            # are labelled as falling.
            # ------------------------------------------------

            for i in range(
                0,
                len(seg) - WIN + 1,
                STEP
            ):

                if not (
                    i <= impact < i + WIN
                ):
                    continue

                window = seg[
                    i:i + WIN
                ]

                F.append(
                    window_features(
                        window
                    )
                )

                y.append(
                    fall_label
                )

                groups.append(
                    gid
                )

    # ========================================================
    # FINAL ARRAYS
    # ========================================================

    if not F:

        raise RuntimeError(
            "\nNo training windows were created."
        )

    return (
        np.asarray(F),
        np.asarray(y),
        np.asarray(groups)
    )


# ============================================================
# MAIN
# ============================================================

def main():

    print(
        "=============================================="
    )

    print(
        " MPU6500 Activity / Fall Detection Training"
    )

    print(
        "=============================================="
    )

    print(
        f"\nData directory:\n"
        f"  {os.path.abspath(DATA_DIR)}"
    )

    print(
        "\nLoading and preprocessing..."
    )

    X, y, groups = build_dataset(
        DATA_DIR
    )

    # ========================================================
    # DATASET SUMMARY
    # ========================================================

    print(
        "\n=============================================="
    )

    print(
        " Dataset"
    )

    print(
        "=============================================="
    )

    names = {
        1: "sitting",
        2: "standing",
        3: "walking",
        4: "falling"
    }

    unique_labels, counts = np.unique(
        y,
        return_counts=True
    )

    for label, count in zip(
        unique_labels,
        counts
    ):

        print(
            f"{label} = "
            f"{names[label]:<10} : "
            f"{count}"
        )

    print(
        f"\nTotal windows: "
        f"{len(X)}"
    )

    print(
        f"Features: "
        f"{X.shape[1]}"
    )

    print(
        f"Groups: "
        f"{len(np.unique(groups))}"
    )

    # ========================================================
    # CHECK GROUP COUNT
    # ========================================================

    n_groups = len(
        np.unique(groups)
    )

    n_folds = min(
        N_FOLDS,
        n_groups
    )

    if n_folds < 2:

        raise RuntimeError(
            "Not enough groups for cross-validation."
        )

    print(
        f"\nStarting "
        f"{n_folds}-fold grouped "
        f"cross-validation..."
    )

    # ========================================================
    # CROSS VALIDATION
    # ========================================================

    cv = StratifiedGroupKFold(
        n_splits=n_folds,
        shuffle=True,
        random_state=0
    )

    y_true = []

    y_pred = []

    for k, (
        train_idx,
        test_idx
    ) in enumerate(
        cv.split(
            X,
            y,
            groups=groups
        ),
        1
    ):

        clf = RandomForestClassifier(
            n_estimators=300,
            class_weight="balanced",
            random_state=0,
            n_jobs=-1
        )

        clf.fit(
            X[train_idx],
            y[train_idx]
        )

        pred = clf.predict(
            X[test_idx]
        )

        accuracy = np.mean(
            pred == y[test_idx]
        )

        print(
            f"Fold {k}: "
            f"accuracy = {accuracy:.3f}"
        )

        y_true.extend(
            y[test_idx]
        )

        y_pred.extend(
            pred
        )

    # ========================================================
    # RESULTS
    # ========================================================

    print(
        "\n=============================================="
    )

    print(
        " Cross-validated results"
    )

    print(
        "==============================================\n"
    )

    print(
        classification_report(
            y_true,
            y_pred,
            labels=[
                1,
                2,
                3,
                4
            ],
            target_names=[
                "sitting",
                "standing",
                "walking",
                "falling"
            ],
            digits=3,
            zero_division=0
        )
    )

    # ========================================================
    # CONFUSION MATRIX
    # ========================================================

    print(
        "Confusion matrix"
    )

    print(
        "(rows = true, columns = predicted)"
    )

    print(
        "Labels: "
        "[sitting, standing, walking, falling]"
    )

    print(
        confusion_matrix(
            y_true,
            y_pred,
            labels=[
                1,
                2,
                3,
                4
            ]
        )
    )

    # ========================================================
    # FINAL MODEL
    # ========================================================

    print(
        "\nTraining final Random Forest "
        "on all available data..."
    )

    final_model = RandomForestClassifier(
        n_estimators=300,
        class_weight="balanced",
        random_state=0,
        n_jobs=-1
    )

    final_model.fit(
        X,
        y
    )

    # ========================================================
    # SAVE MODEL
    # ========================================================

    output_path = os.path.join(
        DATA_DIR,
        "activity_rf.joblib"
    )

    joblib.dump(
        final_model,
        output_path
    )

    print(
        f"\nSaved model -> "
        f"{output_path}"
    )

    print(
        "\nTraining complete."
    )


# ============================================================
# ENTRY POINT
# ============================================================

if __name__ == "__main__":
    main()

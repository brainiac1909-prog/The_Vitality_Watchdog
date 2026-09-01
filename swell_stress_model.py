"""
HRV -> Stress Decision Tree Model
==================================
Replicates Section D ("Stress model construction") of:
Tsai et al., "Photoplethysmography-based HRV analysis and machine learning
for real-time stress quantification in mental health applications",
APL Bioengineering 9, 026103 (2025). https://doi.org/10.1063/5.0256590

Method (as described in the paper):
- Data source: SWELL Knowledge Work dataset (Koldijk et al., 2014), the
  HRV-feature-extracted version, which contains 34 HRV features per window
  (11 time-domain + 8 relative time-domain + 11 frequency-domain +
  4 nonlinear: SD1, SD2, SampEn, Higuchi).
- Target: 3-class stress state - 0 = no stress, 1 = moderate stress,
  2 = severe stress, derived from the SWELL experimental condition label
  (no stress / time pressure / interruption).
- Model: sklearn DecisionTreeClassifier
    max_depth = 10
    min_samples_split = 10
    min_samples_leaf = 5
- Evaluation: accuracy, precision, recall, F1 (per-class + macro), confusion matrix.

USAGE
-----
1. Download the SWELL HRV dataset (commonly distributed as a CSV with
   columns like MEAN_RR, MEDIAN_RR, SDRR, RMSSD, ..., HF_LF, sampen,
   higuci, datasetId, condition). A common source is the Kaggle dataset
   "SWELL Dataset for Stress Detection - HRV" or the original
   train.csv / test.csv from Radboud University's SWELL-KW release.

2. Point DATA_PATH (or pass --data) at the CSV file(s) once your
   download finishes. If the SWELL release you have is already split
   into separate train/test CSVs, pass both --train and --test instead
   of --data, and the script will honor that split directly (matching
   the paper's approach of using the pre-split train/test set).

3. Run:
       python swell_stress_model.py --data /path/to/swell_hrv.csv
   or
       python swell_stress_model.py --train /path/to/train.csv --test /path/to/test.csv

The script is defensive about column-name variants (different SWELL
distributions capitalize / abbreviate columns differently) and will
print out exactly what it matched, so you can sanity-check before trusting
the results.
"""

import argparse
import sys
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.tree import DecisionTreeClassifier
from sklearn.model_selection import train_test_split
from sklearn.metrics import (
    accuracy_score,
    precision_recall_fscore_support,
    classification_report,
    confusion_matrix,
)
import joblib

warnings.filterwarnings("ignore")

# ---------------------------------------------------------------------
# 1. Column-name resolution
# ---------------------------------------------------------------------
# Different distributions of the SWELL HRV feature set use slightly
# different column names. This map lists the accepted aliases for each
# of the 34 HRV features described in Table I of the paper, so the
# script can find them regardless of the exact CSV you downloaded.

FEATURE_ALIASES = {
    # --- time-domain (11) ---
    "MEAN_RR": ["MEAN_RR", "mean_rr", "MeanRR"],
    "MEDIAN_RR": ["MEDIAN_RR", "median_rr", "MedianRR"],
    "SDRR": ["SDRR", "SDNN", "sdrr", "sdnn"],
    "RMSSD": ["RMSSD", "rmssd"],
    "SDSD": ["SDSD", "sdsd"],
    "SDRR_RMSSD": ["SDRR_RMSSD", "SDNN_RMSSD", "sdrr_rmssd"],
    "HR": ["HR", "hr"],
    "pNN25": ["pNN25", "PNN25"],
    "pNN50": ["pNN50", "PNN50"],
    "KURT": ["KURT", "Kurt", "kurt", "kurtosis"],
    "SKEW": ["SKEW", "Skew", "skew", "skewness"],
    # --- relative time-domain (8) ---
    "MEAN_REL_RR": ["MEAN_REL_RR", "Rel_mean_RR"],
    "MEDIAN_REL_RR": ["MEDIAN_REL_RR", "Rel_median_RR"],
    "SDRR_REL_RR": ["SDRR_REL_RR", "Rel_SDNN"],
    "RMSSD_REL_RR": ["RMSSD_REL_RR", "Rel_RMSSD"],
    "SDSD_REL_RR": ["SDSD_REL_RR", "Rel_SDSD"],
    "SDRR_RMSSD_REL_RR": ["SDRR_RMSSD_REL_RR", "Rel_SDNN_RMSSD"],
    "KURT_REL_RR": ["KURT_REL_RR", "Rel_Kurt"],
    "SKEW_REL_RR": ["SKEW_REL_RR", "Rel_Skew"],
    # --- frequency-domain (11) ---
    "VLF": ["VLF", "vlf"],
    "VLF_PCT": ["VLF_PCT", "VLF_percentage", "vlf_pct"],
    "LF": ["LF", "lf"],
    "LF_PCT": ["LF_PCT", "LF_percentage", "lf_pct"],
    "LF_NU": ["LF_NU", "LFnu", "lf_nu"],
    "HF": ["HF", "hf"],
    "HF_PCT": ["HF_PCT", "HF_percentage", "hf_pct"],
    "HF_NU": ["HF_NU", "HFnu", "hf_nu"],
    "TP": ["TP", "Total_power", "TF", "tp"],
    "LF_HF": ["LF_HF", "LF/HF", "lf_hf"],
    "HF_LF": ["HF_LF", "HF/LF", "hf_lf"],
    # --- nonlinear (4) ---
    "SD1": ["SD1", "sd1"],
    "SD2": ["SD2", "sd2"],
    "sampen": ["sampen", "SampEn", "SAMPEN"],
    "higuci": ["higuci", "Higuchi", "HIGUCI", "higuchi"],
}

# label / grouping columns that are NOT features
LABEL_ALIASES = ["condition", "Condition", "class", "Class", "label", "Label"]
GROUP_ALIASES = ["datasetId", "subject", "Subject", "participant", "ID", "id"]

# Map SWELL experimental conditions -> 3-class stress label used in the paper
# no stress (0) / moderate stress i.e. time pressure (1) / severe stress i.e. interruption (2)
CONDITION_TO_STRESS = {
    "no stress": 0, "N": 0, "n": 0, "neutral": 0, "baseline": 0,
    "time pressure": 1, "T": 1, "t": 1,
    "interruption": 2, "I": 2, "i": 2,
}


def resolve_columns(df: pd.DataFrame):
    """Find which of our expected feature columns exist in df, and the label column."""
    found_features = {}
    for canonical, aliases in FEATURE_ALIASES.items():
        for a in aliases:
            if a in df.columns:
                found_features[canonical] = a
                break

    label_col = next((c for c in LABEL_ALIASES if c in df.columns), None)
    group_col = next((c for c in GROUP_ALIASES if c in df.columns), None)

    return found_features, label_col, group_col


def build_stress_labels(series: pd.Series) -> pd.Series:
    """Map raw SWELL condition strings to 0/1/2 stress classes."""
    mapped = series.astype(str).str.strip().map(CONDITION_TO_STRESS)
    if mapped.isna().any():
        unmapped = sorted(series[mapped.isna()].astype(str).unique())
        raise ValueError(
            f"Could not map these condition values to a stress class: {unmapped}\n"
            f"Add them to CONDITION_TO_STRESS in this script."
        )
    return mapped.astype(int)


# ---------------------------------------------------------------------
# 2. Training / evaluation
# ---------------------------------------------------------------------

def load_and_prepare(path: Path):
    df = pd.read_csv(path)
    features, label_col, group_col = resolve_columns(df)

    if not features:
        raise ValueError(
            f"None of the expected 34 HRV feature columns were found in {path}.\n"
            f"Columns present: {list(df.columns)}"
        )
    if label_col is None:
        raise ValueError(
            f"Could not find a condition/label column in {path}.\n"
            f"Columns present: {list(df.columns)}\n"
            f"Expected one of: {LABEL_ALIASES}"
        )

    print(f"[{path.name}] Matched {len(features)}/34 HRV features.")
    missing = set(FEATURE_ALIASES) - set(features)
    if missing:
        print(f"  Missing (will be excluded from model input): {sorted(missing)}")
    print(f"  Label column: '{label_col}'  |  Group column: '{group_col}'")

    X = df[[features[k] for k in features]].copy()
    X.columns = list(features.keys())
    X = X.apply(pd.to_numeric, errors="coerce")

    y = build_stress_labels(df[label_col])

    groups = df[group_col] if group_col else None

    # drop rows with missing feature values
    valid = X.notna().all(axis=1)
    n_dropped = (~valid).sum()
    if n_dropped:
        print(f"  Dropping {n_dropped} rows with missing feature values.")
    X, y = X[valid], y[valid]
    if groups is not None:
        groups = groups[valid]

    return X, y, groups


def train_and_evaluate(X_train, y_train, X_test, y_test, feature_names):
    clf = DecisionTreeClassifier(
        max_depth=10,
        min_samples_split=10,
        min_samples_leaf=5,
        random_state=42,
    )
    clf.fit(X_train, y_train)

    y_pred = clf.predict(X_test)

    acc = accuracy_score(y_test, y_pred)
    precision, recall, f1, support = precision_recall_fscore_support(
        y_test, y_pred, labels=[0, 1, 2], zero_division=0
    )

    class_names = ["No stress (0)", "Moderate stress (1)", "Severe stress (2)"]

    print("\n" + "=" * 60)
    print("HRV -> STRESS DECISION TREE - RESULTS")
    print("=" * 60)
    print(f"Overall accuracy: {acc:.4f}  ({acc*100:.2f}%)")
    print("\nPer-class metrics:")
    for name, p, r, f, s in zip(class_names, precision, recall, f1, support):
        print(f"  {name:22s}  precision={p:.4f}  recall={r:.4f}  f1={f:.4f}  n={s}")

    print("\nFull classification report:")
    print(classification_report(y_test, y_pred, target_names=class_names, zero_division=0))

    print("Confusion matrix (rows=true, cols=predicted):")
    cm = confusion_matrix(y_test, y_pred, labels=[0, 1, 2])
    print(pd.DataFrame(cm, index=class_names, columns=class_names))

    print("\nTop feature importances:")
    importances = pd.Series(clf.feature_importances_, index=feature_names)
    print(importances.sort_values(ascending=False).head(15).to_string())

    return clf


def main():
    ap = argparse.ArgumentParser(description="Train the HRV-stress decision tree model on the SWELL dataset.")
    ap.add_argument("--data", type=str, help="Single CSV; will be split into train/test (80/20, stratified).")
    ap.add_argument("--train", type=str, help="Pre-split training CSV (paper uses SWELL's own train/test split).")
    ap.add_argument("--test", type=str, help="Pre-split test CSV.")
    ap.add_argument("--test-size", type=float, default=0.2, help="Test fraction if --data is used (default 0.2).")
    ap.add_argument("--out", type=str, default="/mnt/user-data/outputs/hrv_stress_decision_tree.joblib",
                     help="Where to save the trained model.")
    args = ap.parse_args()

    if args.train and args.test:
        X_train, y_train, _ = load_and_prepare(Path(args.train))
        X_test, y_test, _ = load_and_prepare(Path(args.test))
        # align columns in case one file has more features than the other
        common = [c for c in X_train.columns if c in X_test.columns]
        X_train, X_test = X_train[common], X_test[common]
    elif args.data:
        X, y, groups = load_and_prepare(Path(args.data))
        X_train, X_test, y_train, y_test = train_test_split(
            X, y, test_size=args.test_size, random_state=42, stratify=y
        )
    else:
        print(__doc__)
        print("\nERROR: provide either --data path.csv, or both --train and --test.")
        sys.exit(1)

    print(f"\nTrain set: {X_train.shape[0]} rows, {X_train.shape[1]} features")
    print(f"Test set:  {X_test.shape[0]} rows, {X_test.shape[1]} features")
    print("Class balance (train):")
    print(y_train.value_counts().sort_index().rename({0: "no stress", 1: "moderate", 2: "severe"}))

    clf = train_and_evaluate(X_train, y_train, X_test, y_test, list(X_train.columns))

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    joblib.dump({"model": clf, "feature_order": list(X_train.columns)}, out_path)
    print(f"\nSaved trained model to: {out_path}")


if __name__ == "__main__":
    main()

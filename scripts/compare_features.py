"""Compare Java in-game feature values with Python pipeline features.

Reads the FeatureLogger CSV (Java-side features logged during inference) and
the matching ticks.csv from the Python pipeline, aligns by (round, tick), and
reports per-feature divergence statistics.

Usage:
    python scripts/compare_features.py <feature_logger_csv> <ticks_csv_dir>

Arguments:
    feature_logger_csv  Path to features_fire_power.csv from FeatureLogger
    ticks_csv_dir       Directory containing ticks.csv (pipeline output)
                        Can be a battle dir like output/csv/<battle>/<robot>/

Outputs:
    Per-feature Pearson correlation, MAE, max absolute error, and % divergent.
    Features sorted by correlation ascending (worst first).
    Top 10 most divergent features flagged with suggested root causes.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd


# The 23 features that were NaN before Sprint 10 (MlDerivedFeatures).
# These deserve special attention in the comparison.
ML_DERIVED_FEATURES = {
    'energy_ratio', 'our_lateral_velocity', 'our_dist_to_wall_min',
    'opponent_center_distance', 'opponent_corner_proximity',
    'opponent_angular_velocity', 'opponent_max_turn_rate',
    'distance_norm', 'opponent_lateral_direction', 'opponent_velocity_delta',
    'opponent_is_decelerating', 'opponent_time_since_direction_change',
    'our_bullet_speed', 'our_bullet_travel_time', 'mea_for_our_bullet',
    'ticks_since_we_fired', 'our_wave_distance', 'our_wave_remaining',
    'opponent_wall_ahead_distance', 'opponent_avg_lateral_velocity_10',
    'opponent_avg_lateral_velocity_30', 'opponent_heading_delta_variability_10',
    'opponent_velocity_variability_10', 'opponent_time_since_velocity_change',
    'opponent_distance_since_direction_change',
}

# Window-derived features (20-tick sliding window)
WINDOW_FEATURES = {
    'distance_wmean', 'distance_wstd',
    'bearing_to_opponent_abs_wmean', 'bearing_to_opponent_abs_wstd',
    'opponent_dist_to_wall_min_wmean', 'opponent_dist_to_wall_min_wstd',
    'our_gun_heat_wmean', 'our_gun_heat_wstd',
    'ticks_since_scan_wmean', 'ticks_since_scan_wstd',
    'opponent_energy_wmean', 'opponent_energy_wstd',
    'our_x_wmean', 'our_x_wstd',
    'our_y_wmean', 'our_y_wstd',
    'our_heading_wmean', 'our_heading_wstd',
    'our_velocity_wmean', 'our_velocity_wstd',
}

# Known root-cause categories for divergence diagnosis
DIVERGENCE_CAUSES = {
    'window': 'Sliding window computation mismatch (round boundaries, warm-up, Bessel correction)',
    'scan': 'Scan timing: Java updates on scan event, Python may have different stale-value behavior',
    'cumulative': 'Cumulative counter reset at round boundary may differ',
    'derived': 'Derived from other features — check upstream feature first',
    'angle': 'Angle normalization or coordinate system difference',
    'wave': 'Wave tracking state machine differs between Java and Python',
    'ml_derived': 'Recently added in Sprint 10 — verify computation matches Python pipeline',
}

FEATURE_CAUSE_MAP = {
    **{f: 'window' for f in WINDOW_FEATURES},
    'cumulative_damage_dealt': 'cumulative',
    'cumulative_damage_received': 'cumulative',
    'cumulative_our_hit_rate': 'cumulative',
    'cumulative_opponent_hit_rate': 'cumulative',
    'cumulative_our_shots_fired': 'cumulative',
    'cumulative_opponent_shots_detected': 'cumulative',
    'bearing_to_opponent_abs': 'angle',
    'our_heading': 'angle',
    'opponent_heading': 'angle',
    'opponent_heading_delta': 'angle',
    'linear_target_angle': 'angle',
    'linear_target_offset': 'angle',
    'circular_target_angle': 'angle',
    'circular_target_offset': 'angle',
    'gf_bearing_offset': 'angle',
    'opponent_guess_factor': 'angle',
    'ticks_since_we_fired': 'wave',
    'our_wave_distance': 'wave',
    'our_wave_remaining': 'wave',
    'n_our_waves_in_flight': 'wave',
    'nearest_our_wave_gap': 'wave',
    'our_bullet_speed': 'wave',
    'our_bullet_travel_time': 'wave',
    'mea_for_our_bullet': 'wave',
    'envelope_fill_ratio': 'derived',
    'reachable_distance_min': 'derived',
    'reachable_distance_max': 'derived',
    'reachable_gf_range': 'derived',
    **{f: 'ml_derived' for f in ML_DERIVED_FEATURES if f not in WINDOW_FEATURES},
    'ticks_since_scan': 'scan',
}


def load_feature_logger_csv(path: Path) -> pd.DataFrame:
    """Load the FeatureLogger CSV (Java-side)."""
    df = pd.read_csv(path)
    print(f"Java FeatureLogger: {len(df)} rows x {df.shape[1]} cols")
    print(f"  Columns: round, tick, {df.shape[1] - 4} features, predicted, actual")

    feature_cols = [c for c in df.columns if c not in ('round', 'tick', 'predicted', 'actual')]
    nan_counts = df[feature_cols].isna().sum()
    nan_features = nan_counts[nan_counts > 0]
    if len(nan_features) > 0:
        print(f"\n  WARNING: {len(nan_features)} features have NaN values in Java output:")
        for feat, count in nan_features.items():
            pct = 100.0 * count / len(df)
            marker = " [MlDerived]" if feat in ML_DERIVED_FEATURES else ""
            print(f"    {feat}: {count}/{len(df)} ({pct:.1f}%){marker}")
    else:
        print(f"  OK: No NaN features (Sprint 10 fix verified)")

    return df


def load_pipeline_ticks(ticks_dir: Path) -> pd.DataFrame:
    """Load the pipeline ticks.csv."""
    ticks_path = ticks_dir / 'ticks.csv' if ticks_dir.is_dir() else ticks_dir
    if not ticks_path.exists():
        candidates = list(Path(ticks_dir).rglob('ticks.csv'))
        if candidates:
            ticks_path = candidates[0]
            print(f"Found ticks.csv at: {ticks_path}")
        else:
            print(f"ERROR: No ticks.csv found in {ticks_dir}")
            sys.exit(1)

    df = pd.read_csv(ticks_path)
    print(f"Pipeline ticks.csv: {len(df)} rows x {df.shape[1]} cols")
    return df


def compute_divergence(java_vals: np.ndarray, python_vals: np.ndarray):
    """Compute divergence metrics between Java and Python feature values."""
    valid = np.isfinite(java_vals) & np.isfinite(python_vals)
    n_valid = int(valid.sum())

    if n_valid < 10:
        return {
            'corr': np.nan, 'mae': np.nan, 'max_err': np.nan,
            'pct_divergent': np.nan, 'n_valid': n_valid,
            'java_nan': int(np.isnan(java_vals).sum()),
            'python_nan': int(np.isnan(python_vals).sum()),
        }

    j = java_vals[valid]
    p = python_vals[valid]

    if np.std(j) < 1e-10 and np.std(p) < 1e-10:
        corr = 1.0 if np.allclose(j, p) else 0.0
    elif np.std(j) < 1e-10 or np.std(p) < 1e-10:
        corr = 0.0
    else:
        corr = float(np.corrcoef(j, p)[0, 1])

    abs_diff = np.abs(j - p)
    mae = float(np.mean(abs_diff))
    max_err = float(np.max(abs_diff))

    denom = np.maximum(np.abs(p), 0.1)
    rel_diff = abs_diff / denom
    pct_divergent = 100.0 * np.mean(rel_diff > 0.10)

    return {
        'corr': corr, 'mae': mae, 'max_err': max_err,
        'pct_divergent': pct_divergent, 'n_valid': n_valid,
        'java_nan': int(np.isnan(java_vals).sum()),
        'python_nan': int(np.isnan(python_vals).sum()),
    }


def compare(java_csv: str, ticks_dir: str):
    java_path = Path(java_csv)
    ticks_path = Path(ticks_dir)

    if not java_path.exists():
        print(f"ERROR: {java_path} not found")
        sys.exit(1)

    java_df = load_feature_logger_csv(java_path)
    pipeline_df = load_pipeline_ticks(ticks_path)

    if 'round' not in java_df.columns or 'tick' not in java_df.columns:
        print("ERROR: Java CSV must have 'round' and 'tick' columns")
        sys.exit(1)
    if 'round' not in pipeline_df.columns or 'tick' not in pipeline_df.columns:
        if 'round_num' in pipeline_df.columns:
            pipeline_df = pipeline_df.rename(columns={'round_num': 'round'})
        else:
            print("ERROR: Pipeline CSV must have 'round' and 'tick' columns")
            sys.exit(1)

    java_feature_cols = [c for c in java_df.columns
                         if c not in ('round', 'tick', 'predicted', 'actual')]

    merged = java_df.merge(pipeline_df, on=['round', 'tick'],
                           suffixes=('_java', '_pipeline'), how='inner')
    print(f"\nAligned rows (round, tick): {len(merged)}")
    if len(merged) == 0:
        print("ERROR: No matching (round, tick) pairs found!")
        print(f"  Java rounds: {sorted(java_df['round'].unique())}")
        print(f"  Pipeline rounds: {sorted(pipeline_df['round'].unique())}")
        print(f"  Java tick range: {java_df['tick'].min()}-{java_df['tick'].max()}")
        print(f"  Pipeline tick range: {pipeline_df['tick'].min()}-{pipeline_df['tick'].max()}")
        sys.exit(1)

    results = []
    for feat in java_feature_cols:
        java_col = feat + '_java' if feat + '_java' in merged.columns else feat
        pipeline_col = feat + '_pipeline' if feat + '_pipeline' in merged.columns else feat

        if java_col not in merged.columns:
            results.append({
                'feature': feat, 'corr': np.nan, 'mae': np.nan,
                'max_err': np.nan, 'pct_divergent': np.nan,
                'n_valid': 0, 'java_nan': 0, 'python_nan': 0,
                'status': 'JAVA_ONLY',
            })
            continue

        if pipeline_col not in merged.columns:
            if feat in merged.columns:
                pipeline_col = feat
            else:
                results.append({
                    'feature': feat, 'corr': np.nan, 'mae': np.nan,
                    'max_err': np.nan, 'pct_divergent': np.nan,
                    'n_valid': 0, 'java_nan': 0, 'python_nan': 0,
                    'status': 'PIPELINE_MISSING',
                })
                continue

        j_vals = merged[java_col].values.astype(float)
        p_vals = merged[pipeline_col].values.astype(float)
        metrics = compute_divergence(j_vals, p_vals)
        metrics['feature'] = feat

        if np.isnan(metrics['corr']):
            metrics['status'] = 'NO_DATA'
        elif metrics['corr'] >= 0.99 and metrics['pct_divergent'] < 5:
            metrics['status'] = 'OK'
        elif metrics['corr'] >= 0.95:
            metrics['status'] = 'MINOR'
        elif metrics['corr'] >= 0.80:
            metrics['status'] = 'DIVERGENT'
        else:
            metrics['status'] = 'BROKEN'

        results.append(metrics)

    results.sort(key=lambda x: x['corr'] if np.isfinite(x['corr']) else -999)

    # Full report
    print(f"\n{'='*110}")
    print(f"{'Feature':<45} {'Corr':>8} {'MAE':>10} {'MaxErr':>10} {'%Div>10%':>8} {'N':>6} {'Status':>10}")
    print(f"{'='*110}")

    broken, divergent, minor, ok = [], [], [], []

    for r in results:
        feat = r['feature']
        corr = r['corr']
        mae = r['mae']
        max_err = r['max_err']
        pct_div = r['pct_divergent']
        n = r['n_valid']
        status = r['status']

        cat = ''
        if feat in ML_DERIVED_FEATURES:
            cat = ' [ML]'
        elif feat in WINDOW_FEATURES:
            cat = ' [W]'

        if status in ('NO_DATA', 'JAVA_ONLY', 'PIPELINE_MISSING'):
            print(f"{feat + cat:<45} {'---':>8} {'---':>10} {'---':>10} {'---':>8} {n:>6} {status:>10}")
        else:
            flag = ' ***' if status in ('BROKEN', 'DIVERGENT') else ''
            print(f"{feat + cat:<45} {corr:>8.4f} {mae:>10.4f} {max_err:>10.4f} "
                  f"{pct_div:>7.1f}% {n:>6} {status:>10}{flag}")

        if status == 'BROKEN':
            broken.append(r)
        elif status == 'DIVERGENT':
            divergent.append(r)
        elif status == 'MINOR':
            minor.append(r)
        elif status == 'OK':
            ok.append(r)

    # Summary
    print(f"\n{'='*110}")
    print(f"SUMMARY: {len(ok)} OK, {len(minor)} minor, "
          f"{len(divergent)} divergent, {len(broken)} broken")
    print(f"{'='*110}")

    # Top 10 most divergent with root cause suggestions
    worst = (broken + divergent)[:10]
    if worst:
        print(f"\nTOP {len(worst)} MOST DIVERGENT FEATURES — Suggested Root Causes:")
        print(f"{'-'*110}")
        for i, r in enumerate(worst, 1):
            feat = r['feature']
            cause_key = FEATURE_CAUSE_MAP.get(feat, 'unknown')
            cause_desc = DIVERGENCE_CAUSES.get(cause_key,
                'Unknown — investigate Java vs Python computation')
            cat = 'MlDerived' if feat in ML_DERIVED_FEATURES else \
                  'Window' if feat in WINDOW_FEATURES else 'Core'
            print(f"  {i:>2}. {feat}")
            print(f"      Category: {cat}  |  corr={r['corr']:.4f}  "
                  f"MAE={r['mae']:.4f}  %div={r['pct_divergent']:.1f}%")
            print(f"      Likely cause: {cause_desc}")
            print()

    # MlDerived features section
    ml_results = [r for r in results if r['feature'] in ML_DERIVED_FEATURES]
    if ml_results:
        print(f"\n{'='*110}")
        print(f"ML-DERIVED FEATURES (Sprint 10 additions) — {len(ml_results)} features")
        print(f"{'='*110}")
        ml_ok = sum(1 for r in ml_results if r['status'] == 'OK')
        ml_broken = sum(1 for r in ml_results if r['status'] in ('BROKEN', 'DIVERGENT'))
        print(f"  OK: {ml_ok}  |  Divergent/Broken: {ml_broken}")
        for r in ml_results:
            feat = r['feature']
            if r['status'] in ('NO_DATA', 'PIPELINE_MISSING'):
                print(f"  {feat:<45} {r['status']}")
            else:
                print(f"  {feat:<45} corr={r['corr']:.4f}  "
                      f"MAE={r['mae']:.4f}  status={r['status']}")

    # Window features section
    win_results = [r for r in results if r['feature'] in WINDOW_FEATURES]
    if win_results:
        print(f"\n{'='*110}")
        print(f"WINDOW FEATURES (20-tick sliding) — {len(win_results)} features")
        print(f"{'='*110}")
        win_ok = sum(1 for r in win_results if r['status'] == 'OK')
        win_broken = sum(1 for r in win_results if r['status'] in ('BROKEN', 'DIVERGENT'))
        print(f"  OK: {win_ok}  |  Divergent/Broken: {win_broken}")
        for r in win_results:
            feat = r['feature']
            if r['status'] in ('NO_DATA', 'PIPELINE_MISSING'):
                print(f"  {feat:<45} {r['status']}")
            else:
                print(f"  {feat:<45} corr={r['corr']:.4f}  "
                      f"MAE={r['mae']:.4f}  status={r['status']}")

    # Prediction quality
    if 'predicted_java' in merged.columns and 'actual_java' in merged.columns:
        pred = merged['predicted_java'].values
        actual = merged['actual_java'].values
        valid = np.isfinite(pred) & np.isfinite(actual)
        if valid.sum() > 10:
            p, a = pred[valid], actual[valid]
            ss_res = np.sum((a - p) ** 2)
            ss_tot = np.sum((a - np.mean(a)) ** 2)
            r2 = 1 - ss_res / ss_tot if ss_tot > 0 else 0
            print(f"\n{'='*110}")
            print(f"IN-GAME PREDICTION QUALITY")
            print(f"  R-squared (predicted vs actual): {r2:.4f}")
            print(f"  MAE: {np.mean(np.abs(p - a)):.4f}")
            print(f"  Predictions: {valid.sum()} valid / {len(pred)} total")
            pred_std = np.std(p)
            print(f"  Prediction std: {pred_std:.4f}")
            if pred_std < 0.1:
                print(f"  WARNING: Prediction variance collapsed (std={pred_std:.4f})!")
                print(f"  Model is hedging to base score — feature ordering mismatch?")
            if r2 < 0:
                print(f"  WARNING: Negative R-squared — model predictions worse than mean baseline!")
                print(f"  Feature divergence is the likely cause.")

    # ── CHECK: Feature order vs trained model ────────────────────────
    # This catches the Sprint 24 root cause: FEATURE_NAMES in Java was in
    # a different order than the model's training columns. The tree splits
    # reference features by INDEX, so wrong order = wrong feature at each
    # split = model produces garbage.
    _check_feature_order(java_feature_cols)

    # ── CHECK: Cross-predict with Python model ───────────────────────
    # Load the XGBoost model and predict on the Java feature vectors
    # (in FEATURE_NAMES order). If Java's FEATURE_NAMES is scrambled,
    # the Python model will produce different predictions than Java's
    # embedded model — even though the individual feature VALUES are correct.
    _cross_predict(merged, java_feature_cols)


def _check_feature_order(java_feature_cols: list[str]):
    """Verify FEATURE_NAMES order matches feature_cols.json from training."""
    print(f"\n{'='*110}")
    print("FEATURE ORDER CHECK (Sprint 24 safeguard)")

    # Try to find feature_cols.json
    candidates = [
        Path(__file__).parent.parent / 'intuition' / 'models' / 'distill' / 'fire_power' / 'feature_cols.json',
        Path('intuition/models/distill/fire_power/feature_cols.json'),
        Path('../intuition/models/distill/fire_power/feature_cols.json'),
    ]

    import json
    fc_path = None
    for c in candidates:
        if c.exists():
            fc_path = c
            break

    if fc_path is None:
        print("  SKIP: feature_cols.json not found (train models first)")
        return

    with open(fc_path) as f:
        trained_cols = json.load(f)

    java_set = set(java_feature_cols)
    trained_set = set(trained_cols)

    if java_set != trained_set:
        missing_in_java = trained_set - java_set
        extra_in_java = java_set - trained_set
        print(f"  FAIL: Feature sets differ!")
        if missing_in_java:
            print(f"    In model but not Java: {missing_in_java}")
        if extra_in_java:
            print(f"    In Java but not model: {extra_in_java}")
        return

    # Check order
    mismatches = 0
    for i, (j, t) in enumerate(zip(java_feature_cols, trained_cols)):
        if j != t:
            mismatches += 1
            if mismatches <= 5:
                print(f"  Index {i}: Java='{j}' vs Model='{t}'")

    if mismatches > 0:
        print(f"  FAIL: {mismatches}/{len(java_feature_cols)} features in WRONG ORDER!")
        print(f"  This means every tree split reads the wrong feature value.")
        print(f"  Fix: re-export with python export_gbm_java.py --all")
    else:
        print(f"  OK: all {len(java_feature_cols)} features in correct order")


def _cross_predict(merged: pd.DataFrame, java_feature_cols: list[str]):
    """Load XGBoost model, predict on Java features, compare vs Java predictions."""
    print(f"\n{'='*110}")
    print("CROSS-PREDICTION CHECK (Python model on Java feature vectors)")

    # Find model
    candidates = [
        Path(__file__).parent.parent / 'intuition' / 'models' / 'distill' / 'fire_power' / 'model.json',
        Path('intuition/models/distill/fire_power/model.json'),
        Path('../intuition/models/distill/fire_power/model.json'),
    ]

    model_path = None
    for c in candidates:
        if c.exists():
            model_path = c
            break

    if model_path is None:
        print("  SKIP: model.json not found (train models first)")
        return

    try:
        import xgboost as xgb
    except ImportError:
        print("  SKIP: xgboost not installed")
        return

    import json
    # Load feature_cols.json to know correct feature order for Python model
    fc_path = model_path.parent / 'feature_cols.json'
    if not fc_path.exists():
        print("  SKIP: feature_cols.json not found")
        return

    with open(fc_path) as f:
        model_cols = json.load(f)

    model = xgb.XGBRegressor()
    model.load_model(str(model_path))

    # Build feature matrix from Java CSV values in the MODEL's column order
    # Use the Java-side values (suffix _java in merged) or plain column name
    X_cols = []
    missing = []
    for col in model_cols:
        java_col = col + '_java'
        if java_col in merged.columns:
            X_cols.append(java_col)
        elif col in merged.columns:
            X_cols.append(col)
        else:
            missing.append(col)
            X_cols.append(None)

    if missing:
        print(f"  WARN: {len(missing)} model features not in Java CSV: {missing[:5]}")
        print("  Cannot run cross-prediction with missing features")
        return

    X = merged[X_cols].values.astype(np.float32)
    valid = np.all(np.isfinite(X), axis=1)

    if valid.sum() < 10:
        print(f"  SKIP: only {valid.sum()} valid rows")
        return

    py_pred = model.predict(X[valid])
    py_pred = np.clip(py_pred, 0.1, 3.0)

    java_pred_col = 'predicted_java' if 'predicted_java' in merged.columns else 'predicted'
    if java_pred_col not in merged.columns:
        print("  SKIP: no Java prediction column found")
        return

    java_pred = merged.loc[valid, java_pred_col].values.astype(np.float64)
    valid2 = np.isfinite(java_pred)
    py_pred, java_pred = py_pred[valid2], java_pred[valid2]

    if len(py_pred) < 10:
        print(f"  SKIP: only {len(py_pred)} comparable predictions")
        return

    mae = np.mean(np.abs(py_pred - java_pred))
    corr = np.corrcoef(py_pred, java_pred)[0, 1] if np.std(py_pred) > 1e-10 else 0.0
    max_err = np.max(np.abs(py_pred - java_pred))

    print(f"  Samples: {len(py_pred)}")
    print(f"  Python vs Java prediction correlation: {corr:.4f}")
    print(f"  Python vs Java prediction MAE: {mae:.4f}")
    print(f"  Max error: {max_err:.4f}")

    if corr < 0.95:
        print(f"  FAIL: Predictions diverge (corr={corr:.4f} < 0.95)!")
        print(f"  This means Java's FEATURE_NAMES order doesn't match the model.")
        print(f"  Fix: re-export with python export_gbm_java.py --all")
    elif mae > 0.1:
        print(f"  WARN: Predictions weakly correlated but MAE={mae:.4f} > 0.1")
        print(f"  Possible numerical precision issue or partial feature mismatch.")
    else:
        print(f"  OK: Java and Python predictions match (corr={corr:.4f}, MAE={mae:.4f})")


if __name__ == '__main__':
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    compare(sys.argv[1], sys.argv[2])

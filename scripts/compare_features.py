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
            if r2 < 0:
                print(f"  WARNING: Negative R-squared — model predictions worse than mean baseline!")
                print(f"  Feature divergence is the likely cause.")


if __name__ == '__main__':
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    compare(sys.argv[1], sys.argv[2])

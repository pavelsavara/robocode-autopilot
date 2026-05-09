"""Compare Java in-game feature values with Python pipeline features.

Reads the Java debug log (internal.csv from a battle) and recomputes the same
features using the Python pipeline logic on the same tick data (ticks.csv).
Reports per-feature correlation and divergence.

Usage:
    cd scripts
    python compare_features.py <battle_dir>

Where <battle_dir> contains:
    internal.csv  — Java robot's per-tick feature log
    ticks.csv     — pipeline-recorded tick features

Outputs:
    Per-feature Pearson correlation, MAE, and max absolute error.
    Features with correlation < 0.95 are flagged as divergent.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd


WINDOW_SIZE = 20
MIN_TICKS = 5

WINDOW_BASE_FEATURES = [
    'distance', 'bearing_to_opponent_abs', 'opponent_dist_to_wall_min',
    'our_gun_heat', 'ticks_since_scan', 'opponent_energy',
    'our_x', 'our_y', 'our_heading', 'our_velocity',
]


def add_window_features_python(df: pd.DataFrame) -> pd.DataFrame:
    """Recompute window features the way train_distill.py does."""
    df = df.sort_values('tick').copy()
    for col in WINDOW_BASE_FEATURES:
        if col not in df.columns:
            continue
        wmean = col + '_wmean'
        wstd = col + '_wstd'
        df[wmean] = df[col].rolling(WINDOW_SIZE, min_periods=MIN_TICKS).mean()
        df[wstd] = df[col].rolling(WINDOW_SIZE, min_periods=MIN_TICKS).std()
    return df


def compare(battle_dir: str):
    bd = Path(battle_dir)

    # Try to find internal.csv and ticks.csv
    internal_path = bd / 'internal.csv'
    ticks_path = bd / 'ticks.csv'

    if not internal_path.exists():
        print(f"ERROR: {internal_path} not found")
        print("The Java robot must write per-tick feature values to internal.csv")
        print("(Enable DATA_SAVE logging in Autopilot.java)")
        sys.exit(1)

    if not ticks_path.exists():
        print(f"ERROR: {ticks_path} not found")
        sys.exit(1)

    java_df = pd.read_csv(internal_path)
    pipeline_df = pd.read_csv(ticks_path)

    print(f"Java internal.csv: {len(java_df)} rows x {java_df.shape[1]} cols")
    print(f"Pipeline ticks.csv: {len(pipeline_df)} rows x {pipeline_df.shape[1]} cols")

    # Recompute window features on pipeline data
    pipeline_df = add_window_features_python(pipeline_df)

    # Align by tick number
    if 'tick' not in java_df.columns or 'tick' not in pipeline_df.columns:
        print("ERROR: Both CSVs must have a 'tick' column")
        sys.exit(1)

    merged = java_df.merge(pipeline_df, on='tick', suffixes=('_java', '_pipeline'),
                           how='inner')
    print(f"Aligned rows: {len(merged)}")

    if len(merged) == 0:
        print("ERROR: No matching ticks found")
        sys.exit(1)

    # Find common feature columns
    java_cols = {c.replace('_java', '') for c in merged.columns if c.endswith('_java')}
    pipeline_cols = {c.replace('_pipeline', '') for c in merged.columns if c.endswith('_pipeline')}
    common = sorted(java_cols & pipeline_cols)

    print(f"\nCommon features: {len(common)}")
    print(f"Java-only features: {len(java_cols - pipeline_cols)}")
    print(f"Pipeline-only features: {len(pipeline_cols - java_cols)}")

    # Compute per-feature correlation
    results = []
    for feat in common:
        j = merged[feat + '_java'].values.astype(float)
        p = merged[feat + '_pipeline'].values.astype(float)

        # Skip if all NaN
        valid = np.isfinite(j) & np.isfinite(p)
        if valid.sum() < 10:
            results.append((feat, np.nan, np.nan, np.nan, valid.sum()))
            continue

        j_v = j[valid]
        p_v = p[valid]

        # Pearson correlation
        if np.std(j_v) < 1e-10 or np.std(p_v) < 1e-10:
            corr = 1.0 if np.allclose(j_v, p_v) else 0.0
        else:
            corr = float(np.corrcoef(j_v, p_v)[0, 1])

        mae = float(np.mean(np.abs(j_v - p_v)))
        max_err = float(np.max(np.abs(j_v - p_v)))
        results.append((feat, corr, mae, max_err, int(valid.sum())))

    # Sort by correlation (worst first)
    results.sort(key=lambda x: x[1] if np.isfinite(x[1]) else -999)

    # Report
    print(f"\n{'Feature':<45} {'Corr':>8} {'MAE':>12} {'MaxErr':>12} {'N':>6}")
    print('-' * 85)

    divergent = []
    for feat, corr, mae, max_err, n in results:
        flag = ' ***' if (np.isfinite(corr) and corr < 0.95) else ''
        if np.isnan(corr):
            print(f"{feat:<45} {'NaN':>8} {'NaN':>12} {'NaN':>12} {n:>6}  (too few valid)")
        else:
            print(f"{feat:<45} {corr:>8.4f} {mae:>12.4f} {max_err:>12.4f} {n:>6}{flag}")
        if np.isfinite(corr) and corr < 0.95:
            divergent.append(feat)

    print(f"\n=== DIVERGENT FEATURES (correlation < 0.95): {len(divergent)} ===")
    for feat in divergent:
        print(f"  - {feat}")

    if not divergent:
        print("  (none found — feature parity looks good)")

    # Check window features specifically
    print("\n=== WINDOW FEATURE DETAIL ===")
    for base in WINDOW_BASE_FEATURES:
        for suffix in ['_wmean', '_wstd']:
            feat = base + suffix
            match = [r for r in results if r[0] == feat]
            if match:
                _, corr, mae, _, n = match[0]
                status = 'OK' if (np.isfinite(corr) and corr >= 0.95) else 'MISMATCH'
                if np.isnan(corr):
                    status = 'NO_DATA'
                print(f"  {feat:<40} corr={corr:.4f}  mae={mae:.4f}  [{status}]"
                      if np.isfinite(corr) else f"  {feat:<40} NO DATA")
            else:
                print(f"  {feat:<40} NOT FOUND in both datasets")


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    compare(sys.argv[1])

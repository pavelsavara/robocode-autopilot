"""Compare Java FeatureLogger output with Python pipeline ticks.csv.

The Java FeatureLogger writes features_fire_power_diagnostic.csv with:
  round, tick, [80 features in FirePowerData.FEATURE_NAMES order], predicted, actual

The pipeline writes ticks.csv with named columns.

This script aligns both by (round, tick) and computes per-feature correlation and MAE.
"""
from __future__ import annotations
import sys
import numpy as np
import pandas as pd
from pathlib import Path

# 80 features in the exact order Java uses them (from FirePowerData.FEATURE_NAMES)
FEATURE_NAMES = [
    "opponent_energy", "our_gun_heat", "ticks_since_scan",
    "cumulative_damage_dealt", "cumulative_damage_received",
    "cumulative_our_hit_rate", "cumulative_opponent_hit_rate",
    "cumulative_our_shots_fired", "cumulative_opponent_shots_detected",
    "opponent_center_distance", "opponent_corner_proximity", "distance",
    "bearing_to_opponent_abs", "opponent_dist_to_wall_min",
    "our_x", "our_y", "our_heading", "our_velocity",
    "opponent_x", "opponent_y", "opponent_heading",
    "opponent_wall_ahead_distance", "opponent_velocity",
    "opponent_lateral_velocity", "opponent_advancing_velocity",
    "opponent_heading_delta", "energy_ratio",
    "our_lateral_velocity", "our_dist_to_wall_min",
    "our_bullet_speed", "our_bullet_travel_time", "mea_for_our_bullet",
    "ticks_since_we_fired", "our_wave_distance", "our_wave_remaining",
    "n_our_waves_in_flight", "nearest_our_wave_gap",
    "envelope_fill_ratio", "reachable_distance_min", "reachable_distance_max",
    "reachable_gf_range", "opponent_lateral_direction",
    "opponent_velocity_delta", "opponent_is_decelerating",
    "opponent_time_since_direction_change", "opponent_angular_velocity",
    "opponent_max_turn_rate", "distance_norm",
    "linear_target_angle", "linear_target_offset",
    "circular_target_angle", "circular_target_offset",
    "gf_bearing_offset", "opponent_guess_factor",
    "opponent_avg_lateral_velocity_10", "opponent_avg_lateral_velocity_30",
    "opponent_heading_delta_variability_10", "opponent_velocity_variability_10",
    "opponent_time_since_velocity_change", "opponent_distance_since_direction_change",
    "distance_wmean", "distance_wstd",
    "bearing_to_opponent_abs_wmean", "bearing_to_opponent_abs_wstd",
    "opponent_dist_to_wall_min_wmean", "opponent_dist_to_wall_min_wstd",
    "our_gun_heat_wmean", "our_gun_heat_wstd",
    "ticks_since_scan_wmean", "ticks_since_scan_wstd",
    "opponent_energy_wmean", "opponent_energy_wstd",
    "our_x_wmean", "our_x_wstd",
    "our_y_wmean", "our_y_wstd",
    "our_heading_wmean", "our_heading_wstd",
    "our_velocity_wmean", "our_velocity_wstd",
]

WINDOW_SIZE = 20
MIN_TICKS = 5
WINDOW_BASE = [
    'distance', 'bearing_to_opponent_abs', 'opponent_dist_to_wall_min',
    'our_gun_heat', 'ticks_since_scan', 'opponent_energy',
    'our_x', 'our_y', 'our_heading', 'our_velocity',
]


def add_window_features(df: pd.DataFrame) -> pd.DataFrame:
    """Add window features to ticks.csv matching the Java computation."""
    df = df.sort_values(['round', 'tick']).copy()
    for col in WINDOW_BASE:
        if col not in df.columns:
            continue
        wmean = col + '_wmean'
        wstd = col + '_wstd'
        # Java resets window at round boundaries — group by round
        df[wmean] = df.groupby('round')[col].transform(
            lambda s: s.rolling(WINDOW_SIZE, min_periods=MIN_TICKS).mean()
        )
        df[wstd] = df.groupby('round')[col].transform(
            lambda s: s.rolling(WINDOW_SIZE, min_periods=MIN_TICKS).std()
        )
    return df


def main():
    java_csv = Path(r"d:\robocode-autopilot\output\local\features_fire_power_diagnostic.csv")

    # Find the most recent FloatingTadpole battle
    csv_root = Path(r"d:\robocode-autopilot\output\local\csv")
    ft_dirs = sorted(
        csv_root.rglob("FloatingTadpole*"),
        key=lambda p: p.stat().st_mtime,
        reverse=True
    )
    if not ft_dirs:
        print("ERROR: No FloatingTadpole battle directories found")
        sys.exit(1)

    # Use most recent
    ft_dir = ft_dirs[0]
    battle_dir = ft_dir.parent
    auto_dirs = list(battle_dir.glob("Autopilot*"))
    if not auto_dirs:
        print(f"ERROR: No Autopilot directory in {battle_dir}")
        sys.exit(1)

    ticks_path = auto_dirs[0] / "ticks.csv"
    if not ticks_path.exists():
        print(f"ERROR: {ticks_path} not found")
        sys.exit(1)

    print(f"Java feature logger: {java_csv}")
    print(f"Pipeline ticks.csv:  {ticks_path}")
    print(f"Battle: {battle_dir.name}")
    print()

    # Load Java feature logger CSV — already has named columns
    java_df = pd.read_csv(java_csv)
    print(f"Java CSV: {len(java_df)} rows, columns: round, tick, {len(FEATURE_NAMES)} features + predicted, actual")

    # Load pipeline ticks.csv
    pipeline_df = pd.read_csv(ticks_path)
    print(f"Pipeline CSV: {len(pipeline_df)} rows x {pipeline_df.shape[1]} cols")

    # Add window features to pipeline data (recompute from base features)
    pipeline_df = add_window_features(pipeline_df)

    # Align by (round, tick)
    merged = java_df.merge(pipeline_df, on=['round', 'tick'], suffixes=('_java', '_pipe'), how='inner')
    print(f"Aligned rows (round+tick match): {len(merged)}")

    if len(merged) == 0:
        # Try tick-only alignment
        print("Trying tick-only alignment...")
        merged = java_df.merge(pipeline_df, on='tick', suffixes=('_java', '_pipe'), how='inner')
        print(f"Aligned rows (tick-only match): {len(merged)}")

    if len(merged) == 0:
        print("\nERROR: No matching rows found. Checking round/tick ranges:")
        print(f"  Java rounds: {java_df['round'].unique()}, ticks: {java_df['tick'].min()}-{java_df['tick'].max()}")
        print(f"  Pipeline rounds: {pipeline_df['round'].unique() if 'round' in pipeline_df.columns else 'N/A'}")
        print(f"  Pipeline ticks: {pipeline_df['tick'].min()}-{pipeline_df['tick'].max()}")
        sys.exit(1)

    # Compare each of the 80 features
    results = []
    for feat in FEATURE_NAMES:
        # Java column name is just the feature name (from the named CSV header)
        j_col = feat + '_java' if feat + '_java' in merged.columns else feat
        p_col = feat + '_pipe' if feat + '_pipe' in merged.columns else feat

        if j_col not in merged.columns:
            results.append((feat, np.nan, np.nan, np.nan, 0, "MISSING_JAVA"))
            continue
        if p_col not in merged.columns:
            results.append((feat, np.nan, np.nan, np.nan, 0, "MISSING_PIPE"))
            continue

        j = merged[j_col].values.astype(float)
        p = merged[p_col].values.astype(float)

        valid = np.isfinite(j) & np.isfinite(p)
        n = int(valid.sum())
        if n < 10:
            results.append((feat, np.nan, np.nan, np.nan, n, "FEW_VALID"))
            continue

        j_v, p_v = j[valid], p[valid]

        # Correlation
        if np.std(j_v) < 1e-10 or np.std(p_v) < 1e-10:
            corr = 1.0 if np.allclose(j_v, p_v, atol=1e-6) else 0.0
        else:
            corr = float(np.corrcoef(j_v, p_v)[0, 1])

        mae = float(np.mean(np.abs(j_v - p_v)))
        max_err = float(np.max(np.abs(j_v - p_v)))
        results.append((feat, corr, mae, max_err, n, ""))

    # Sort by correlation (worst first)
    results.sort(key=lambda x: x[1] if np.isfinite(x[1]) else -999)

    # Print report
    print(f"\n{'Feature':<45} {'Corr':>8} {'MAE':>12} {'MaxErr':>12} {'N':>6} {'Note':>15}")
    print('=' * 100)

    divergent = []
    ok_count = 0
    for feat, corr, mae, max_err, n, note in results:
        if note:
            print(f"{feat:<45} {'---':>8} {'---':>12} {'---':>12} {n:>6} {note:>15}")
            continue
        flag = ' ***DIVERGENT' if corr < 0.95 else ''
        print(f"{feat:<45} {corr:>8.4f} {mae:>12.6f} {max_err:>12.6f} {n:>6}{flag}")
        if corr < 0.95:
            divergent.append((feat, corr, mae, max_err))
        else:
            ok_count += 1

    # Summary
    print(f"\n{'='*60}")
    print(f"SUMMARY: {ok_count} features OK (corr >= 0.95)")
    print(f"         {len(divergent)} features DIVERGENT (corr < 0.95)")
    print(f"         {sum(1 for r in results if r[5])} features missing/insufficient data")

    if divergent:
        print(f"\n=== DIVERGENT FEATURES ===")
        for feat, corr, mae, max_err in divergent:
            print(f"  {feat:<45} corr={corr:.4f}  MAE={mae:.6f}  maxErr={max_err:.6f}")

        # Categorize divergence
        window_feats = [f for f, *_ in divergent if '_wmean' in f or '_wstd' in f]
        base_feats = [f for f, *_ in divergent if '_wmean' not in f and '_wstd' not in f]

        if window_feats:
            print(f"\n  Window features divergent ({len(window_feats)}): {window_feats}")
            print("  => Likely cause: window computation differences (round boundary, min_periods, ddof)")
        if base_feats:
            print(f"\n  Base features divergent ({len(base_feats)}): {base_feats}")
            print("  => Likely cause: scan timing, feature ordering, or computation logic differences")
    else:
        print("\n  ALL FEATURES MATCH — look elsewhere for R² divergence")

    # Also check prediction vs actual
    if 'predicted' in java_df.columns and 'actual' in java_df.columns:
        valid = java_df['actual'].notna() & (java_df['actual'] != 0)
        if valid.sum() > 5:
            pred = java_df.loc[valid, 'predicted'].values
            act = java_df.loc[valid, 'actual'].values
            ss_res = np.sum((act - pred) ** 2)
            ss_tot = np.sum((act - np.mean(act)) ** 2)
            r2 = 1 - ss_res / ss_tot if ss_tot > 0 else float('nan')
            print(f"\n=== IN-GAME PREDICTION QUALITY ===")
            print(f"  Samples with known actual: {valid.sum()}")
            print(f"  In-game R²: {r2:.4f}")
            print(f"  Mean prediction: {np.mean(pred):.4f}, Mean actual: {np.mean(act):.4f}")
            print(f"  Prediction std: {np.std(pred):.4f}, Actual std: {np.std(act):.4f}")


if __name__ == '__main__':
    main()

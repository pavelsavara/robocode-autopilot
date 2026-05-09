"""Analyze the Java FeatureLogger output for NaN patterns and value distributions.
This checks the diagnostic CSV in isolation — no cross-battle comparison needed.
"""
import numpy as np
import pandas as pd

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

df = pd.read_csv(r"d:\robocode-autopilot\output\local\features_fire_power_diagnostic.csv")
print(f"Total rows: {len(df)}")
print(f"Rounds: {sorted(df['round'].unique())}")
print(f"Tick range per round:")
for r in sorted(df['round'].unique()):
    rdf = df[df['round'] == r]
    print(f"  Round {r}: ticks {rdf['tick'].min()}-{rdf['tick'].max()} ({len(rdf)} rows)")

# Check NaN patterns for all 80 features
print(f"\n{'='*80}")
print(f"NaN ANALYSIS — Features with high NaN rates")
print(f"{'Feature':<50} {'Valid':>6} {'NaN':>6} {'%NaN':>8} {'Mean':>12} {'Std':>12}")
print('-' * 96)

always_nan = []
sometimes_nan = []
always_valid = []
for feat in FEATURE_NAMES:
    if feat not in df.columns:
        print(f"{feat:<50} NOT IN CSV")
        always_nan.append(feat)
        continue
    n_valid = df[feat].notna().sum()
    n_nan = df[feat].isna().sum()
    pct_nan = 100 * n_nan / len(df)
    if n_valid > 0:
        mean = df[feat].mean()
        std = df[feat].std()
    else:
        mean = float('nan')
        std = float('nan')
    
    if n_nan == len(df):
        always_nan.append(feat)
        print(f"{feat:<50} {n_valid:>6} {n_nan:>6} {pct_nan:>7.1f}% {'NaN':>12} {'NaN':>12}  *** ALWAYS NaN")
    elif n_nan > 0:
        sometimes_nan.append(feat)
        print(f"{feat:<50} {n_valid:>6} {n_nan:>6} {pct_nan:>7.1f}% {mean:>12.4f} {std:>12.4f}  * PARTIAL")
    else:
        always_valid.append(feat)

print(f"\n{'='*80}")
print(f"SUMMARY:")
print(f"  Always NaN:     {len(always_nan)} features")
print(f"  Sometimes NaN:  {len(sometimes_nan)} features")
print(f"  Always valid:   {len(always_valid)} features")

print(f"\n=== ALWAYS NaN FEATURES ({len(always_nan)}) ===")
for f in always_nan:
    print(f"  - {f}")

print(f"\n=== SOMETIMES NaN FEATURES ({len(sometimes_nan)}) ===")
for f in sometimes_nan:
    n_valid = df[f].notna().sum()
    pct = 100 * n_valid / len(df)
    print(f"  - {f}: {n_valid}/{len(df)} valid ({pct:.1f}%)")

# Check prediction quality
if 'predicted' in df.columns and 'actual' in df.columns:
    print(f"\n{'='*80}")
    print("IN-GAME PREDICTION ANALYSIS")
    
    # All rows
    valid_pred = df['predicted'].notna()
    print(f"\nPrediction stats (all {valid_pred.sum()} rows):")
    print(f"  Mean: {df.loc[valid_pred, 'predicted'].mean():.4f}")
    print(f"  Std:  {df.loc[valid_pred, 'predicted'].std():.4f}")
    print(f"  Min:  {df.loc[valid_pred, 'predicted'].min():.4f}")
    print(f"  Max:  {df.loc[valid_pred, 'predicted'].max():.4f}")
    
    # Rows where actual fire power is known
    fire_rows = df['actual'].notna() & (df['actual'] > 0)
    print(f"\nFire events: {fire_rows.sum()}")
    if fire_rows.sum() > 0:
        pred = df.loc[fire_rows, 'predicted'].values
        actual = df.loc[fire_rows, 'actual'].values
        print(f"  Actual fire powers: {np.unique(actual)}")
        print(f"  Actual mean: {actual.mean():.4f}, std: {actual.std():.4f}")
        print(f"  Predicted mean: {pred.mean():.4f}, std: {pred.std():.4f}")
        
        # R²
        ss_res = np.sum((actual - pred)**2)
        ss_tot = np.sum((actual - actual.mean())**2)
        r2 = 1 - ss_res / ss_tot if ss_tot > 0 else float('nan')
        print(f"  R²: {r2:.4f}")
        
        # Check prediction range
        print(f"\n  Prediction distribution on fire ticks:")
        for p_val in np.arange(0.5, 3.5, 0.5):
            n = ((pred >= p_val) & (pred < p_val + 0.5)).sum()
            print(f"    [{p_val:.1f}, {p_val+0.5:.1f}): {n}")

# Check if window features have NaN at start of rounds (expected) vs throughout
print(f"\n{'='*80}")
print("WINDOW FEATURE TIMING CHECK")
window_feats = [f for f in FEATURE_NAMES if '_wmean' in f or '_wstd' in f]
for wf in window_feats[:4]:  # Just check a few
    if wf in df.columns:
        for r in sorted(df['round'].unique())[:2]:
            rdf = df[df['round'] == r].sort_values('tick')
            first_valid_tick = rdf.loc[rdf[wf].notna(), 'tick'].min()
            n_valid = rdf[wf].notna().sum()
            print(f"  {wf} round {r}: first valid at tick {first_valid_tick}, {n_valid}/{len(rdf)} valid")

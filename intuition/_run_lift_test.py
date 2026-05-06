"""Quick lift test: retrain fire-power GBM with and without new features.

Small data (85K rows), no hyperparameter search — just compare R² and
feature importance to see if the new columns add lift.

Usage:
    python intuition/_run_lift_test.py
"""
import sys
sys.path.insert(0, 'intuition')

from pathlib import Path
import numpy as np
import pandas as pd
import xgboost as xgb
from sklearn.model_selection import GroupKFold
from sklearn.metrics import r2_score, mean_absolute_error

from _loader import (
    build_robot_index, load_stratified, compute_strategic_axes,
    numeric_feature_cols, drop_redundant_features,
    WAVE_DERIVED_COLS, FIRE_POWER_LEAKAGE_COLS, SCAN_META_COLS,
)

csv_root = Path('output/csv')

# Small sample for speed: 20 bots × 2 battles × 5% rows ≈ 85K rows
print("Loading small sample...")
selection = build_robot_index(csv_root=csv_root, max_robots=20,
                               battles_per_robot=2, seed=42)
ticks = load_stratified('ticks.csv', selection, csv_root=csv_root, row_frac=0.05)
ticks = compute_strategic_axes(ticks)
print(f"Loaded {len(ticks):,} rows × {len(ticks.columns)} cols")

# Target: fire power prediction (only on ticks where opponent fired)
mask = ticks['opponent_fired'] == 1
fire_ticks = ticks[mask].copy()
print(f"Fire events: {len(fire_ticks):,}")

target = 'opponent_fire_power'
y = fire_ticks[target].values
groups = fire_ticks['battle_id'].astype(str).values

# Exclude leakage columns
exclude = set(WAVE_DERIVED_COLS) | set(FIRE_POWER_LEAKAGE_COLS) | set(SCAN_META_COLS)

# OLD features: without the new columns
new_cols = {
    'nearest_opponent_wave_gap', 'total_opponent_wave_damage', 'nearest_our_wave_gap',
    'envelope_fill_ratio', 'reachable_distance_min', 'reachable_distance_max',
    'reachable_gf_range',
    'cumulative_damage_dealt', 'cumulative_damage_received',
    'cumulative_our_hit_rate', 'cumulative_opponent_hit_rate',
    'cumulative_our_shots_fired', 'cumulative_opponent_shots_detected',
}

all_features = numeric_feature_cols(fire_ticks, extra_exclude=exclude)
all_features = drop_redundant_features(all_features)

old_features = [c for c in all_features if c not in new_cols]
new_only = [c for c in all_features if c in new_cols]

print(f"\nOld features: {len(old_features)}")
print(f"New features: {len(new_only)} — {new_only}")
print(f"All features: {len(all_features)}")

# Fixed hyperparameters (no search — speed)
params = dict(
    n_estimators=300,
    max_depth=6,
    learning_rate=0.1,
    subsample=0.8,
    colsample_bytree=0.8,
    n_jobs=-1,
    random_state=42,
    verbosity=0,
)

def evaluate(feature_list, label):
    X = fire_ticks[feature_list].values
    cv = GroupKFold(n_splits=3)
    r2s, maes = [], []
    importances = np.zeros(len(feature_list))
    for train_idx, test_idx in cv.split(X, y, groups):
        model = xgb.XGBRegressor(**params)
        model.fit(X[train_idx], y[train_idx])
        pred = model.predict(X[test_idx])
        r2s.append(r2_score(y[test_idx], pred))
        maes.append(mean_absolute_error(y[test_idx], pred))
        importances += model.feature_importances_
    importances /= 3
    print(f"\n{label}:")
    print(f"  R² = {np.mean(r2s):.4f} ± {np.std(r2s):.4f}")
    print(f"  MAE = {np.mean(maes):.4f} ± {np.std(maes):.4f}")
    # Top 5 features
    top_idx = np.argsort(importances)[::-1][:5]
    print("  Top features:")
    for i in top_idx:
        print(f"    {feature_list[i]:40s} {importances[i]:.3f}")
    return np.mean(r2s), np.mean(maes)

r2_old, mae_old = evaluate(old_features, "OLD features only")
r2_all, mae_all = evaluate(all_features, "ALL features (old + new)")

# Also test: just envelope + wave-gap features (no cumulative)
envelope_wave_cols = {
    'nearest_our_wave_gap',
    'envelope_fill_ratio', 'reachable_distance_min', 'reachable_distance_max',
    'reachable_gf_range',
}
env_features = old_features + [c for c in all_features if c in envelope_wave_cols]
r2_env, mae_env = evaluate(env_features, "OLD + envelope/wave-gap only (no cumulative)")

print(f"\n{'='*60}")
print(f"LIFT from new features:")
print(f"  R²:  {r2_old:.4f} → {r2_all:.4f}  (Δ = {r2_all - r2_old:+.4f})")
print(f"  MAE: {mae_old:.4f} → {mae_all:.4f}  (Δ = {mae_all - mae_old:+.4f})")

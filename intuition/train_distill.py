"""Train compact GBM models for Java distillation.

Retrains all models at reduced size (200 trees, depth 6) to fit the
500 KB per-model binary budget. Also trains the previously missing
movement (GBM-window) and fire timing (GBM-window) models.

Usage:
    cd intuition
    python train_distill.py [--task fire_power|fire_timing|movement|all]

Output:
    models/distill/<task>/model.json  — XGBoost model for export
    models/distill/<task>/metrics.json — CV metrics
    models/distill/<task>/feature_cols.json — ordered feature list
"""
from __future__ import annotations

import json
import sys
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import (
    r2_score, mean_absolute_error, roc_auc_score, accuracy_score
)
from sklearn.model_selection import GroupKFold

warnings.filterwarnings('ignore', category=FutureWarning)
sys.path.insert(0, str(Path(__file__).parent))

from _loader import (
    build_robot_index, load_stratified, numeric_feature_cols,
    FIRE_POWER_LEAKAGE_COLS, SCAN_META_COLS, WAVE_DERIVED_COLS,
)

OUT_ROOT = Path(__file__).parent / 'models' / 'distill'

# Compact model config: 200 trees × depth 6 ≈ 200–400 KB binary
COMPACT_PARAMS = {
    'n_estimators': 200,
    'max_depth': 6,
    'learning_rate': 0.1,
    'subsample': 0.8,
    'colsample_bytree': 0.8,
    'reg_lambda': 2.0,
    'reg_alpha': 1.0,
    'min_child_weight': 3,
    'n_jobs': -1,
    'random_state': 42,
    'verbosity': 0,
}

WINDOW_SIZE = 20

# Base features for window stats — must match WindowFeatures.java exactly
WINDOW_BASE_FEATURES = [
    'distance', 'bearing_to_opponent_abs', 'opponent_dist_to_wall_min',
    'our_gun_heat', 'ticks_since_scan', 'opponent_energy',
    'our_x', 'our_y', 'our_heading', 'our_velocity',
]


# ── Window features ──────────────────────────────────────────────────

def add_window_features(df: pd.DataFrame, base_cols: list[str],
                         window: int = WINDOW_SIZE) -> tuple[pd.DataFrame, list[str]]:
    """Add rolling window mean/std for base_cols. Returns (df, new_col_names)."""
    new_cols = []
    for col in base_cols:
        if col not in df.columns:
            continue
        wmean = col + '_wmean'
        wstd = col + '_wstd'
        df[wmean] = df.groupby(['battle_id', 'round', 'robot_name'])[col].transform(
            lambda x: x.rolling(window, min_periods=5).mean()
        )
        df[wstd] = df.groupby(['battle_id', 'round', 'robot_name'])[col].transform(
            lambda x: x.rolling(window, min_periods=5).std()
        )
        new_cols.extend([wmean, wstd])
    return df, new_cols


# ── Data loading ─────────────────────────────────────────────────────

def load_data(csv_root=None, verbose=True):
    """Load ticks + scores with stratified sampling.

    If csv_root is provided, data is loaded from that directory instead of
    the default output/csv/.
    """
    root_kwargs = {'csv_root': csv_root} if csv_root else {}
    selection = build_robot_index(max_robots=50, battles_per_robot=2,
                                  seed=42, verbose=verbose, **root_kwargs)
    # row_frac=1.0: need consecutive ticks for shift-based targets
    ticks = load_stratified('ticks.csv', selection, row_frac=1.0, verbose=verbose,
                            **root_kwargs)
    scores = load_stratified('scores.csv', selection, verbose=verbose,
                             **root_kwargs)
    return ticks, scores, selection


# ── Task A: Fire Power ───────────────────────────────────────────────

def train_fire_power(ticks: pd.DataFrame):
    """Train compact fire power predictor."""
    import xgboost as xgb

    print("\n=== Fire Power (compact) ===")

    # Compute window features on FULL ticks BEFORE filtering to fire events.
    # This matches Java's WindowFeatures which computes over consecutive ticks.
    # Previously, window features were computed on fire-event rows only,
    # making a "20-tick window" actually a "20-fire-event window" spanning
    # hundreds of real ticks — a fundamental mismatch with in-game computation.
    ticks_w = ticks.copy()
    ticks_w, _new_cols = add_window_features(ticks_w, WINDOW_BASE_FEATURES)

    # Filter to fire events
    fire_mask = ticks_w['opponent_fired'] == 1
    fire = ticks_w[fire_mask].dropna(subset=['opponent_fire_power']).copy()
    print(f"  Fire events: {len(fire):,}")

    # Feature selection (exclude leakage)
    # Window features are already in the DataFrame from add_window_features above,
    # so numeric_feature_cols() will include them automatically.
    exclude = set(FIRE_POWER_LEAKAGE_COLS) | {'opponent_fired'} | set(SCAN_META_COLS)
    feat_cols = numeric_feature_cols(fire, extra_exclude=exclude)

    # Clean
    target = fire['opponent_fire_power'].values
    X = fire[feat_cols].values
    groups = fire['battle_id'].astype(str).values

    valid = np.isfinite(target) & np.all(np.isfinite(X), axis=1)
    X, target, groups = X[valid], target[valid], groups[valid]
    print(f"  Valid samples: {len(X):,}, features: {X.shape[1]}")

    # Train with CV
    model = xgb.XGBRegressor(**COMPACT_PARAMS)
    gkf = GroupKFold(n_splits=5)

    r2_scores, mae_scores = [], []
    for train_idx, val_idx in gkf.split(X, target, groups):
        model.fit(X[train_idx], target[train_idx])
        pred = model.predict(X[val_idx])
        r2_scores.append(r2_score(target[val_idx], pred))
        mae_scores.append(mean_absolute_error(target[val_idx], pred))

    # Final model on all data
    model.fit(X, target)

    metrics = {
        'task': 'fire_power',
        'model': f'XGBRegressor (compact {COMPACT_PARAMS["n_estimators"]}×{COMPACT_PARAMS["max_depth"]})',
        'n_samples': len(X),
        'n_features': X.shape[1],
        'cv_r2_mean': float(np.mean(r2_scores)),
        'cv_r2_std': float(np.std(r2_scores)),
        'cv_mae_mean': float(np.mean(mae_scores)),
        'cv_mae_std': float(np.std(mae_scores)),
        'feature_cols': feat_cols,
    }

    print(f"  R²: {metrics['cv_r2_mean']:.4f} ± {metrics['cv_r2_std']:.4f}")
    print(f"  MAE: {metrics['cv_mae_mean']:.4f} ± {metrics['cv_mae_std']:.4f}")

    _save(model, metrics, 'fire_power')
    return metrics


# ── Task D: Movement Prediction ──────────────────────────────────────

def train_movement(ticks: pd.DataFrame):
    """Train compact movement predictor (lateral velocity at t+5)."""
    import xgboost as xgb

    print("\n=== Movement N=5 (compact) ===")

    HORIZON = 5
    # Build target: opponent_lateral_velocity shifted by HORIZON
    df = ticks.sort_values(['battle_id', 'round', 'robot_name', 'tick']).copy()
    df['target_lat_vel'] = df.groupby(
        ['battle_id', 'round', 'robot_name'])['opponent_lateral_velocity'].shift(-HORIZON)

    df = df.dropna(subset=['target_lat_vel'])

    # Features: exclude wave-derived (not about movement) and scan meta
    # Also exclude opponent_lateral_velocity itself — it's autocorrelated with the target
    exclude = set(WAVE_DERIVED_COLS) | set(SCAN_META_COLS) | {
        'opponent_fired', 'opponent_fire_power',
        'opponent_lateral_velocity',  # IS the target at t+5
        'opponent_guess_factor',  # ≡ our_lateral_velocity / 8
        'target_lat_vel',  # the synthetic target column
        'opponent_avg_lateral_velocity_10',  # running avg of target
        'opponent_avg_lateral_velocity_30',  # running avg of target
        'opponent_lateral_direction',  # sign(target)
    }
    feat_cols = numeric_feature_cols(df, extra_exclude=exclude)

    # Add window features matching WindowFeatures.java
    df, new_cols = add_window_features(df, WINDOW_BASE_FEATURES)
    feat_cols = feat_cols + new_cols

    target = df['target_lat_vel'].values
    X = df[feat_cols].values
    groups = df['battle_id'].astype(str).values

    valid = np.isfinite(target) & np.all(np.isfinite(X), axis=1)
    X, target, groups = X[valid], target[valid], groups[valid]

    # Subsample if too large
    if len(X) > 500_000:
        rng = np.random.RandomState(42)
        idx = rng.choice(len(X), 500_000, replace=False)
        X, target, groups = X[idx], target[idx], groups[idx]

    print(f"  Samples: {len(X):,}, features: {X.shape[1]}")

    model = xgb.XGBRegressor(**COMPACT_PARAMS)
    gkf = GroupKFold(n_splits=5)

    r2_scores, mae_scores = [], []
    for train_idx, val_idx in gkf.split(X, target, groups):
        model.fit(X[train_idx], target[train_idx])
        pred = model.predict(X[val_idx])
        r2_scores.append(r2_score(target[val_idx], pred))
        mae_scores.append(mean_absolute_error(target[val_idx], pred))

    model.fit(X, target)

    metrics = {
        'task': 'movement',
        'model': f'XGBRegressor (compact, N={HORIZON})',
        'n_samples': len(X),
        'n_features': X.shape[1],
        'cv_r2_mean': float(np.mean(r2_scores)),
        'cv_r2_std': float(np.std(r2_scores)),
        'cv_mae_mean': float(np.mean(mae_scores)),
        'cv_mae_std': float(np.std(mae_scores)),
        'feature_cols': feat_cols,
    }

    print(f"  R²: {metrics['cv_r2_mean']:.4f} ± {metrics['cv_r2_std']:.4f}")
    print(f"  MAE: {metrics['cv_mae_mean']:.4f} ± {metrics['cv_mae_std']:.4f}")

    _save(model, metrics, 'movement')
    return metrics


# ── Task E: Fire Timing ──────────────────────────────────────────────

def train_fire_timing(ticks: pd.DataFrame):
    """Train compact fire timing predictor (fires within 3 ticks)."""
    import xgboost as xgb

    print("\n=== Fire Timing 3-tick (compact) ===")

    HORIZON = 3
    df = ticks.sort_values(['battle_id', 'round', 'robot_name', 'tick']).copy()

    # Build target: does opponent fire in ticks [t+1, t+HORIZON]? (excluding current tick)
    df['fires_ahead'] = df.groupby(
        ['battle_id', 'round', 'robot_name'])['opponent_fired'].transform(
        lambda x: x.shift(-1).rolling(HORIZON, min_periods=1).max()
    )
    df['fires_ahead'] = df['fires_ahead'].fillna(0).astype(int)

    # Features: exclude fire-detection columns (leakage) AND opponent_fired itself
    exclude = set(WAVE_DERIVED_COLS) | set(SCAN_META_COLS) | {
        'opponent_fired', 'opponent_fire_power',
        'fires_ahead',  # the synthetic target column
    }
    feat_cols = numeric_feature_cols(df, extra_exclude=exclude)

    # Add window features matching WindowFeatures.java
    df, new_cols = add_window_features(df, WINDOW_BASE_FEATURES)
    feat_cols = feat_cols + new_cols

    target = df['fires_ahead'].values
    X = df[feat_cols].values
    groups = df['battle_id'].astype(str).values

    valid = np.isfinite(target) & np.all(np.isfinite(X), axis=1)
    X, target, groups = X[valid], target[valid], groups[valid]

    if len(X) > 500_000:
        rng = np.random.RandomState(42)
        idx = rng.choice(len(X), 500_000, replace=False)
        X, target, groups = X[idx], target[idx], groups[idx]

    print(f"  Samples: {len(X):,}, features: {X.shape[1]}")
    print(f"  Positive rate: {target.mean():.3f}")

    params = dict(COMPACT_PARAMS)
    params['eval_metric'] = 'logloss'
    pos_weight = float((target == 0).sum() / max(1, (target == 1).sum()))
    params['scale_pos_weight'] = pos_weight

    model = xgb.XGBClassifier(**params)
    gkf = GroupKFold(n_splits=5)

    auc_scores = []
    for train_idx, val_idx in gkf.split(X, target, groups):
        model.fit(X[train_idx], target[train_idx])
        pred_proba = model.predict_proba(X[val_idx])[:, 1]
        auc_scores.append(roc_auc_score(target[val_idx], pred_proba))

    model.fit(X, target)

    metrics = {
        'task': 'fire_timing',
        'model': f'XGBClassifier (compact, K={HORIZON})',
        'n_samples': len(X),
        'n_features': X.shape[1],
        'cv_auc_mean': float(np.mean(auc_scores)),
        'cv_auc_std': float(np.std(auc_scores)),
        'feature_cols': feat_cols,
    }

    print(f"  AUC: {metrics['cv_auc_mean']:.4f} ± {metrics['cv_auc_std']:.4f}")

    _save(model, metrics, 'fire_timing')
    return metrics


# ── Save ─────────────────────────────────────────────────────────────

def _save(model, metrics: dict, task: str):
    task_dir = OUT_ROOT / task
    task_dir.mkdir(parents=True, exist_ok=True)
    model.save_model(str(task_dir / 'model.json'))
    with open(task_dir / 'metrics.json', 'w') as f:
        json.dump(metrics, f, indent=2, default=str)
    with open(task_dir / 'feature_cols.json', 'w') as f:
        json.dump(metrics['feature_cols'], f)
    print(f"  Saved to {task_dir}")


# ── CLI ──────────────────────────────────────────────────────────────

def _parse_args():
    task = 'all'
    roots = None
    i = 1
    while i < len(sys.argv):
        arg = sys.argv[i]
        if arg == '--task' and i + 1 < len(sys.argv):
            task = sys.argv[i + 1]; i += 2
        elif arg == '--roots' and i + 1 < len(sys.argv):
            roots = sys.argv[i + 1]; i += 2
        elif arg in ('fire_power', 'fire_timing', 'movement', 'all'):
            task = arg; i += 1
        else:
            i += 1
    return task, roots


if __name__ == '__main__':
    task, roots = _parse_args()

    print("Loading data...")
    ticks, scores, selection = load_data(csv_root=roots)

    all_metrics = {}
    if task in ('fire_power', 'all'):
        all_metrics['fire_power'] = train_fire_power(ticks)
    if task in ('movement', 'all'):
        all_metrics['movement'] = train_movement(ticks)
    if task in ('fire_timing', 'all'):
        all_metrics['fire_timing'] = train_fire_timing(ticks)

    if all_metrics:
        summary_path = OUT_ROOT / 'summary.json'
        OUT_ROOT.mkdir(parents=True, exist_ok=True)
        with open(summary_path, 'w') as f:
            json.dump(all_metrics, f, indent=2, default=str)
        print(f"\nAll results saved to {summary_path}")

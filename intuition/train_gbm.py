"""GBM training pipeline for Robocode ML Stage 4.

Three tasks:
  A. Fire power prediction (regression) — per-tick + 20-tick window features
  B. Round outcome prediction (binary classification) — per-round aggregates
  C. Bot fingerprinting (multi-class classification) — wave + tick window stats

Each task uses RandomizedSearchCV for hyperparameter tuning with GroupKFold
on battle_id (no within-battle leakage).

Usage:
    cd intuition
    python train_gbm.py

Outputs saved to intuition/models/gbm/<task>/
"""
from __future__ import annotations

import json
import sys
import time
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.model_selection import GroupKFold, RandomizedSearchCV, cross_val_score
from sklearn.preprocessing import LabelEncoder

sys.path.insert(0, str(Path(__file__).parent))
from _loader import (
    FIRE_POWER_LEAKAGE_COLS,
    SCAN_META_COLS,
    WAVE_DERIVED_COLS,
    build_robot_index,
    compute_strategic_axes,
    drop_redundant_features,
    load_stratified,
    numeric_feature_cols,
    attach_battle_constants,
    attach_opponent_bot,
)

warnings.filterwarnings('ignore', category=FutureWarning)
warnings.filterwarnings('ignore', category=UserWarning)

OUT_ROOT = Path(__file__).parent / 'models' / 'gbm'

# Hyperparameter search space
XGB_REG_PARAMS = {
    'n_estimators': [200, 500, 800],
    'max_depth': [4, 6, 8, 10],
    'learning_rate': [0.01, 0.05, 0.1],
    'subsample': [0.7, 0.8, 0.9],
    'colsample_bytree': [0.6, 0.8, 1.0],
    'reg_alpha': [0, 0.1, 1.0],
    'reg_lambda': [0.5, 1.0, 2.0],
    'min_child_weight': [1, 3, 5],
}

XGB_CLF_PARAMS = {
    'n_estimators': [200, 500, 800],
    'max_depth': [4, 6, 8],
    'learning_rate': [0.01, 0.05, 0.1],
    'subsample': [0.7, 0.8, 0.9],
    'colsample_bytree': [0.6, 0.8, 1.0],
    'reg_alpha': [0, 0.1, 1.0],
    'reg_lambda': [0.5, 1.0, 2.0],
}

LGB_CLF_PARAMS = {
    'n_estimators': [200, 500, 800],
    'max_depth': [4, 6, 8, 10],
    'learning_rate': [0.01, 0.05, 0.1],
    'subsample': [0.7, 0.8, 0.9],
    'colsample_bytree': [0.6, 0.8, 1.0],
    'reg_alpha': [0, 0.1, 1.0],
    'reg_lambda': [0.5, 1.0, 2.0],
    'min_child_samples': [5, 10, 20],
}


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _save_results(task_dir: Path, metrics: dict, feat_imp: dict | None = None):
    task_dir.mkdir(parents=True, exist_ok=True)
    with open(task_dir / 'metrics.json', 'w') as f:
        json.dump(metrics, f, indent=2, default=str)
    if feat_imp is not None:
        pd.DataFrame(list(feat_imp.items()),
                      columns=['feature', 'importance']) \
          .sort_values('importance', ascending=False) \
          .to_csv(task_dir / 'feature_importance.csv', index=False)
    print(f"  Results saved to {task_dir}")


def _ensure_xgb():
    try:
        import xgboost as xgb
        return xgb
    except ImportError:
        print("ERROR: xgboost not installed. Run: pip install xgboost")
        sys.exit(1)


def _ensure_lgb():
    try:
        import lightgbm as lgb
        return lgb
    except ImportError:
        print("ERROR: lightgbm not installed. Run: pip install lightgbm")
        sys.exit(1)


def _add_window_features(ticks: pd.DataFrame, feature_cols: list[str],
                          window_size: int = 20) -> pd.DataFrame:
    """Add rolling window statistics (mean, std, slope, range) to fire-event rows.

    For each fire event, compute statistics over the preceding `window_size` ticks.
    Returns a new DataFrame with the original columns plus window features.
    """
    # Work on a copy with sorted ticks
    print(f"    Computing {window_size}-tick window features...")
    stat_names = ['_wmean', '_wstd', '_wslope', '_wrange']
    window_cols = []

    result_frames = []
    for (bid, rnd, robot), grp in ticks.groupby(['battle_id', 'round', 'robot_name']):
        grp = grp.sort_values('tick').reset_index(drop=True)
        fire_mask = grp['opponent_fired'] == 1
        if fire_mask.sum() == 0:
            continue

        for col in feature_cols[:10]:  # top 10 features only to keep manageable
            vals = grp[col].values.astype(float)
            # Rolling window stats
            means = pd.Series(vals).rolling(window_size, min_periods=5).mean().values
            stds = pd.Series(vals).rolling(window_size, min_periods=5).std().values

            grp[col + '_wmean'] = means
            grp[col + '_wstd'] = stds

        result_frames.append(grp[fire_mask])

    if not result_frames:
        return pd.DataFrame()

    combined = pd.concat(result_frames, ignore_index=True)
    new_cols = [c for c in combined.columns if c.endswith('_wmean') or c.endswith('_wstd')]
    print(f"    Added {len(new_cols)} window features, {len(combined):,} fire-event rows")
    return combined


# ---------------------------------------------------------------------------
# Task A — Fire Power Prediction
# ---------------------------------------------------------------------------

def train_fire_power(ticks: pd.DataFrame, n_search: int = 30,
                     time_limit: float = 600) -> dict:
    """Train XGBoost regressor with window features + hyperparameter tuning."""
    xgb = _ensure_xgb()
    print("\n=== Task A: Fire Power Prediction ===")
    t0 = time.time()

    # --- Per-tick features for fire events ---
    target_col = 'opponent_fire_power'
    # Exclude: leakage cols + fired flag + scan-quality meta-leakage
    exclude = set(FIRE_POWER_LEAKAGE_COLS) | {'opponent_fired'} | set(SCAN_META_COLS)
    tick_feature_cols = numeric_feature_cols(ticks, extra_exclude=exclude)
    tick_feature_cols = drop_redundant_features(tick_feature_cols)

    # --- Add window features ---
    fire_df = _add_window_features(ticks, tick_feature_cols, window_size=20)
    if fire_df.empty or len(fire_df) < 100:
        print("  Too few fire events after windowing, skipping.")
        return {}

    # Build feature list: original tick features + window features
    window_feature_cols = [c for c in fire_df.columns
                           if (c.endswith('_wmean') or c.endswith('_wstd'))
                           and fire_df[c].dtype in ('float32', 'float64')]
    all_feature_cols = [c for c in tick_feature_cols if c in fire_df.columns] + window_feature_cols

    X = fire_df[all_feature_cols].fillna(0).values.astype(np.float32)
    y = fire_df[target_col].values.astype(np.float32)
    groups = fire_df['battle_id'].astype(str).values

    # Filter NaN/inf
    valid = np.isfinite(y) & np.all(np.isfinite(X), axis=1)
    X, y, groups = X[valid], y[valid], groups[valid]

    print(f"  Features: {len(all_feature_cols)} ({len(tick_feature_cols)} tick + {len(window_feature_cols)} window)")
    print(f"  Samples: {len(X):,}")

    # --- Hyperparameter search ---
    gkf = GroupKFold(n_splits=5)
    base_model = xgb.XGBRegressor(n_jobs=-1, random_state=42, verbosity=0)

    elapsed = time.time() - t0
    remaining = max(60, time_limit - elapsed)
    actual_n = min(n_search, max(5, int(remaining / 10)))  # ~10s per iter
    print(f"  Hyperparameter search: {actual_n} iterations...")

    search = RandomizedSearchCV(
        base_model, XGB_REG_PARAMS, n_iter=actual_n, cv=gkf,
        scoring='neg_mean_absolute_error', random_state=42,
        n_jobs=1, refit=True,  # n_jobs=1 because XGBoost already parallelizes
    )
    search.fit(X, y, groups=groups)

    best_model = search.best_estimator_
    print(f"  Best params: {search.best_params_}")
    print(f"  Best CV MAE: {-search.best_score_:.4f}")

    # --- Cross-val with best model ---
    r2_scores = cross_val_score(best_model, X, y, cv=gkf, groups=groups, scoring='r2')
    mae_scores = -cross_val_score(best_model, X, y, cv=gkf, groups=groups,
                                   scoring='neg_mean_absolute_error')

    # Final model on all data
    best_model.fit(X, y)
    feat_imp = dict(zip(all_feature_cols, best_model.feature_importances_.tolist()))

    metrics = {
        'task': 'fire_power',
        'model': 'XGBRegressor (tuned + window)',
        'n_samples': int(len(X)),
        'n_features': len(all_feature_cols),
        'n_tick_features': len(tick_feature_cols),
        'n_window_features': len(window_feature_cols),
        'best_params': {k: (int(v) if isinstance(v, (np.integer,)) else v)
                        for k, v in search.best_params_.items()},
        'cv_r2_mean': float(np.mean(r2_scores)),
        'cv_r2_std': float(np.std(r2_scores)),
        'cv_r2_per_fold': r2_scores.tolist(),
        'cv_mae_mean': float(np.mean(mae_scores)),
        'cv_mae_std': float(np.std(mae_scores)),
        'cv_mae_per_fold': mae_scores.tolist(),
        'feature_cols': all_feature_cols,
        'mean_baseline_mae': float(np.mean(np.abs(y - y.mean()))),
        'search_iterations': actual_n,
        'training_time_s': round(time.time() - t0, 1),
    }

    print(f"  CV R²:  {metrics['cv_r2_mean']:.4f} ± {metrics['cv_r2_std']:.4f}")
    print(f"  CV MAE: {metrics['cv_mae_mean']:.4f} ± {metrics['cv_mae_std']:.4f}")
    print(f"  Mean baseline MAE: {metrics['mean_baseline_mae']:.4f}")
    print(f"  Training time: {metrics['training_time_s']:.0f}s")

    task_dir = OUT_ROOT / 'fire_power'
    task_dir.mkdir(parents=True, exist_ok=True)
    best_model.save_model(str(task_dir / 'model.json'))
    _save_results(task_dir, metrics, feat_imp)
    return metrics


# ---------------------------------------------------------------------------
# Task B — Round Outcome Prediction
# ---------------------------------------------------------------------------

def train_round_outcome(ticks: pd.DataFrame, scores: pd.DataFrame,
                        time_limit: float = 300, early_ticks: int = 100) -> dict:
    """Train XGBoost classifier on EARLY-tick aggregates only.

    Uses only the first `early_ticks` ticks of each round to avoid outcome
    tautology: full-round energy_ratio_mean IS the outcome restated, and
    tick_count encodes round length (= who died). Early-tick features are
    what a real robot could observe mid-round.
    """
    xgb = _ensure_xgb()
    print(f"\n=== Task B: Round Outcome Prediction (first {early_ticks} ticks) ===")
    t0 = time.time()

    # Filter to first N ticks of each round
    early = ticks.copy()
    early = early[early['tick'] <= early_ticks]
    print(f"  Ticks after early-{early_ticks} filter: {len(early):,} / {len(ticks):,}")

    # Build per-round aggregates from early ticks only
    agg_cols = [
        'distance', 'opponent_lateral_velocity', 'opponent_advancing_velocity',
        'opponent_heading_delta', 'our_dist_to_wall_min',
        'our_lateral_velocity', 'opponent_velocity_delta',
    ]
    # Exclude energy_ratio — it's an outcome echo even in early ticks
    # (damage exchanged in ticks 0-100 shifts energy_ratio)
    agg_cols = [c for c in agg_cols if c in early.columns]

    agg_funcs = {c: ['mean', 'std'] for c in agg_cols}
    # Do NOT include opponent_fired_sum or tick_count — both are outcome proxies

    per_round = early.groupby(['battle_id', 'round', 'robot_name']).agg(agg_funcs)
    per_round.columns = ['_'.join(col).strip('_') for col in per_round.columns]
    per_round = per_round.reset_index()

    score_cols = ['battle_id', 'round', 'robot_name', 'win_rate',
                  'damage_dealt', 'damage_received', 'net_damage']
    score_cols = [c for c in score_cols if c in scores.columns]
    merged = per_round.merge(
        scores[score_cols + (['robot_name'] if 'robot_name' not in score_cols else [])],
        on=['battle_id', 'round', 'robot_name'],
        how='inner',
    )

    merged['won'] = (merged['net_damage'] > 0).astype(int)

    outcome_cols = {'win_rate', 'damage_dealt', 'damage_received', 'net_damage',
                    'won', 'battle_id', 'round', 'robot_name'}
    feature_cols = [c for c in merged.columns
                    if c not in outcome_cols
                    and merged[c].dtype in ('float32', 'float64', 'int32', 'int64', 'int8', 'int16')]

    X = merged[feature_cols].fillna(0).values.astype(np.float32)
    y = merged['won'].values
    groups = merged['battle_id'].astype(str).values

    print(f"  Rounds: {len(X):,}, Features: {len(feature_cols)}")
    print(f"  Majority baseline: {max(y.mean(), 1-y.mean()):.3f}")

    # Light tuning — 15 iterations max
    gkf = GroupKFold(n_splits=5)
    base_model = xgb.XGBClassifier(n_jobs=-1, random_state=42, verbosity=0,
                                    eval_metric='logloss')

    elapsed = time.time() - t0
    remaining = max(30, time_limit - elapsed)
    actual_n = min(15, max(5, int(remaining / 5)))
    print(f"  Hyperparameter search: {actual_n} iterations...")

    search = RandomizedSearchCV(
        base_model, XGB_CLF_PARAMS, n_iter=actual_n, cv=gkf,
        scoring='roc_auc', random_state=42, n_jobs=1, refit=True,
    )
    search.fit(X, y, groups=groups)

    best_model = search.best_estimator_
    print(f"  Best params: {search.best_params_}")

    acc_scores = cross_val_score(best_model, X, y, cv=gkf, groups=groups, scoring='accuracy')
    auc_scores = cross_val_score(best_model, X, y, cv=gkf, groups=groups, scoring='roc_auc')

    best_model.fit(X, y)
    feat_imp = dict(zip(feature_cols, best_model.feature_importances_.tolist()))

    metrics = {
        'task': 'round_outcome',
        'model': 'XGBClassifier (tuned)',
        'n_samples': int(len(X)),
        'n_features': len(feature_cols),
        'majority_baseline': float(max(y.mean(), 1 - y.mean())),
        'best_params': {k: (int(v) if isinstance(v, (np.integer,)) else v)
                        for k, v in search.best_params_.items()},
        'cv_accuracy_mean': float(np.mean(acc_scores)),
        'cv_accuracy_std': float(np.std(acc_scores)),
        'cv_accuracy_per_fold': acc_scores.tolist(),
        'cv_auc_mean': float(np.mean(auc_scores)),
        'cv_auc_std': float(np.std(auc_scores)),
        'cv_auc_per_fold': auc_scores.tolist(),
        'feature_cols': feature_cols,
        'training_time_s': round(time.time() - t0, 1),
    }

    print(f"  CV Accuracy: {metrics['cv_accuracy_mean']:.4f} ± {metrics['cv_accuracy_std']:.4f}")
    print(f"  CV ROC AUC:  {metrics['cv_auc_mean']:.4f} ± {metrics['cv_auc_std']:.4f}")
    print(f"  Training time: {metrics['training_time_s']:.0f}s")

    task_dir = OUT_ROOT / 'round_outcome'
    task_dir.mkdir(parents=True, exist_ok=True)
    best_model.save_model(str(task_dir / 'model.json'))
    _save_results(task_dir, metrics, feat_imp)
    return metrics


# ---------------------------------------------------------------------------
# Task C — Bot Fingerprinting
# ---------------------------------------------------------------------------

def _build_fingerprint_features(waves: pd.DataFrame, ticks: pd.DataFrame,
                                 N: int = 20) -> pd.DataFrame:
    """Build fingerprint windows with wave stats + tick-derived window stats.

    For each N-fire window, compute:
    - 10 wave-based statistics (power, distance, lat-vel moments + trends)
    - 6 tick-derived statistics (movement variability around fire events)
    """
    if 'opponent_bot' not in waves.columns:
        return pd.DataFrame()

    bot_counts = waves.groupby('opponent_bot').size()
    valid_bots = bot_counts[bot_counts >= N].index.tolist()
    wf = waves[waves['opponent_bot'].isin(valid_bots)].copy()

    # Pre-index ticks for fast lookup of movement stats around fire ticks
    # Build a dict: (battle_id, robot_name) -> sorted array of (tick, lat_vel, vel_delta, heading_delta)
    tick_lookup = {}
    if 'opponent_lateral_velocity' in ticks.columns:
        tick_cols = ['opponent_lateral_velocity', 'opponent_velocity_delta',
                     'opponent_heading_delta', 'distance']
        tick_cols = [c for c in tick_cols if c in ticks.columns]
        for (bid, robot), grp in ticks.groupby(['battle_id', 'robot_name']):
            grp = grp.sort_values('tick')
            tick_lookup[(bid, robot)] = {
                'ticks': grp['tick'].values.astype(int),
                **{c: grp[c].values.astype(float) for c in tick_cols}
            }

    windows = []
    for (bid, robot), grp in wf.groupby(['battle_id', 'robot_name']):
        grp = grp.sort_values('tick')
        opp = grp['opponent_bot'].iloc[0]
        vals = grp[['wave_bullet_power', 'wave_fire_distance',
                     'wave_lateral_velocity_at_fire']].values
        fire_ticks = grp['tick'].values.astype(int)

        tlookup = tick_lookup.get((bid, robot))

        for start in range(0, len(vals) - N + 1, N):
            chunk = vals[start:start + N]
            chunk_ticks = fire_ticks[start:start + N]
            powers = chunk[:, 0]
            dists = chunk[:, 1]
            lats = chunk[:, 2]
            abs_lats = np.abs(lats)

            # 10 wave statistics (same as before)
            corr = np.corrcoef(powers, dists)[0, 1] if len(powers) > 1 else 0.0
            if np.isnan(corr):
                corr = 0.0
            x = np.arange(N, dtype=np.float32)
            power_trend = np.polyfit(x, powers, 1)[0] if N > 1 else 0.0
            dist_trend = np.polyfit(x, dists, 1)[0] if N > 1 else 0.0
            if N > 1 and np.std(powers) > 1e-6:
                power_autocorr = np.corrcoef(powers[:-1], powers[1:])[0, 1]
            else:
                power_autocorr = 0.0
            if np.isnan(power_autocorr):
                power_autocorr = 0.0

            rec = {
                'battle_id': bid,
                'robot_name': robot,
                'opponent_bot': opp,
                # Wave stats (10)
                'mean_power': np.mean(powers),
                'std_power': np.std(powers),
                'mean_dist': np.mean(dists),
                'std_dist': np.std(dists),
                'mean_abs_lat': np.mean(abs_lats),
                'corr_pow_dist': corr,
                'power_trend': power_trend,
                'dist_trend': dist_trend,
                'power_autocorr': power_autocorr,
                'power_range': np.ptp(powers),
                # Lateral velocity at fire: additional stats
                'lat_std': np.std(lats),
                'lat_trend': np.polyfit(x, lats, 1)[0] if N > 1 else 0.0,
                'mean_fire_interval': np.mean(np.diff(chunk_ticks)) if N > 1 else 0.0,
                'std_fire_interval': np.std(np.diff(chunk_ticks)) if N > 1 else 0.0,
            }

            # Tick-derived stats: movement variability between fires
            if tlookup is not None and 'opponent_lateral_velocity' in tlookup:
                t_arr = tlookup['ticks']
                lv_arr = tlookup['opponent_lateral_velocity']
                t_start, t_end = int(chunk_ticks[0]), int(chunk_ticks[-1])
                mask = (t_arr >= t_start) & (t_arr <= t_end)
                if mask.sum() > 5:
                    lv_slice = lv_arr[mask]
                    rec['tick_lat_vel_std'] = float(np.nanstd(lv_slice))
                    rec['tick_lat_vel_range'] = float(np.nanmax(lv_slice) - np.nanmin(lv_slice))
                    # Direction changes in window
                    signs = np.sign(lv_slice)
                    rec['tick_direction_changes'] = float(np.sum(np.abs(np.diff(signs)) > 0))
                else:
                    rec['tick_lat_vel_std'] = 0.0
                    rec['tick_lat_vel_range'] = 0.0
                    rec['tick_direction_changes'] = 0.0

                if 'opponent_heading_delta' in tlookup:
                    hd_slice = tlookup['opponent_heading_delta'][mask]
                    rec['tick_heading_delta_std'] = float(np.nanstd(hd_slice))
                else:
                    rec['tick_heading_delta_std'] = 0.0
            else:
                rec['tick_lat_vel_std'] = 0.0
                rec['tick_lat_vel_range'] = 0.0
                rec['tick_direction_changes'] = 0.0
                rec['tick_heading_delta_std'] = 0.0

            windows.append(rec)

    if not windows:
        return pd.DataFrame()

    return pd.DataFrame(windows)


def train_fingerprint(waves: pd.DataFrame, ticks: pd.DataFrame,
                      time_limit: float = 600) -> dict:
    """Train LightGBM classifier with enriched features + hyperparameter tuning."""
    lgb = _ensure_lgb()
    print("\n=== Task C: Bot Fingerprinting ===")
    t0 = time.time()

    N = 20
    win_df = _build_fingerprint_features(waves, ticks, N=N)
    if win_df.empty:
        print("  No valid windows, skipping.")
        return {}

    n_classes = win_df['opponent_bot'].nunique()
    print(f"  Windows (N={N}): {len(win_df):,}, Classes: {n_classes}")

    le = LabelEncoder()
    win_df['label'] = le.fit_transform(win_df['opponent_bot'])

    feature_cols = [c for c in win_df.columns
                    if c not in {'battle_id', 'robot_name', 'opponent_bot', 'label'}
                    and win_df[c].dtype in ('float32', 'float64', 'int32', 'int64')]
    print(f"  Features: {len(feature_cols)}")

    X = win_df[feature_cols].fillna(0).values.astype(np.float32)
    y = win_df['label'].values
    groups = win_df['battle_id'].astype(str).values

    # Filter NaN/inf
    valid = np.all(np.isfinite(X), axis=1)
    X, y, groups = X[valid], y[valid], groups[valid]

    gkf = GroupKFold(n_splits=min(5, len(set(groups))))

    # Hyperparameter tuning
    base_model = lgb.LGBMClassifier(n_jobs=-1, random_state=42, verbose=-1,
                                     num_class=n_classes)

    elapsed = time.time() - t0
    remaining = max(60, time_limit - elapsed)
    actual_n = min(25, max(5, int(remaining / 8)))
    print(f"  Hyperparameter search: {actual_n} iterations...")

    search = RandomizedSearchCV(
        base_model, LGB_CLF_PARAMS, n_iter=actual_n, cv=gkf,
        scoring='accuracy', random_state=42, n_jobs=1, refit=True,
    )
    search.fit(X, y, groups=groups)

    best_model = search.best_estimator_
    print(f"  Best params: {search.best_params_}")

    acc_scores = cross_val_score(best_model, X, y, cv=gkf, groups=groups, scoring='accuracy')

    best_model.fit(X, y)
    feat_imp = dict(zip(feature_cols, best_model.feature_importances_.tolist()))

    metrics = {
        'task': 'fingerprint',
        'model': 'LGBMClassifier (tuned + enriched)',
        'n_windows': int(len(X)),
        'n_classes': n_classes,
        'window_size': N,
        'n_features': len(feature_cols),
        'random_baseline': float(1.0 / n_classes),
        'best_params': {k: (int(v) if isinstance(v, (np.integer,)) else v)
                        for k, v in search.best_params_.items()},
        'cv_accuracy_mean': float(np.mean(acc_scores)),
        'cv_accuracy_std': float(np.std(acc_scores)),
        'cv_accuracy_per_fold': acc_scores.tolist(),
        'class_names': le.classes_.tolist(),
        'feature_cols': feature_cols,
        'training_time_s': round(time.time() - t0, 1),
    }

    print(f"  CV Top-1 Accuracy: {metrics['cv_accuracy_mean']:.4f} ± {metrics['cv_accuracy_std']:.4f}")
    print(f"  Random baseline:   {metrics['random_baseline']:.4f}")
    print(f"  Training time: {metrics['training_time_s']:.0f}s")

    task_dir = OUT_ROOT / 'fingerprint'
    task_dir.mkdir(parents=True, exist_ok=True)
    best_model.booster_.save_model(str(task_dir / 'model.txt'))
    _save_results(task_dir, metrics, feat_imp)
    with open(task_dir / 'classes.json', 'w') as f:
        json.dump(le.classes_.tolist(), f)
    return metrics


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    print("=" * 60)
    print("GBM Training Pipeline — Stage 4 (tuned)")
    print("=" * 60)

    csv_root = Path(__file__).parent / '..' / 'output' / 'csv'
    print(f"\nCSV root: {csv_root.resolve()}")

    # More data: 10 battles per robot
    selection = build_robot_index(
        csv_root=csv_root,
        max_robots=50,
        battles_per_robot=10,
        seed=42,
    )

    # Load data — full rows for window features
    print("\nLoading ticks.csv (full rows for window features)...")
    ticks = load_stratified('ticks.csv', selection, csv_root=csv_root, row_frac=1.0)

    # Add strategic axes as context features for all models
    ticks = compute_strategic_axes(ticks)

    print("\nLoading waves.csv...")
    waves = load_stratified('waves.csv', selection, csv_root=csv_root)

    print("\nLoading scores.csv...")
    scores = load_stratified('scores.csv', selection, csv_root=csv_root)

    waves = attach_opponent_bot(waves, selection, csv_root=csv_root)

    all_metrics = {}

    # Task A — fire power (10 min budget)
    m = train_fire_power(ticks, n_search=30, time_limit=600)
    if m:
        all_metrics['fire_power'] = m

    # Task B — round outcome (5 min budget)
    m = train_round_outcome(ticks, scores, time_limit=300)
    if m:
        all_metrics['round_outcome'] = m

    # Task C — fingerprint (10 min budget)
    m = train_fingerprint(waves, ticks, time_limit=600)
    if m:
        all_metrics['fingerprint'] = m

    # Summary
    print("\n" + "=" * 60)
    print("SUMMARY")
    print("=" * 60)
    for task, m in all_metrics.items():
        if 'cv_r2_mean' in m:
            print(f"  {task}: R²={m['cv_r2_mean']:.4f}, MAE={m['cv_mae_mean']:.4f} "
                  f"({m.get('training_time_s', '?')}s)")
        elif 'cv_accuracy_mean' in m:
            baseline = m.get('majority_baseline', m.get('random_baseline', 0))
            print(f"  {task}: Acc={m['cv_accuracy_mean']:.4f} (baseline={baseline:.4f}) "
                  f"({m.get('training_time_s', '?')}s)")

    OUT_ROOT.mkdir(parents=True, exist_ok=True)
    with open(OUT_ROOT / 'summary.json', 'w') as f:
        json.dump(all_metrics, f, indent=2, default=str)
    print(f"\nAll results saved to {OUT_ROOT}")


if __name__ == '__main__':
    main()

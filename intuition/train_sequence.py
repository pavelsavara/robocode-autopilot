"""LSTM / Sequence model training pipeline.

Two tasks:
  D. Movement prediction — predict opponent lateral velocity N ticks ahead
  E. Fire timing prediction — predict opponent fires within next K ticks

Includes a windowed-GBM baseline for each task so we can measure whether
sequential modeling adds value over aggregated window statistics.

Usage:
    cd intuition
    python train_sequence.py

Outputs saved to intuition/models/lstm_movement/ and lstm_fire_timing/
"""
from __future__ import annotations

import json
import sys
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.model_selection import GroupKFold

sys.path.insert(0, str(Path(__file__).parent))
from _loader import (
    build_robot_index,
    compute_strategic_axes,
    load_stratified,
)

warnings.filterwarnings('ignore', category=FutureWarning)

MODEL_ROOT = Path(__file__).parent / 'models'

# Window configuration
WINDOW_LEN = 20  # ticks of context
MOVEMENT_HORIZONS = [5, 10, 20]  # predict lat-vel at t+N
FIRE_HORIZON = 3  # predict fire within next K ticks


# ---------------------------------------------------------------------------
# Feature columns for sequence windows
# ---------------------------------------------------------------------------

MOVEMENT_FEATURES = [
    'opponent_lateral_velocity',
    'opponent_advancing_velocity',
    'opponent_velocity_delta',
    'opponent_heading_delta',
    'opponent_is_decelerating',
    'distance_norm',
    'energy_ratio',
    'opponent_wall_ahead_distance',
    'opponent_dist_to_wall_min',
    'our_lateral_velocity',
]

FIRE_FEATURES = [
    'opponent_energy',
    'opponent_velocity',
    'opponent_lateral_velocity',
    'distance',
    'energy_ratio',
    'ticks_since_scan',
    'our_lateral_velocity',
    'our_dist_to_wall_min',
]


# ---------------------------------------------------------------------------
# Window construction
# ---------------------------------------------------------------------------

def _build_windows(
    ticks: pd.DataFrame,
    feature_cols: list[str],
    target_col: str,
    horizon: int,
    binary_target: bool = False,
    fire_horizon: int | None = None,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Build sliding-window arrays from ticks DataFrame.

    Returns (X, y, groups) where:
      X: [n_windows, WINDOW_LEN, n_features]
      y: [n_windows] (regression or binary)
      groups: [n_windows] battle_id strings
    """
    # Filter to existing columns
    avail_cols = [c for c in feature_cols if c in ticks.columns]
    if len(avail_cols) < len(feature_cols):
        missing = set(feature_cols) - set(avail_cols)
        print(f"    Warning: missing columns {missing}, using {len(avail_cols)} features")

    windows_X = []
    windows_y = []
    windows_group = []

    for (bid, rnd, robot), grp in ticks.groupby(['battle_id', 'round', 'robot_name']):
        bid = str(bid)
        grp = grp.sort_values('tick').reset_index(drop=True)
        n = len(grp)
        if n < WINDOW_LEN + horizon:
            continue

        # Extract feature matrix for this round
        feat_mat = grp[avail_cols].ffill().fillna(0).values.astype(np.float32)

        # Build sliding windows with stride 5 to reduce count
        max_start = n - WINDOW_LEN - horizon
        for i in range(0, max_start + 1, 5):  # stride=5
            window = feat_mat[i:i + WINDOW_LEN]

            if binary_target and fire_horizon is not None:
                # Fire timing: check if opponent_fired in next K ticks
                future_slice = grp.iloc[i + WINDOW_LEN:i + WINDOW_LEN + fire_horizon]
                if 'opponent_fired' not in future_slice.columns:
                    continue
                fired_vals = future_slice['opponent_fired'].dropna()
                if len(fired_vals) == 0:
                    continue
                y_val = int(fired_vals.max())
            else:
                # Movement: target is the lat-vel at t+horizon
                target_idx = i + WINDOW_LEN + horizon - 1
                if target_idx >= n:
                    continue
                if target_col not in grp.columns:
                    continue
                y_val = float(grp.iloc[target_idx][target_col])
                if np.isnan(y_val):
                    continue

            windows_X.append(window)
            windows_y.append(y_val)
            windows_group.append(bid)

    if not windows_X:
        return np.array([]), np.array([]), np.array([])

    X = np.stack(windows_X)
    y = np.array(windows_y, dtype=np.float32)
    groups = np.array(windows_group)

    # Final NaN/inf filter
    valid = np.isfinite(y) & np.all(np.isfinite(X.reshape(len(X), -1)), axis=1)
    if not valid.all():
        X = X[valid]
        y = y[valid]
        groups = groups[valid]

    return X, y, groups


def _window_statistics(X_3d: np.ndarray) -> np.ndarray:
    """Compute aggregated statistics per window for GBM baseline.

    Input: [n_windows, seq_len, n_features]
    Output: [n_windows, n_features * 6]  (mean, std, first, last, slope, range)
    """
    n, seq, nf = X_3d.shape
    stats = np.zeros((n, nf * 6), dtype=np.float32)
    t = np.arange(seq, dtype=np.float32)

    for f in range(nf):
        col = X_3d[:, :, f]  # [n, seq]
        stats[:, f * 6 + 0] = col.mean(axis=1)
        stats[:, f * 6 + 1] = col.std(axis=1)
        stats[:, f * 6 + 2] = col[:, 0]   # first
        stats[:, f * 6 + 3] = col[:, -1]  # last
        # slope (linear regression coefficient)
        t_centered = t - t.mean()
        slopes = (col * t_centered[None, :]).sum(axis=1) / (t_centered ** 2).sum()
        stats[:, f * 6 + 4] = slopes
        stats[:, f * 6 + 5] = col.max(axis=1) - col.min(axis=1)  # range

    return stats


# ---------------------------------------------------------------------------
# GBM baseline (windowed features)
# ---------------------------------------------------------------------------

def _train_gbm_baseline(X_3d: np.ndarray, y: np.ndarray, groups: np.ndarray,
                         task_name: str, is_classification: bool) -> dict:
    """Train XGBoost on window statistics as a baseline."""
    import xgboost as xgb

    X_flat = _window_statistics(X_3d)

    # Subsample if too large (GBM on 800k rows is slow for CV)
    max_samples = 100_000
    if len(X_flat) > max_samples:
        rng = np.random.RandomState(42)
        idx = rng.choice(len(X_flat), max_samples, replace=False)
        X_flat = X_flat[idx]
        y_sub = y[idx]
        groups_sub = groups[idx]
    else:
        y_sub = y
        groups_sub = groups

    # Filter NaN/inf from y
    valid = np.isfinite(y_sub)
    if not valid.all():
        X_flat = X_flat[valid]
        y_sub = y_sub[valid]
        groups_sub = groups_sub[valid]

    # Also filter NaN/inf from X_flat
    valid2 = np.all(np.isfinite(X_flat), axis=1)
    if not valid2.all():
        X_flat = X_flat[valid2]
        y_sub = y_sub[valid2]
        groups_sub = groups_sub[valid2]

    gkf = GroupKFold(n_splits=5)

    if is_classification:
        model = xgb.XGBClassifier(
            n_estimators=200, max_depth=6, learning_rate=0.05,
            subsample=0.8, colsample_bytree=0.8, n_jobs=-1,
            random_state=42, verbosity=0, eval_metric='logloss',
            scale_pos_weight=float((y_sub == 0).sum() / max(1, (y_sub == 1).sum())),
        )
        from sklearn.metrics import make_scorer, f1_score
        scoring = 'roc_auc'
    else:
        model = xgb.XGBRegressor(
            n_estimators=200, max_depth=6, learning_rate=0.05,
            subsample=0.8, colsample_bytree=0.8, n_jobs=-1,
            random_state=42, verbosity=0,
        )
        scoring = 'r2'

    from sklearn.model_selection import cross_val_score
    scores = cross_val_score(model, X_flat, y_sub, cv=gkf, groups=groups_sub, scoring=scoring)

    metrics = {
        'model': f'XGBoost-window-{task_name}',
        'n_samples': int(len(X_flat)),
        'n_features': X_flat.shape[1],
        f'cv_{scoring}_mean': float(np.mean(scores)),
        f'cv_{scoring}_std': float(np.std(scores)),
    }

    if not is_classification:
        mae_scores = -cross_val_score(
            model, X_flat, y_sub, cv=gkf, groups=groups_sub,
            scoring='neg_mean_absolute_error',
        )
        metrics['cv_mae_mean'] = float(np.mean(mae_scores))
        metrics['cv_mae_std'] = float(np.std(mae_scores))

    return metrics


# ---------------------------------------------------------------------------
# LSTM model
# ---------------------------------------------------------------------------

def _train_lstm(X_3d: np.ndarray, y: np.ndarray, groups: np.ndarray,
                task_name: str, is_classification: bool) -> dict:
    """Train LSTM on sequence windows."""
    import torch
    import torch.nn as nn
    from torch.utils.data import TensorDataset, DataLoader

    class SeqModel(nn.Module):
        def __init__(self, input_size: int, hidden_size: int = 64,
                     num_layers: int = 2, dropout: float = 0.2,
                     is_classifier: bool = False):
            super().__init__()
            self.lstm = nn.LSTM(input_size, hidden_size, num_layers,
                                batch_first=True, dropout=dropout if num_layers > 1 else 0)
            self.head = nn.Sequential(
                nn.Linear(hidden_size, 32),
                nn.ReLU(),
                nn.Linear(32, 1),
            )
            self.is_classifier = is_classifier

        def forward(self, x):
            _, (hn, _) = self.lstm(x)  # hn: [layers, batch, hidden]
            out = self.head(hn[-1])    # use last layer's hidden state
            return out.squeeze(-1)

    # Subsample for training speed
    max_samples = 200_000
    if len(X_3d) > max_samples:
        rng = np.random.RandomState(42)
        idx = rng.choice(len(X_3d), max_samples, replace=False)
        X_3d = X_3d[idx]
        y = y[idx]
        groups = groups[idx]

    n_features = X_3d.shape[2]
    gkf = GroupKFold(n_splits=5)
    fold_metrics = []

    for fold, (train_idx, val_idx) in enumerate(gkf.split(X_3d, y, groups)):
        X_train = torch.tensor(X_3d[train_idx], dtype=torch.float32)
        y_train = torch.tensor(y[train_idx], dtype=torch.float32)
        X_val = torch.tensor(X_3d[val_idx], dtype=torch.float32)
        y_val = y[val_idx]

        train_ds = TensorDataset(X_train, y_train)
        train_dl = DataLoader(train_ds, batch_size=512, shuffle=True)

        model = SeqModel(n_features, hidden_size=64, num_layers=2,
                         dropout=0.2, is_classifier=is_classification)

        if is_classification:
            pos_weight = float((y[train_idx] == 0).sum() / max(1, (y[train_idx] == 1).sum()))
            criterion = nn.BCEWithLogitsLoss(
                pos_weight=torch.tensor(min(pos_weight, 20.0)),
            )
        else:
            criterion = nn.HuberLoss()

        optimizer = torch.optim.Adam(model.parameters(), lr=1e-3, weight_decay=1e-4)
        scheduler = torch.optim.lr_scheduler.ReduceLROnPlateau(
            optimizer, patience=5, factor=0.5,
        )

        best_val_loss = float('inf')
        patience_counter = 0
        best_state = None

        for epoch in range(50):
            model.train()
            for xb, yb in train_dl:
                pred = model(xb)
                loss = criterion(pred, yb)
                optimizer.zero_grad()
                loss.backward()
                optimizer.step()

            # Validation
            model.eval()
            with torch.no_grad():
                val_pred = model(X_val)
                val_loss = criterion(val_pred, torch.tensor(y_val, dtype=torch.float32)).item()

            scheduler.step(val_loss)

            if val_loss < best_val_loss:
                best_val_loss = val_loss
                patience_counter = 0
                best_state = {k: v.clone() for k, v in model.state_dict().items()}
            else:
                patience_counter += 1
                if patience_counter >= 10:
                    break

        # Evaluate best model
        if best_state is not None:
            model.load_state_dict(best_state)
        model.eval()
        with torch.no_grad():
            val_pred = model(X_val).numpy()

        fm = {'fold': fold, 'epochs': epoch + 1}

        if is_classification:
            from sklearn.metrics import roc_auc_score, f1_score, precision_score, recall_score
            val_prob = 1.0 / (1.0 + np.exp(-val_pred))  # sigmoid
            val_pred_binary = (val_prob >= 0.5).astype(int)
            if len(np.unique(y_val)) > 1:
                fm['auc'] = float(roc_auc_score(y_val, val_prob))
            else:
                fm['auc'] = 0.5
            fm['f1'] = float(f1_score(y_val, val_pred_binary, zero_division=0))
            fm['precision'] = float(precision_score(y_val, val_pred_binary, zero_division=0))
            fm['recall'] = float(recall_score(y_val, val_pred_binary, zero_division=0))
            print(f"    Fold {fold}: AUC={fm['auc']:.4f}, F1={fm['f1']:.4f}, "
                  f"P={fm['precision']:.4f}, R={fm['recall']:.4f}")
        else:
            from sklearn.metrics import r2_score, mean_absolute_error
            fm['r2'] = float(r2_score(y_val, val_pred))
            fm['mae'] = float(mean_absolute_error(y_val, val_pred))
            mean_mae = float(mean_absolute_error(y_val, np.full_like(y_val, y_val.mean())))
            fm['mean_baseline_mae'] = mean_mae
            print(f"    Fold {fold}: R²={fm['r2']:.4f}, MAE={fm['mae']:.4f} "
                  f"(mean baseline MAE={mean_mae:.4f})")

        fold_metrics.append(fm)

    # Train final model
    X_all = torch.tensor(X_3d, dtype=torch.float32)
    y_all = torch.tensor(y, dtype=torch.float32)
    train_ds = TensorDataset(X_all, y_all)
    train_dl = DataLoader(train_ds, batch_size=512, shuffle=True)

    if is_classification:
        pw = float((y == 0).sum() / max(1, (y == 1).sum()))
        criterion = nn.BCEWithLogitsLoss(pos_weight=torch.tensor(min(pw, 20.0)))
    else:
        criterion = nn.HuberLoss()

    final_model = SeqModel(n_features, hidden_size=64, num_layers=2,
                           dropout=0.2, is_classifier=is_classification)
    optimizer = torch.optim.Adam(final_model.parameters(), lr=1e-3, weight_decay=1e-4)

    for epoch in range(30):
        final_model.train()
        for xb, yb in train_dl:
            pred = final_model(xb)
            loss = criterion(pred, yb)
            optimizer.zero_grad()
            loss.backward()
            optimizer.step()

    return fold_metrics, final_model


# ---------------------------------------------------------------------------
# Task D — Movement prediction
# ---------------------------------------------------------------------------

def train_movement(ticks: pd.DataFrame) -> dict:
    """Train LSTM + GBM baseline for lateral velocity prediction."""
    print("\n=== Task D: Movement Prediction (LSTM) ===")

    all_metrics = {}

    for horizon in MOVEMENT_HORIZONS:
        print(f"\n  --- Horizon N={horizon} ---")

        X, y, groups = _build_windows(
            ticks, MOVEMENT_FEATURES, 'opponent_lateral_velocity',
            horizon=horizon, binary_target=False,
        )
        if len(X) == 0:
            print("    No valid windows, skipping.")
            continue

        print(f"    Windows: {len(X):,}, shape: {X.shape}")

        # GBM baseline
        print("    Training GBM baseline...")
        gbm_metrics = _train_gbm_baseline(X, y, groups, f'movement_N{horizon}', False)
        print(f"    GBM R²: {gbm_metrics.get('cv_r2_mean', 'N/A')}, "
              f"MAE: {gbm_metrics.get('cv_mae_mean', 'N/A')}")

        # LSTM
        print("    Training LSTM...")
        fold_metrics, final_model = _train_lstm(X, y, groups, f'movement_N{horizon}', False)

        # Save
        out_dir = MODEL_ROOT / f'lstm_movement_N{horizon}'
        out_dir.mkdir(parents=True, exist_ok=True)

        import torch
        torch.save(final_model.state_dict(), out_dir / 'model.pt')

        avg = lambda key: float(np.mean([m[key] for m in fold_metrics if key in m]))
        std = lambda key: float(np.std([m[key] for m in fold_metrics if key in m]))

        metrics = {
            'task': f'movement_N{horizon}',
            'window_len': WINDOW_LEN,
            'horizon': horizon,
            'n_samples': int(len(X)),
            'n_features': X.shape[2],
            'feature_cols': [c for c in MOVEMENT_FEATURES if c in ticks.columns],
            'lstm_cv_r2_mean': avg('r2'),
            'lstm_cv_r2_std': std('r2'),
            'lstm_cv_mae_mean': avg('mae'),
            'lstm_cv_mae_std': std('mae'),
            'mean_baseline_mae': avg('mean_baseline_mae'),
            'gbm_baseline': gbm_metrics,
            'fold_details': fold_metrics,
        }

        with open(out_dir / 'metrics.json', 'w') as f:
            json.dump(metrics, f, indent=2, default=str)

        with open(out_dir / 'model_config.json', 'w') as f:
            json.dump({
                'input_size': X.shape[2],
                'hidden_size': 64,
                'num_layers': 2,
                'window_len': WINDOW_LEN,
                'horizon': horizon,
                'feature_cols': metrics['feature_cols'],
            }, f, indent=2)

        all_metrics[f'N{horizon}'] = metrics
        print(f"    LSTM R²: {metrics['lstm_cv_r2_mean']:.4f} ± {metrics['lstm_cv_r2_std']:.4f}")
        print(f"    LSTM MAE: {metrics['lstm_cv_mae_mean']:.4f}")

    return all_metrics


# ---------------------------------------------------------------------------
# Task E — Fire timing prediction
# ---------------------------------------------------------------------------

def train_fire_timing(ticks: pd.DataFrame) -> dict:
    """Train LSTM + GBM baseline for fire timing prediction."""
    print("\n=== Task E: Fire Timing Prediction (LSTM) ===")

    X, y, groups = _build_windows(
        ticks, FIRE_FEATURES, 'opponent_fired',
        horizon=FIRE_HORIZON, binary_target=True, fire_horizon=FIRE_HORIZON,
    )
    if len(X) == 0:
        print("  No valid windows, skipping.")
        return {}

    print(f"  Windows: {len(X):,}, shape: {X.shape}")
    pos_rate = float(y.mean())
    print(f"  Positive rate (fired within {FIRE_HORIZON} ticks): {pos_rate:.4f}")

    # GBM baseline
    print("  Training GBM baseline...")
    gbm_metrics = _train_gbm_baseline(X, y, groups, 'fire_timing', True)
    print(f"  GBM AUC: {gbm_metrics.get('cv_roc_auc_mean', 'N/A')}")

    # LSTM
    print("  Training LSTM...")
    fold_metrics, final_model = _train_lstm(X, y, groups, 'fire_timing', True)

    out_dir = MODEL_ROOT / 'lstm_fire_timing'
    out_dir.mkdir(parents=True, exist_ok=True)

    import torch
    torch.save(final_model.state_dict(), out_dir / 'model.pt')

    avg = lambda key: float(np.mean([m[key] for m in fold_metrics if key in m]))
    std = lambda key: float(np.std([m[key] for m in fold_metrics if key in m]))

    metrics = {
        'task': 'fire_timing',
        'window_len': WINDOW_LEN,
        'fire_horizon': FIRE_HORIZON,
        'n_samples': int(len(X)),
        'n_features': X.shape[2],
        'positive_rate': pos_rate,
        'feature_cols': [c for c in FIRE_FEATURES if c in ticks.columns],
        'lstm_cv_auc_mean': avg('auc'),
        'lstm_cv_auc_std': std('auc'),
        'lstm_cv_f1_mean': avg('f1'),
        'lstm_cv_precision_mean': avg('precision'),
        'lstm_cv_recall_mean': avg('recall'),
        'gbm_baseline': gbm_metrics,
        'fold_details': fold_metrics,
    }

    with open(out_dir / 'metrics.json', 'w') as f:
        json.dump(metrics, f, indent=2, default=str)

    with open(out_dir / 'model_config.json', 'w') as f:
        json.dump({
            'input_size': X.shape[2],
            'hidden_size': 64,
            'num_layers': 2,
            'window_len': WINDOW_LEN,
            'fire_horizon': FIRE_HORIZON,
            'feature_cols': metrics['feature_cols'],
        }, f, indent=2)

    print(f"  LSTM AUC: {metrics['lstm_cv_auc_mean']:.4f}")
    print(f"  LSTM F1:  {metrics['lstm_cv_f1_mean']:.4f}")
    return metrics


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    print("=" * 60)
    print("Sequence Model Pipeline — LSTM + GBM Baseline")
    print("=" * 60)

    csv_root = Path(__file__).parent / '..' / 'output' / 'csv'
    print(f"\nCSV root: {csv_root.resolve()}")

    selection = build_robot_index(
        csv_root=csv_root,
        max_robots=50,
        battles_per_robot=2,
        seed=42,
    )

    # Load ticks — need full rows for sliding windows. Use 2 battles per robot
    # to keep ~3M rows in memory.
    print("\nLoading ticks.csv (full rows, 50×2 battles)...")
    ticks = load_stratified('ticks.csv', selection, csv_root=csv_root, row_frac=1.0)

    # Add strategic axes as context features
    ticks = compute_strategic_axes(ticks)

    all_metrics = {}

    # Task D — Movement
    movement_metrics = train_movement(ticks)
    if movement_metrics:
        all_metrics['movement'] = movement_metrics

    # Task E — Fire timing
    fire_metrics = train_fire_timing(ticks)
    if fire_metrics:
        all_metrics['fire_timing'] = fire_metrics

    # Summary
    print(f"\n{'='*60}")
    print("SUMMARY — Sequence Models")
    print(f"{'='*60}")

    for task, m in all_metrics.items():
        if task == 'movement':
            for horizon, hm in m.items():
                print(f"  Movement {horizon}: "
                      f"LSTM R²={hm['lstm_cv_r2_mean']:.4f}, "
                      f"GBM R²={hm['gbm_baseline'].get('cv_r2_mean', 'N/A')}")
        elif task == 'fire_timing':
            print(f"  Fire timing: "
                  f"LSTM AUC={m['lstm_cv_auc_mean']:.4f}, "
                  f"GBM AUC={m['gbm_baseline'].get('cv_roc_auc_mean', 'N/A')}")

    # Save combined summary
    MODEL_ROOT.mkdir(parents=True, exist_ok=True)
    with open(MODEL_ROOT / 'sequence_summary.json', 'w') as f:
        json.dump(all_metrics, f, indent=2, default=str)
    print(f"\nAll results saved under {MODEL_ROOT}")


if __name__ == '__main__':
    main()

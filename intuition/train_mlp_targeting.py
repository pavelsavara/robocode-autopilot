"""MLP targeting pipeline — predict opponent GuessFactor distribution.

Trains a small MLP that maps per-wave situation features to a 61-bin
GuessFactor probability distribution.

Usage:
    cd intuition
    python train_mlp_targeting.py

Outputs saved to intuition/models/mlp_targeting/
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
    attach_opponent_bot,
)

warnings.filterwarnings('ignore', category=FutureWarning)

OUT_ROOT = Path(__file__).parent / 'models' / 'mlp_targeting'
GF_BINS = 61  # [-1, +1] in 61 bins


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _gf_to_bin(gf: np.ndarray) -> np.ndarray:
    """Convert GuessFactor [-1, +1] to bin index [0, 60]."""
    return np.clip(np.round((gf + 1) / 2 * (GF_BINS - 1)).astype(int), 0, GF_BINS - 1)


def _smooth_labels(bin_indices: np.ndarray, sigma: float = 2.0) -> np.ndarray:
    """Gaussian-smooth 1-hot labels around the true bin (vectorized)."""
    n = len(bin_indices)
    bins = np.arange(GF_BINS, dtype=np.float32)  # [61]
    # Broadcast: [n, 1] - [1, 61] -> [n, 61]
    diff = bins[None, :] - bin_indices[:, None].astype(np.float32)
    w = np.exp(-0.5 * (diff / sigma) ** 2)
    w /= w.sum(axis=1, keepdims=True)
    return w


def _build_wave_features(ticks: pd.DataFrame, waves: pd.DataFrame) -> tuple[np.ndarray, np.ndarray, np.ndarray, list[str]]:
    """Build (X, y_bin, groups) via vectorized merge (no iterrows)."""
    feature_cols = [
        'opponent_lateral_velocity', 'opponent_advancing_velocity',
        'distance', 'opponent_velocity_delta',
        'opponent_time_since_direction_change', 'opponent_time_since_velocity_change',
        'opponent_wall_ahead_distance', 'opponent_dist_to_wall_min',
        'our_lateral_velocity', 'energy_ratio',
        'opponent_avg_lateral_velocity_10', 'opponent_heading_delta_variability_10',
        'opponent_distance_since_direction_change', 'distance_norm',
        # Multi-wave pressure dimensions (from nb13)
        'n_opponent_waves_in_flight', 'n_our_waves_in_flight',
        # Strategic axes (slow-moving context)
        'axis_aggression', 'axis_preferred_range',
        'axis_opponent_family', 'axis_game_phase',
    ]
    feature_cols = [c for c in feature_cols if c in ticks.columns]

    gf_col = 'gf_current_at_power_2' if 'gf_current_at_power_2' in ticks.columns else 'opponent_guess_factor'
    if gf_col not in ticks.columns:
        print("    ERROR: No GF column in ticks")
        return np.array([]), np.array([]), np.array([]), []

    # Prepare waves
    w = waves.dropna(subset=['wave_flight_time']).copy()
    w['fire_tick'] = w['tick'].astype(int)
    w['break_tick'] = (w['fire_tick'] + w['wave_flight_time'].round()).astype(int)

    # Prepare ticks — rename feature cols with prefix for fire-tick merge
    fire_rename = {c: f'f_{c}' for c in feature_cols}
    tick_fire = ticks[['battle_id', 'round', 'robot_name', 'tick'] + feature_cols].copy()
    tick_fire['tick'] = tick_fire['tick'].astype(int)
    tick_fire = tick_fire.rename(columns=fire_rename)

    # Prepare ticks for break-tick GF merge
    tick_break = ticks[['battle_id', 'round', 'robot_name', 'tick', gf_col]].copy()
    tick_break['tick'] = tick_break['tick'].astype(int)
    tick_break = tick_break.rename(columns={gf_col: 'break_gf'})

    # Merge 1: fire-tick features
    print("    Merging fire-tick features...")
    m = w.merge(tick_fire, left_on=['battle_id', 'round', 'robot_name', 'fire_tick'],
                right_on=['battle_id', 'round', 'robot_name', 'tick'], how='inner')
    print(f"    Fire-tick matches: {len(m):,} / {len(w):,}")

    # Merge 2: break-tick GF
    print("    Merging break-tick GF...")
    m = m.merge(tick_break, left_on=['battle_id', 'round', 'robot_name', 'break_tick'],
                right_on=['battle_id', 'round', 'robot_name', 'tick'], how='inner',
                suffixes=('', '_brk'))
    m = m.dropna(subset=['break_gf'])
    print(f"    Final samples: {len(m):,}")

    if len(m) == 0:
        return np.array([]), np.array([]), np.array([]), []

    # Build arrays
    all_feature_cols = ['bullet_power', 'bullet_flight_time'] + feature_cols
    arrs = [m['wave_bullet_power'].fillna(0).values.astype(np.float32),
            m['wave_flight_time'].fillna(0).values.astype(np.float32)]
    for c in feature_cols:
        arrs.append(m[f'f_{c}'].fillna(0).values.astype(np.float32))
    X = np.column_stack(arrs)

    gf_vals = m['break_gf'].values.astype(np.float32).clip(-1, 1)
    y_bin = _gf_to_bin(gf_vals)
    groups = m['battle_id'].astype(str).values
    return X, y_bin, groups, all_feature_cols


# ---------------------------------------------------------------------------
# PyTorch model
# ---------------------------------------------------------------------------

def _train_pytorch(X: np.ndarray, y_bin: np.ndarray, groups: np.ndarray,
                   feature_cols: list[str]) -> dict:
    """Train MLP with PyTorch, evaluate via GroupKFold."""
    import torch
    import torch.nn as nn
    from torch.utils.data import TensorDataset, DataLoader

    class GFNet(nn.Module):
        def __init__(self, n_features: int, n_bins: int = GF_BINS):
            super().__init__()
            self.net = nn.Sequential(
                nn.Linear(n_features, 128),
                nn.ReLU(),
                nn.Dropout(0.2),
                nn.Linear(128, 128),
                nn.ReLU(),
                nn.Dropout(0.2),
                nn.Linear(128, 64),
                nn.ReLU(),
                nn.Linear(64, n_bins),
            )

        def forward(self, x):
            return self.net(x)

    n_features = X.shape[1]

    # Smooth labels
    y_smooth = _smooth_labels(y_bin, sigma=2.0)

    gkf = GroupKFold(n_splits=3)
    fold_metrics = []

    for fold, (train_idx, val_idx) in enumerate(gkf.split(X, y_bin, groups)):
        X_train = torch.tensor(X[train_idx], dtype=torch.float32)
        y_train = torch.tensor(y_smooth[train_idx], dtype=torch.float32)
        X_val = torch.tensor(X[val_idx], dtype=torch.float32)
        y_val_bin = y_bin[val_idx]

        train_ds = TensorDataset(X_train, y_train)
        train_dl = DataLoader(train_ds, batch_size=1024, shuffle=True)

        model = GFNet(n_features)
        optimizer = torch.optim.Adam(model.parameters(), lr=1e-3, weight_decay=1e-4)
        scheduler = torch.optim.lr_scheduler.ReduceLROnPlateau(
            optimizer, patience=5, factor=0.5,
        )

        # Training
        best_val_loss = float('inf')
        patience_counter = 0
        best_state = None

        for epoch in range(30):
            model.train()
            epoch_loss = 0.0
            n_batches = 0
            for xb, yb in train_dl:
                logits = model(xb)
                # KL divergence loss (smooth labels)
                log_probs = torch.log_softmax(logits, dim=1)
                loss = -(yb * log_probs).sum(dim=1).mean()
                optimizer.zero_grad()
                loss.backward()
                optimizer.step()
                epoch_loss += loss.item()
                n_batches += 1

            # Validation
            model.eval()
            with torch.no_grad():
                val_logits = model(X_val)
                val_log_probs = torch.log_softmax(val_logits, dim=1)
                val_probs = torch.softmax(val_logits, dim=1)

                # Cross-entropy against smooth labels
                y_val_smooth = torch.tensor(
                    _smooth_labels(y_val_bin, sigma=2.0), dtype=torch.float32,
                )
                val_loss = -(y_val_smooth * val_log_probs).sum(dim=1).mean().item()

            scheduler.step(val_loss)

            if val_loss < best_val_loss:
                best_val_loss = val_loss
                patience_counter = 0
                best_state = {k: v.clone() for k, v in model.state_dict().items()}
            else:
                patience_counter += 1
                if patience_counter >= 8:
                    break

        # Evaluate best model
        if best_state is not None:
            model.load_state_dict(best_state)
        model.eval()
        with torch.no_grad():
            val_logits = model(X_val)
            val_probs = torch.softmax(val_logits, dim=1).numpy()

        # Metrics
        pred_bins = val_probs.argmax(axis=1)
        gf_error = np.abs(pred_bins - y_val_bin) / (GF_BINS - 1) * 2  # scale to GF units
        mean_gf_error = float(np.mean(gf_error))

        # Top-1 accuracy (predicted bin == actual bin ± 1)
        within_1 = float(np.mean(np.abs(pred_bins - y_val_bin) <= 1))
        within_3 = float(np.mean(np.abs(pred_bins - y_val_bin) <= 3))
        exact = float(np.mean(pred_bins == y_val_bin))

        # Cross-entropy (vs uniform baseline = log(61) ≈ 4.11)
        ce_loss = best_val_loss

        fold_metrics.append({
            'fold': fold,
            'val_loss': ce_loss,
            'mean_gf_error': mean_gf_error,
            'exact_bin_accuracy': exact,
            'within_1_accuracy': within_1,
            'within_3_accuracy': within_3,
            'epochs_trained': epoch + 1,
        })
        print(f"    Fold {fold}: loss={ce_loss:.4f}, GF_err={mean_gf_error:.4f}, "
              f"exact={exact:.4f}, ±1={within_1:.4f}, ±3={within_3:.4f}")

    # Train final model on all data
    X_all = torch.tensor(X, dtype=torch.float32)
    y_all = torch.tensor(y_smooth, dtype=torch.float32)
    train_ds = TensorDataset(X_all, y_all)
    train_dl = DataLoader(train_ds, batch_size=1024, shuffle=True)

    final_model = GFNet(n_features)
    optimizer = torch.optim.Adam(final_model.parameters(), lr=1e-3, weight_decay=1e-4)

    for epoch in range(20):
        final_model.train()
        for xb, yb in train_dl:
            logits = final_model(xb)
            log_probs = torch.log_softmax(logits, dim=1)
            loss = -(yb * log_probs).sum(dim=1).mean()
            optimizer.zero_grad()
            loss.backward()
            optimizer.step()

    # Save model
    OUT_ROOT.mkdir(parents=True, exist_ok=True)
    torch.save(final_model.state_dict(), OUT_ROOT / 'model.pt')

    # Save config
    config = {
        'n_features': n_features,
        'n_bins': GF_BINS,
        'architecture': [n_features, 128, 128, 64, GF_BINS],
        'feature_cols': feature_cols,
        'label_sigma': 2.0,
    }
    with open(OUT_ROOT / 'model_config.json', 'w') as f:
        json.dump(config, f, indent=2)

    # Aggregate metrics
    avg = lambda key: float(np.mean([m[key] for m in fold_metrics]))
    std = lambda key: float(np.std([m[key] for m in fold_metrics]))

    metrics = {
        'task': 'gf_targeting',
        'model': 'MLP [in→128→128→64→61]',
        'n_samples': int(len(X)),
        'n_features': n_features,
        'cv_loss_mean': avg('val_loss'),
        'cv_loss_std': std('val_loss'),
        'uniform_baseline_loss': float(np.log(GF_BINS)),
        'cv_gf_error_mean': avg('mean_gf_error'),
        'cv_gf_error_std': std('mean_gf_error'),
        'cv_exact_bin_acc_mean': avg('exact_bin_accuracy'),
        'cv_within_1_acc_mean': avg('within_1_accuracy'),
        'cv_within_3_acc_mean': avg('within_3_accuracy'),
        'fold_details': fold_metrics,
        'feature_cols': feature_cols,
    }

    with open(OUT_ROOT / 'metrics.json', 'w') as f:
        json.dump(metrics, f, indent=2, default=str)

    return metrics


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    print("=" * 60)
    print("MLP Targeting Pipeline — GuessFactor Distribution")
    print("=" * 60)

    csv_root = Path(__file__).parent / '..' / 'output' / 'csv'
    print(f"\nCSV root: {csv_root.resolve()}")

    selection = build_robot_index(
        csv_root=csv_root,
        max_robots=50,
        battles_per_robot=3,
        seed=42,
    )

    # Load ticks — we need full rows only around wave fire/break ticks,
    # but load at 20% sample to stay in memory. Some wave-tick matches will be
    # missed, which is acceptable.
    print("\nLoading ticks.csv (20% row sample for wave matching)...")
    ticks = load_stratified('ticks.csv', selection, csv_root=csv_root, row_frac=0.20)

    # Add strategic axes as context features
    ticks = compute_strategic_axes(ticks)

    print("\nLoading waves.csv...")
    waves = load_stratified('waves.csv', selection, csv_root=csv_root)

    # Build wave features
    print("\nBuilding wave feature/label pairs...")
    X, y_bin, groups, feature_cols = _build_wave_features(ticks, waves)

    if len(X) == 0:
        print("ERROR: No valid wave-to-tick matches found.")
        return

    print(f"  Valid wave samples: {len(X):,}")
    print(f"  Features: {len(feature_cols)}")
    print(f"  GF bin distribution: min={y_bin.min()}, max={y_bin.max()}, "
          f"median={np.median(y_bin):.0f}")

    # Skip augmentation for initial training speed
    # Subsample to 30k for fast CPU training
    max_train = 30_000
    if len(X) > max_train:
        rng = np.random.RandomState(42)
        idx = rng.choice(len(X), max_train, replace=False)
        X_aug = X[idx]
        y_aug = y_bin[idx]
        groups_aug = groups[idx]
        print(f"  Subsampled to {max_train:,} for fast training")
    else:
        X_aug = X
        y_aug = y_bin
        groups_aug = groups

    # Train
    print("\nTraining MLP...")
    metrics = _train_pytorch(X_aug, y_aug, groups_aug, feature_cols)

    print(f"\n{'='*60}")
    print("SUMMARY — MLP GF Targeting")
    print(f"{'='*60}")
    print(f"  Loss:           {metrics['cv_loss_mean']:.4f} ± {metrics['cv_loss_std']:.4f} "
          f"(uniform: {metrics['uniform_baseline_loss']:.4f})")
    print(f"  Mean GF error:  {metrics['cv_gf_error_mean']:.4f}")
    print(f"  Exact bin acc:  {metrics['cv_exact_bin_acc_mean']:.4f}")
    print(f"  Within ±1 bin:  {metrics['cv_within_1_acc_mean']:.4f}")
    print(f"  Within ±3 bins: {metrics['cv_within_3_acc_mean']:.4f}")
    print(f"\nResults saved to {OUT_ROOT}")


if __name__ == '__main__':
    main()

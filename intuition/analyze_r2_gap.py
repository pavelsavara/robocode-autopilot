"""Analyze the fire power R² gap between offline (0.84) and in-game (0.48).

Reads all internal.csv files from output/local/csv/ and computes:
1. Overall in-game R² (fire events only)
2. Per-opponent R² breakdown
3. Prediction bias analysis
4. Distribution shift analysis
5. Root cause diagnosis
"""
import os, sys, json
from pathlib import Path
import numpy as np
import pandas as pd

CSV_ROOT = Path(__file__).parent.parent / "output" / "local" / "csv"

def load_all_internal():
    """Load all Autopilot internal.csv files with opponent names."""
    frames = []
    for battle_dir in CSV_ROOT.iterdir():
        if not battle_dir.is_dir():
            continue
        autopilot_dir = battle_dir / "Autopilot 0.1.0"
        if not autopilot_dir.is_dir():
            continue
        csv_path = autopilot_dir / "internal.csv"
        if not csv_path.exists() or csv_path.stat().st_size == 0:
            continue
        # Find opponent name from sibling dir
        opponent = None
        for sibling in battle_dir.iterdir():
            if sibling.is_dir() and sibling.name != "Autopilot 0.1.0":
                opponent = sibling.name
                break
        if opponent is None:
            continue
        try:
            df = pd.read_csv(csv_path, low_memory=False)
            df['opponent'] = opponent
            df['battle_id'] = battle_dir.name
            frames.append(df)
        except Exception:
            continue
    if not frames:
        print("ERROR: No internal.csv files found")
        sys.exit(1)
    return pd.concat(frames, ignore_index=True)

def compute_r2(y_true, y_pred):
    ss_res = np.sum((y_true - y_pred) ** 2)
    ss_tot = np.sum((y_true - np.mean(y_true)) ** 2)
    return 1.0 - ss_res / ss_tot if ss_tot > 0 else float('nan')

def main():
    print("Loading internal.csv files...")
    df = load_all_internal()
    print(f"  Total rows: {len(df):,}")
    print(f"  Battles: {df['battle_id'].nunique()}")
    print(f"  Opponents: {df['opponent'].nunique()}")

    # Coerce columns
    for col in ['opponent_fired', 'predicted_fire_power', 'opponent_fire_power']:
        df[col] = pd.to_numeric(df[col], errors='coerce')

    # Filter to fire events
    fire = df[df['opponent_fired'] == 1].dropna(subset=['predicted_fire_power', 'opponent_fire_power']).copy()
    print(f"  Fire events: {len(fire):,}")
    
    if len(fire) < 10:
        print("ERROR: Too few fire events")
        return

    y_true = fire['opponent_fire_power'].values
    y_pred = fire['predicted_fire_power'].values

    # ── 1. Overall R² ─────────────────────────
    r2 = compute_r2(y_true, y_pred)
    print(f"\n=== Overall In-Game R² ===")
    print(f"  R² = {r2:.4f}  (n={len(fire):,} fire events)")
    print(f"  Offline R² = 0.840  (from training CV)")
    print(f"  Gap = {0.840 - r2:.3f}")

    # ── 2. Per-opponent R² ─────────────────────
    print(f"\n=== Per-Opponent R² ===")
    opp_stats = []
    for opp, grp in fire.groupby('opponent'):
        n = len(grp)
        if n < 5:
            continue
        yt = grp['opponent_fire_power'].values
        yp = grp['predicted_fire_power'].values
        r2_opp = compute_r2(yt, yp)
        mae = np.mean(np.abs(yt - yp))
        mean_actual = np.mean(yt)
        mean_pred = np.mean(yp)
        std_actual = np.std(yt)
        bias = mean_pred - mean_actual
        opp_stats.append({
            'opponent': opp, 'n': n, 'r2': r2_opp,
            'mae': mae, 'mean_actual': mean_actual, 'mean_pred': mean_pred,
            'std_actual': std_actual, 'bias': bias
        })
    opp_df = pd.DataFrame(opp_stats).sort_values('r2')
    print(f"{'Opponent':<30} {'N':>6} {'R²':>8} {'MAE':>7} {'Actual':>7} {'Pred':>7} {'Bias':>7} {'StdAct':>7}")
    print("-" * 110)
    for _, row in opp_df.iterrows():
        print(f"{row['opponent']:<30} {row['n']:>6} {row['r2']:>8.3f} {row['mae']:>7.3f} {row['mean_actual']:>7.3f} {row['mean_pred']:>7.3f} {row['bias']:>7.3f} {row['std_actual']:>7.3f}")
    
    # Summary
    print(f"\n  Mean per-opponent R²: {opp_df['r2'].mean():.4f}")
    print(f"  Median per-opponent R²: {opp_df['r2'].median():.4f}")
    print(f"  Worst: {opp_df.iloc[0]['opponent']} (R²={opp_df.iloc[0]['r2']:.3f})")
    print(f"  Best: {opp_df.iloc[-1]['opponent']} (R²={opp_df.iloc[-1]['r2']:.3f})")
    
    # ── 3. Prediction bias ─────────────────────
    print(f"\n=== Prediction Bias Analysis ===")
    overall_bias = np.mean(y_pred) - np.mean(y_true)
    print(f"  Mean actual fire power: {np.mean(y_true):.4f}")
    print(f"  Mean predicted fire power: {np.mean(y_pred):.4f}")
    print(f"  Overall bias: {overall_bias:+.4f}")
    print(f"  Std actual: {np.std(y_true):.4f}")
    print(f"  Std predicted: {np.std(y_pred):.4f}")
    
    # Prediction distribution
    print(f"\n  Prediction range: [{np.min(y_pred):.3f}, {np.max(y_pred):.3f}]")
    print(f"  Actual range: [{np.min(y_true):.3f}, {np.max(y_true):.3f}]")
    
    # ── 4. Fire power bucket analysis ──────────
    print(f"\n=== Fire Power Bucket Analysis ===")
    fire['actual_bucket'] = pd.cut(fire['opponent_fire_power'], 
                                    bins=[0, 0.5, 1.0, 1.5, 2.0, 2.5, 3.01],
                                    labels=['0-0.5', '0.5-1', '1-1.5', '1.5-2', '2-2.5', '2.5-3'])
    bucket_stats = fire.groupby('actual_bucket', observed=True).agg(
        n=('opponent_fire_power', 'count'),
        mean_actual=('opponent_fire_power', 'mean'),
        mean_pred=('predicted_fire_power', 'mean'),
    ).reset_index()
    bucket_stats['bias'] = bucket_stats['mean_pred'] - bucket_stats['mean_actual']
    print(f"{'Bucket':<10} {'N':>6} {'MeanActual':>11} {'MeanPred':>11} {'Bias':>8}")
    print("-" * 50)
    for _, row in bucket_stats.iterrows():
        print(f"{row['actual_bucket']:<10} {row['n']:>6} {row['mean_actual']:>11.3f} {row['mean_pred']:>11.3f} {row['bias']:>8.3f}")

    # ── 5. Constant-power opponents ────────────
    print(f"\n=== Constant-Power Opponents ===")
    print("(Low variance = model can't improve over mean prediction)")
    const_opps = opp_df[opp_df['std_actual'] < 0.2].sort_values('std_actual')
    print(f"{'Opponent':<30} {'StdAct':>7} {'R²':>8} {'N':>6}")
    print("-" * 55)
    for _, row in const_opps.iterrows():
        print(f"{row['opponent']:<30} {row['std_actual']:>7.3f} {row['r2']:>8.3f} {row['n']:>6}")
    
    # ── 6. R² with and without constant-power opps ──────
    print(f"\n=== Impact of Constant-Power Opponents on R² ===")
    const_opp_names = set(const_opps['opponent'])
    fire_varied = fire[~fire['opponent'].isin(const_opp_names)]
    if len(fire_varied) > 10:
        r2_varied = compute_r2(fire_varied['opponent_fire_power'].values,
                               fire_varied['predicted_fire_power'].values)
        print(f"  R² without constant-power opponents: {r2_varied:.4f} (n={len(fire_varied):,})")
        print(f"  R² with all opponents: {r2:.4f} (n={len(fire):,})")
    
    # ── 7. Negative R² diagnosis ───────────────
    print(f"\n=== Negative R² Diagnosis ===")
    neg_opps = opp_df[opp_df['r2'] < 0]
    if len(neg_opps) > 0:
        print(f"  {len(neg_opps)} opponents have negative R² (model worse than mean)")
        for _, row in neg_opps.iterrows():
            print(f"    {row['opponent']}: R²={row['r2']:.3f}, bias={row['bias']:+.3f}, "
                  f"actual={row['mean_actual']:.3f}±{row['std_actual']:.3f}, pred={row['mean_pred']:.3f}")
    else:
        print("  No opponents with negative R²")

    # ── 8. Mean-predictor baseline comparison ──
    print(f"\n=== Mean-Predictor Baseline ===")
    global_mean = np.mean(y_true)
    r2_mean = compute_r2(y_true, np.full_like(y_true, global_mean))
    print(f"  R² of predicting global mean ({global_mean:.3f}) for everyone: {r2_mean:.4f}")
    print(f"  R² of in-game model: {r2:.4f}")
    print(f"  Model improvement over mean baseline: {r2 - r2_mean:+.4f}")

    # ── 9. Per-opponent mean baseline ──────────
    print(f"\n=== Per-Opponent Mean Baseline ===")
    fire['opp_mean_pred'] = fire.groupby('opponent')['opponent_fire_power'].transform('mean')
    r2_opp_mean = compute_r2(fire['opponent_fire_power'].values, fire['opp_mean_pred'].values)
    print(f"  R² of per-opponent mean prediction: {r2_opp_mean:.4f}")
    print(f"  R² of in-game model: {r2:.4f}")
    
    # ── Summary ───────────────────────────────
    print(f"\n{'='*60}")
    print(f"SUMMARY")
    print(f"{'='*60}")
    print(f"  Overall in-game R²: {r2:.4f}")
    print(f"  Offline CV R²: 0.840")
    print(f"  Gap: {0.840 - r2:.3f}")
    print(f"  Model base score (intercept): 1.7988")
    print(f"  Global mean actual: {np.mean(y_true):.4f}")
    print(f"  Global mean predicted: {np.mean(y_pred):.4f}")
    print(f"  Overall bias: {overall_bias:+.4f}")
    print(f"  Opponents with R²<0: {len(neg_opps)}")
    print(f"  Constant-power opponents (std<0.2): {len(const_opps)}")

if __name__ == '__main__':
    main()

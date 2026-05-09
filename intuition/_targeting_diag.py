"""Phase 4 targeting diagnostics — gun selection & accuracy analysis."""
import pandas as pd
import numpy as np
import os
import json

# Load summary.json for opponent names
with open(r'D:\robocode-autopilot\output\local\results\summary.json') as f:
    summary = json.load(f)

# Build battle_id -> opponent name mapping
battle_opponents = {}
for entry in summary:
    bid = entry.get('battle_id', '')
    opp = entry['bot_a']['name']
    battle_opponents[bid] = opp

# Scan all Autopilot internal.csv files
base = r'D:\robocode-autopilot\output\local\csv'
battle_dirs = [d for d in os.listdir(base) if os.path.isdir(os.path.join(base, d))]

frames = []
scores_frames = []
for bd in battle_dirs:
    auto_dir = os.path.join(base, bd, 'Autopilot 0.1.0')
    int_csv = os.path.join(auto_dir, 'internal.csv')
    sc_csv = os.path.join(auto_dir, 'scores.csv')
    try:
        if os.path.exists(int_csv) and os.path.getsize(int_csv) > 10:
            df = pd.read_csv(int_csv, engine='python', on_bad_lines='skip')
            df['battle_id'] = bd
            df['opponent'] = battle_opponents.get(bd, 'unknown')
            frames.append(df)
    except Exception as e:
        print(f"  WARN: skipping internal.csv in {bd}: {e}")
    try:
        if os.path.exists(sc_csv) and os.path.getsize(sc_csv) > 10:
            sdf = pd.read_csv(sc_csv, engine='python', on_bad_lines='skip')
            sdf['battle_id'] = bd
            sdf['opponent'] = battle_opponents.get(bd, 'unknown')
            scores_frames.append(sdf)
    except Exception as e:
        print(f"  WARN: skipping scores.csv in {bd}: {e}")

internal = pd.concat(frames, ignore_index=True)
scores = pd.concat(scores_frames, ignore_index=True)
print(f"Loaded {len(frames)} battles, {len(internal):,} internal rows, {len(scores):,} score rows")
print(f"Unique opponents: {internal['opponent'].nunique()}")

# ========== GUN NAMES ==========
gun_names = {0: 'HeadOnGun', 1: 'LinearGun', 2: 'CircularGun', 3: 'PredictiveGun', 4: 'GFGun'}

# ========== GUN SELECTION DISTRIBUTION ==========
print("\n=== GUN SELECTION DISTRIBUTION (selected_gun_idx) ===")
sel = internal['selected_gun_idx'].value_counts().sort_index()
total_ticks = len(internal)
for idx, count in sel.items():
    name = gun_names.get(int(idx), f'Gun{int(idx)}')
    pct = 100 * count / total_ticks
    print(f"  {name} (idx={int(idx)}): {count:,} ticks ({pct:.1f}%)")

# ========== PER-GUN VIRTUAL HIT RATES (end-of-round cumulative) ==========
print("\n=== VIRTUAL GUN HIT RATES (end-of-round cumulative avg) ===")
gun_hrs = {}
for i in range(5):
    col = f'gun_hr_{i}'
    if col in internal.columns:
        last = internal.groupby(['battle_id', 'round'])[col].last()
        mean_hr = last.mean()
        gun_hrs[i] = mean_hr
        name = gun_names.get(i, f'Gun{i}')
        print(f"  {name}: {mean_hr*100:.2f}%")

best_gun = max(gun_hrs, key=gun_hrs.get) if gun_hrs else -1
print(f"\n  >> Best virtual gun: {gun_names.get(best_gun, '?')} ({gun_hrs[best_gun]*100:.2f}%)")

# ========== GUN SELECTION vs BEST GUN ==========
print("\n=== GUN SELECTION CORRECTNESS ===")
# Per round: was the selected gun (mode) the one with the highest HR?
round_groups = internal.groupby(['battle_id', 'round'])
correct = 0
total_rounds = 0
for (bid, rnd), grp in round_groups:
    total_rounds += 1
    # Most frequently selected gun this round
    mode_gun = int(grp['selected_gun_idx'].mode().iloc[0])
    # Best HR gun at end of round
    end_row = grp.iloc[-1]
    hrs = {i: end_row[f'gun_hr_{i}'] for i in range(5)}
    best = max(hrs, key=hrs.get)
    if mode_gun == best:
        correct += 1
pct_correct = 100 * correct / total_rounds if total_rounds else 0
print(f"  Rounds where most-selected gun = best-HR gun: {correct}/{total_rounds} ({pct_correct:.1f}%)")

# ========== OUR HIT RATE (from scores.csv) ==========
print("\n=== OUR HIT RATE (from scores.csv) ===")
hr_col = None
for c in scores.columns:
    if 'our_hit_rate' in c:
        hr_col = c
        break

if hr_col:
    per_battle = scores.groupby('battle_id')[hr_col].mean()
    overall = per_battle.mean()
    print(f"  Overall hit rate: {overall*100:.2f}%")

    # Per-opponent
    opp_hr = scores.groupby('opponent')[hr_col].mean().sort_values(ascending=False)
    print(f"\n  Per-opponent hit rates (top 15):")
    for opp, hr in opp_hr.head(15).items():
        print(f"    {opp:40s} {hr*100:.1f}%")
    print(f"\n  Per-opponent hit rates (bottom 15):")
    for opp, hr in opp_hr.tail(15).items():
        print(f"    {opp:40s} {hr*100:.1f}%")
else:
    print("  our_hit_rate not found in scores.csv")
    print("  Score columns:", list(scores.columns))

# ========== CUMULATIVE HIT RATE (from internal.csv) ==========
print("\n=== CUMULATIVE HIT RATE (from internal.csv last tick) ===")
if 'cumulative_our_hit_rate' in internal.columns:
    last_ticks = internal.groupby(['battle_id', 'round'])['cumulative_our_hit_rate'].last()
    cum_hr = last_ticks.mean()
    print(f"  Mean end-of-round cumulative hit rate: {cum_hr*100:.2f}%")

    # Cumulative shots fired
    shots = internal.groupby(['battle_id', 'round'])['cumulative_our_shots_fired'].last()
    print(f"  Mean end-of-round shots fired: {shots.mean():.1f}")

# ========== GUN SELECTION PER OPPONENT ==========
print("\n=== GUN SELECTION BY OPPONENT (top gun per opponent) ===")
gun_by_opp = internal.groupby('opponent')['selected_gun_idx'].apply(
    lambda x: x.value_counts().idxmax()
).sort_values()
for opp, gidx in gun_by_opp.items():
    name = gun_names.get(int(gidx), f'Gun{int(gidx)}')
    # Get the distribution
    opp_data = internal[internal['opponent'] == opp]
    dist = opp_data['selected_gun_idx'].value_counts(normalize=True).sort_index()
    dist_str = ", ".join([f"{gun_names.get(int(k),'?')}:{v*100:.0f}%" for k, v in dist.items() if v > 0.01])
    print(f"  {opp:40s} primary={name:15s} [{dist_str}]")

# ========== COMPARISON WITH PREVIOUS SPRINT ==========
print("\n=== COMPARISON WITH PREVIOUS SPRINT ===")
print("  Previous sprint (retro-5): 8.1% hit rate")
if hr_col:
    print(f"  Current sprint:            {overall*100:.2f}% hit rate")
    delta = overall * 100 - 8.1
    direction = "UP" if delta > 0 else "DOWN"
    print(f"  Delta: {delta:+.2f}pp ({direction})")

# ========== FIRE POWER DISTRIBUTION ==========
print("\n=== FIRE POWER BUDGET DISTRIBUTION ===")
if 'fire_power_budget' in internal.columns:
    fpb = internal['fire_power_budget']
    print(f"  Mean: {fpb.mean():.3f}")
    print(f"  Median: {fpb.median():.3f}")
    print(f"  Std: {fpb.std():.3f}")
    print(f"  Min: {fpb.min():.3f}, Max: {fpb.max():.3f}")
    # How many ticks have fire_power_budget > 0 (i.e., we're willing to fire)
    firing_pct = (fpb > 0).mean() * 100
    print(f"  Ticks with fire_power > 0: {firing_pct:.1f}%")

# ========== TICKS SINCE WE FIRED ==========
print("\n=== FIRING FREQUENCY ===")
if 'ticks_since_we_fired' in internal.columns:
    tsf = internal['ticks_since_we_fired']
    # Fire events = where ticks_since_we_fired resets to small value
    fires = internal[internal['ticks_since_we_fired'] == 0]
    print(f"  Total fire events (ticks_since_we_fired==0): {len(fires):,}")
    print(f"  Average ticks between fires: {tsf.mean():.1f}")

print("\n=== ANALYSIS COMPLETE ===")

"""Phase 4 targeting diagnostics — gun selection & accuracy."""
import pandas as pd
import numpy as np
import os
import json
import sys

base = r'D:\robocode-autopilot\output\local\csv'

# Load summary.json for opponent names
with open(r'D:\robocode-autopilot\output\local\results\summary.json') as f:
    summary = json.load(f)

battle_opponents = {}
for entry in summary:
    bid = entry.get('battle_id', '')
    battle_opponents[bid] = entry['bot_a']['name']

battle_dirs = sorted([d for d in os.listdir(base) if os.path.isdir(os.path.join(base, d))])

# Only read the columns we need from internal.csv
INT_COLS = ['round', 'tick', 'selected_gun_idx', 'gun_hr_0', 'gun_hr_1', 'gun_hr_2',
            'gun_hr_3', 'gun_hr_4', 'fire_power_budget', 'ticks_since_we_fired',
            'cumulative_our_hit_rate', 'cumulative_our_shots_fired',
            'cumulative_opponent_hit_rate', 'cumulative_opponent_shots_detected']

SC_COLS = ['round', 'our_hit_rate', 'opponent_hit_rate', 'win_rate',
           'damage_dealt', 'damage_received']

print(f"Scanning {len(battle_dirs)} battle directories...")
sys.stdout.flush()

frames = []
scores_frames = []
skipped = 0
for i, bd in enumerate(battle_dirs):
    auto_dir = os.path.join(base, bd, 'Autopilot 0.1.0')
    int_csv = os.path.join(auto_dir, 'internal.csv')
    sc_csv = os.path.join(auto_dir, 'scores.csv')

    try:
        if os.path.exists(int_csv):
            df = pd.read_csv(int_csv, usecols=lambda c: c in INT_COLS)
            df['battle_id'] = bd
            df['opponent'] = battle_opponents.get(bd, 'unknown')
            frames.append(df)
    except Exception as e:
        skipped += 1
        if skipped <= 5:
            print(f"  WARN: skip internal.csv {bd}: {e}")

    try:
        if os.path.exists(sc_csv):
            sdf = pd.read_csv(sc_csv, usecols=lambda c: c in (SC_COLS + ['round']))
            sdf['battle_id'] = bd
            sdf['opponent'] = battle_opponents.get(bd, 'unknown')
            scores_frames.append(sdf)
    except Exception as e:
        pass

    if (i + 1) % 50 == 0:
        print(f"  ...loaded {i+1}/{len(battle_dirs)} battles")
        sys.stdout.flush()

print(f"Loaded {len(frames)} battles ({skipped} skipped)")
sys.stdout.flush()

internal = pd.concat(frames, ignore_index=True)
scores = pd.concat(scores_frames, ignore_index=True) if scores_frames else pd.DataFrame()
print(f"Internal: {len(internal):,} rows | Scores: {len(scores):,} rows")
print(f"Opponents: {internal['opponent'].nunique()}")

gun_names = {0: 'HeadOnGun', 1: 'LinearGun', 2: 'CircularGun', 3: 'PredictiveGun', 4: 'GFGun'}

# ====== GUN SELECTION ======
print("\n" + "="*60)
print("SANITY CHECK #3 — GUN SELECTION")
print("="*60)
sel = internal['selected_gun_idx'].value_counts().sort_index()
total = len(internal)
for idx, count in sel.items():
    name = gun_names.get(int(idx), f'Gun{int(idx)}')
    print(f"  {name:20s} {count:>8,} ticks  ({100*count/total:5.1f}%)")

# ====== VIRTUAL GUN HIT RATES ======
print("\n--- Virtual Gun Hit Rates (end-of-round cumulative) ---")
gun_hrs = {}
for i in range(5):
    col = f'gun_hr_{i}'
    if col in internal.columns:
        last = internal.groupby(['battle_id', 'round'])[col].last()
        mean_hr = last.mean()
        gun_hrs[i] = mean_hr
        name = gun_names.get(i, f'Gun{i}')
        print(f"  {name:20s} {mean_hr*100:6.2f}%")

if gun_hrs:
    best = max(gun_hrs, key=gun_hrs.get)
    worst = min(gun_hrs, key=gun_hrs.get)
    print(f"\n  Best virtual gun:  {gun_names[best]} ({gun_hrs[best]*100:.2f}%)")
    print(f"  Worst virtual gun: {gun_names[worst]} ({gun_hrs[worst]*100:.2f}%)")

# ====== GUN SELECTION CORRECTNESS ======
print("\n--- Gun Selection Correctness ---")
# Vectorized: get last tick per round
last_idx = internal.groupby(['battle_id', 'round'])['tick'].idxmax()
last_rows = internal.loc[last_idx]
hr_cols = [f'gun_hr_{i}' for i in range(5)]
best_hr_gun = last_rows[hr_cols].values.argmax(axis=1)
mode_gun = internal.groupby(['battle_id', 'round'])['selected_gun_idx'].agg(lambda x: x.mode().iloc[0])
mode_arr = mode_gun.values
correct = (mode_arr == best_hr_gun).sum()
total_rounds = len(mode_arr)
print(f"  Rounds where selected gun = best-HR gun: {correct}/{total_rounds} ({100*correct/total_rounds:.1f}%)")

# ====== R02 — HIT RATES ======
print("\n" + "="*60)
print("R02 — GUN ACCURACY (HIT RATES)")
print("="*60)

# From cumulative in internal.csv
if 'cumulative_our_hit_rate' in internal.columns:
    last_ticks = internal.groupby(['battle_id', 'round'])['cumulative_our_hit_rate'].last()
    cum_hr = last_ticks.mean()
    print(f"  Our hit rate (cumulative, end-of-round avg): {cum_hr*100:.2f}%")

if 'cumulative_opponent_hit_rate' in internal.columns:
    last_opp = internal.groupby(['battle_id', 'round'])['cumulative_opponent_hit_rate'].last()
    opp_hr = last_opp.mean()
    print(f"  Opponent hit rate (cumulative):               {opp_hr*100:.2f}%")

if 'cumulative_our_shots_fired' in internal.columns:
    shots = internal.groupby(['battle_id', 'round'])['cumulative_our_shots_fired'].last()
    print(f"  Avg shots fired per round:                    {shots.mean():.1f}")

# From scores.csv
if not scores.empty and 'our_hit_rate' in scores.columns:
    battle_hr = scores.groupby('battle_id')['our_hit_rate'].mean()
    overall = battle_hr.mean()
    print(f"  Our hit rate (scores.csv, per-battle avg):    {overall*100:.2f}%")

    print("\n  Per-opponent hit rate:")
    opp_hr = scores.groupby('opponent')['our_hit_rate'].mean().sort_values(ascending=False)
    for opp, hr in opp_hr.items():
        print(f"    {opp:40s} {hr*100:5.1f}%")

# ====== R08 — GUN SELECTION BY OPPONENT ======
print("\n" + "="*60)
print("R08 — GUN SELECTION BY OPPONENT")
print("="*60)

gun_opp = internal.groupby(['opponent', 'selected_gun_idx']).size().unstack(fill_value=0)
for opp in sorted(gun_opp.index):
    row = gun_opp.loc[opp]
    total = row.sum()
    primary_idx = int(row.idxmax())
    primary_name = gun_names.get(primary_idx, '?')
    parts = []
    for gidx in row.index:
        frac = row[gidx] / total
        if frac > 0.01:
            parts.append(f"{gun_names.get(int(gidx),'?')[:4]}:{frac*100:.0f}%")
    print(f"  {opp:40s} primary={primary_name:15s} [{', '.join(parts)}]")

# ====== FIRE POWER ======
print("\n--- Fire Power Budget ---")
if 'fire_power_budget' in internal.columns:
    fpb = internal['fire_power_budget']
    print(f"  Mean: {fpb.mean():.3f}, Median: {fpb.median():.3f}, Max: {fpb.max():.3f}")
    firing = (fpb > 0).mean() * 100
    print(f"  Ticks with fire_power > 0: {firing:.1f}%")

# ====== COMPARISON ======
print("\n" + "="*60)
print("COMPARISON WITH PREVIOUS SPRINT (retro-5)")
print("="*60)
print("  Previous: 8.1% hit rate, 47.1% opp hit rate")
if 'cumulative_our_hit_rate' in internal.columns:
    print(f"  Current:  {cum_hr*100:.1f}% hit rate")
    print(f"  Delta:    {cum_hr*100 - 8.1:+.1f}pp")

print("\n=== DONE ===")

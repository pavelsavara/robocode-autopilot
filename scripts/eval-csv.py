"""
Quick CSV evaluation script for the local test loop.
Reads ticks.csv, waves.csv, scores.csv from a battle directory
and prints statistical properties.

Usage: python scripts/eval-csv.py <battle_dir>
  where battle_dir contains Autopilot/ and Opponent/ subdirectories
"""

import sys
import os
from pathlib import Path

def read_csv_simple(path):
    """Minimal CSV reader — no pandas dependency for speed."""
    if not os.path.exists(path):
        return None, []
    with open(path, 'r') as f:
        lines = f.readlines()
    if len(lines) < 2:
        return lines[0].strip().split(',') if lines else None, []
    headers = lines[0].strip().split(',')
    rows = [line.strip().split(',') for line in lines[1:] if line.strip()]
    return headers, rows


def parse_float(s):
    try:
        v = float(s)
        return v if v == v else None  # NaN check
    except (ValueError, TypeError):
        return None


def analyze_ticks(battle_dir, perspective):
    """Analyze ticks.csv for one perspective."""
    path = os.path.join(battle_dir, perspective, 'ticks.csv')
    headers, rows = read_csv_simple(path)
    if not rows:
        print(f"  {perspective}/ticks.csv: EMPTY or MISSING")
        return None

    n_rows = len(rows)
    n_cols = len(headers)

    # Count NaN per column
    nan_counts = {}
    for i, h in enumerate(headers):
        nan_count = sum(1 for row in rows if i < len(row) and row[i] == 'NaN')
        if nan_count > 0:
            nan_counts[h] = nan_count

    # Key features
    stats = {}
    for col_name in ['distance', 'our_energy', 'opponent_lateral_velocity']:
        if col_name in headers:
            idx = headers.index(col_name)
            values = [parse_float(row[idx]) for row in rows if idx < len(row)]
            values = [v for v in values if v is not None]
            if values:
                stats[col_name] = {
                    'mean': sum(values) / len(values),
                    'min': min(values),
                    'max': max(values),
                    'non_nan': len(values),
                }

    print(f"  {perspective}/ticks.csv: {n_rows} rows, {n_cols} columns")
    nan_total = sum(nan_counts.values())
    nan_pct = 100.0 * nan_total / (n_rows * n_cols) if n_rows * n_cols > 0 else 0
    print(f"    NaN cells: {nan_total}/{n_rows * n_cols} ({nan_pct:.1f}%)")
    if nan_counts:
        top_nan = sorted(nan_counts.items(), key=lambda x: -x[1])[:5]
        print(f"    Top NaN columns: {', '.join(f'{k}={v}' for k, v in top_nan)}")
    for col, s in stats.items():
        print(f"    {col}: mean={s['mean']:.1f}, range=[{s['min']:.1f}, {s['max']:.1f}], valid={s['non_nan']}/{n_rows}")

    return {'rows': n_rows, 'nan_pct': nan_pct, 'stats': stats}


def analyze_waves(battle_dir, perspective):
    """Analyze waves.csv."""
    path = os.path.join(battle_dir, perspective, 'waves.csv')
    headers, rows = read_csv_simple(path)
    if not rows:
        print(f"  {perspective}/waves.csv: EMPTY (no opponent fires detected)")
        return
    print(f"  {perspective}/waves.csv: {len(rows)} fire events")

    if 'fire_power' in headers:
        idx = headers.index('fire_power')
        powers = [parse_float(row[idx]) for row in rows if idx < len(row)]
        powers = [p for p in powers if p is not None]
        if powers:
            print(f"    fire_power: mean={sum(powers)/len(powers):.2f}, range=[{min(powers):.1f}, {max(powers):.1f}]")


def analyze_scores(battle_dir, perspective):
    """Analyze scores.csv."""
    path = os.path.join(battle_dir, perspective, 'scores.csv')
    headers, rows = read_csv_simple(path)
    if not rows:
        print(f"  {perspective}/scores.csv: EMPTY")
        return None

    results = []
    if 'result' in headers:
        idx = headers.index('result')
        results = [parse_float(row[idx]) for row in rows if idx < len(row)]
        results = [int(r) for r in results if r is not None]

    wins = results.count(1)
    losses = results.count(-1)
    ties = results.count(0)
    total = len(results)

    print(f"  {perspective}/scores.csv: {total} rounds — W:{wins} L:{losses} T:{ties}")
    if total > 0:
        win_rate = 100.0 * wins / total
        print(f"    Win rate: {win_rate:.0f}%")
    return {'wins': wins, 'losses': losses, 'ties': ties, 'total': total}


def main():
    if len(sys.argv) < 2:
        print("Usage: python scripts/eval-csv.py <battle_dir>")
        sys.exit(1)

    battle_dir = sys.argv[1]
    if not os.path.isdir(battle_dir):
        print(f"ERROR: Directory not found: {battle_dir}")
        sys.exit(1)

    # Find perspectives (subdirectories)
    perspectives = [d for d in os.listdir(battle_dir)
                    if os.path.isdir(os.path.join(battle_dir, d))]

    if not perspectives:
        print(f"ERROR: No perspective directories in {battle_dir}")
        sys.exit(1)

    print(f"Battle: {os.path.basename(battle_dir)}")
    print(f"Perspectives: {', '.join(perspectives)}")
    print()

    all_ok = True
    for perspective in sorted(perspectives):
        print(f"--- {perspective} ---")
        ticks_info = analyze_ticks(battle_dir, perspective)
        analyze_waves(battle_dir, perspective)
        scores_info = analyze_scores(battle_dir, perspective)
        print()

        # Sanity checks
        if ticks_info is None:
            print(f"  FAIL: No tick data for {perspective}")
            all_ok = False
        elif ticks_info['nan_pct'] > 80:
            print(f"  WARNING: Very high NaN rate ({ticks_info['nan_pct']:.0f}%) -- pipeline may be broken")
            all_ok = False

    if all_ok:
        print("OK: Pipeline sanity check PASSED")
    else:
        print("FAIL: Pipeline sanity check FAILED")
        sys.exit(1)


if __name__ == '__main__':
    main()

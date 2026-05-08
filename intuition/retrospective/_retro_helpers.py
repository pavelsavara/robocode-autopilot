"""Shared helpers for retrospective analysis notebooks.

All retrospective notebooks load data from output/local/csv/ and filter to
the Autopilot robot's perspective.
"""
from __future__ import annotations

import sys
from pathlib import Path

# Ensure the intuition/ directory is on sys.path so we can import _loader
_intuition_dir = Path(__file__).resolve().parent.parent
if str(_intuition_dir) not in sys.path:
    sys.path.insert(0, str(_intuition_dir))

from _loader import build_robot_index, load_stratified, numeric_feature_cols, BATTLE_CONSTANT_COLS

# Default root for local pipeline output
LOCAL_CSV_ROOT = _intuition_dir.parent / 'output' / 'local' / 'csv'


def load_local_data(
    filename: str = 'ticks.csv',
    row_frac: float = 1.0,
    autopilot_only: bool = True,
    max_robots: int = 200,
    battles_per_robot: int = 100,
    seed: int = 42,
    csv_root=None,
):
    """Load local pipeline CSV data, optionally filtering to Autopilot's perspective.

    Args:
        filename: Which CSV to load (ticks.csv, waves.csv, scores.csv).
        row_frac: Fraction of rows to sample (1.0 = all).
        autopilot_only: If True, filter to rows where observer_bot contains 'Autopilot'.
        max_robots: Max robots for index building.
        battles_per_robot: Max battles per robot for index building.
        seed: Random seed.
        csv_root: Override CSV root path.

    Returns:
        pandas DataFrame with the loaded data.
    """
    root = Path(csv_root) if csv_root else LOCAL_CSV_ROOT

    selection = build_robot_index(
        csv_root=root,
        max_robots=max_robots,
        battles_per_robot=battles_per_robot,
        seed=seed,
    )

    df = load_stratified(
        filename,
        selection,
        csv_root=root,
        row_frac=row_frac,
        seed=seed,
    )

    if autopilot_only and len(df) > 0:
        # Filter to Autopilot's perspective — check multiple possible column names
        filter_col = None
        for col in ['observer_bot', 'robot_name']:
            if col in df.columns:
                filter_col = col
                break

        if filter_col:
            mask = df[filter_col].str.contains('Autopilot', case=False, na=False)
            df = df[mask].reset_index(drop=True)
            if len(df) == 0:
                print("⚠ No rows found for Autopilot perspective. Check column values.")
        else:
            print("⚠ No observer_bot or robot_name column found; returning all rows.")

    return df


def load_local_scores(**kwargs):
    """Shortcut to load scores.csv from local pipeline."""
    return load_local_data(filename='scores.csv', **kwargs)


def load_local_ticks(**kwargs):
    """Shortcut to load ticks.csv from local pipeline."""
    return load_local_data(filename='ticks.csv', **kwargs)


def load_local_waves(**kwargs):
    """Shortcut to load waves.csv from local pipeline."""
    return load_local_data(filename='waves.csv', **kwargs)


def get_opponent_map(csv_root=None):
    """Build a mapping from battle_id to opponent name.

    Walks the CSV directory tree and for each battle, finds the perspective
    directory that does NOT belong to Autopilot. Returns dict {battle_id: opponent_name}.
    """
    root = Path(csv_root) if csv_root else LOCAL_CSV_ROOT
    opponent_map = {}
    if not root.exists():
        return opponent_map

    for battle_dir in root.iterdir():
        if not battle_dir.is_dir():
            continue
        battle_id = battle_dir.name
        for perspective_dir in battle_dir.iterdir():
            if perspective_dir.is_dir() and 'autopilot' not in perspective_dir.name.lower():
                opponent_map[battle_id] = perspective_dir.name
                break
    return opponent_map


def add_opponent_names(df, csv_root=None):
    """Add an 'opponent_name' column to a DataFrame that has 'battle_id'."""
    if 'battle_id' not in df.columns:
        return df
    opp_map = get_opponent_map(csv_root)
    df = df.copy()
    df['opponent_name'] = df['battle_id'].map(opp_map).fillna('Unknown')
    return df

"""Shared CSV loader for intuition notebooks.

The full rumble dataset is ~1900 battles / ~3900 ticks.csv files / ~20 GB.
Naive `pd.concat` of every CSV in the tree OOMs VS Code's notebook host.

This module provides `load_stratified()` — pick top-N most-played robots,
N battles per robot, then optionally row-subsample each ticks.csv. Numeric
columns are downcast (`float64 -> float32`, `int64 -> smallest int`) so the
working set fits in ~300 MB.

Typical usage at the top of a notebook:

    import sys; sys.path.insert(0, '.')
    from _loader import load_stratified, build_robot_index

    selection = build_robot_index(max_robots=50, battles_per_robot=3, seed=42)
    ticks  = load_stratified('ticks.csv',  selection, row_frac=0.20)
    waves  = load_stratified('waves.csv',  selection)
    scores = load_stratified('scores.csv', selection)
"""
from __future__ import annotations

import random
from collections import defaultdict
from pathlib import Path
from typing import Iterable

import pandas as pd

CSV_ROOT_DEFAULT = Path('../output/csv')


def _downcast(df: pd.DataFrame) -> pd.DataFrame:
    """Shrink memory: float64 -> float32, int64 -> smallest signed int."""
    for c in df.select_dtypes(include=['float64']).columns:
        df[c] = df[c].astype('float32')
    for c in df.select_dtypes(include=['int64']).columns:
        df[c] = pd.to_numeric(df[c], downcast='integer')
    return df


def build_robot_index(
    csv_root: Path = CSV_ROOT_DEFAULT,
    max_robots: int = 50,
    battles_per_robot: int = 3,
    seed: int = 42,
    verbose: bool = True,
) -> set[tuple[str, str]]:
    """Pick a stratified subset of (battle_id, robot_name) pairs.

    Strategy:
      1. Walk the CSV tree once and group ticks.csv files by robot_name.
      2. Keep the top `max_robots` by appearance count (so each has enough data).
      3. Randomly pick `battles_per_robot` battles from each kept robot.

    Returns a set of (battle_id, robot_name) tuples to load.
    """
    rng = random.Random(seed)
    robot_to_files: dict[str, list[tuple[str, Path]]] = defaultdict(list)
    for ticks_path in csv_root.rglob('ticks.csv'):
        robot_name = ticks_path.parent.name
        battle_id  = ticks_path.parent.parent.name
        robot_to_files[robot_name].append((battle_id, ticks_path))

    total_files = sum(len(v) for v in robot_to_files.values())
    if verbose:
        print(f"Indexed {total_files} ticks.csv files across "
              f"{len(robot_to_files)} distinct robots.")

    sorted_robots = sorted(robot_to_files.items(), key=lambda kv: -len(kv[1]))
    selected_robots = [name for name, _ in sorted_robots[:max_robots]]

    selection: set[tuple[str, str]] = set()
    for robot in selected_robots:
        files = robot_to_files[robot]
        picked = rng.sample(files, min(battles_per_robot, len(files)))
        for battle_id, _ in picked:
            selection.add((battle_id, robot))

    if verbose:
        print(f"Selected {len(selected_robots)} robots × ~{battles_per_robot} battles "
              f"= {len(selection)} (battle, robot) pairs to load.")
    return selection


def load_stratified(
    filename: str,
    selection: Iterable[tuple[str, str]],
    csv_root: Path = CSV_ROOT_DEFAULT,
    row_frac: float = 1.0,
    seed: int = 42,
    verbose: bool = True,
) -> pd.DataFrame:
    """Load `filename` from each (battle_id, robot) pair in `selection`.

    Args:
      filename:  e.g. 'ticks.csv', 'waves.csv', 'scores.csv'.
      selection: result of `build_robot_index()`.
      row_frac:  fraction of rows to keep per file (1.0 = keep all).
                 Use 0.1–0.2 for ticks.csv; keep 1.0 for waves/scores.
    """
    frames = []
    for battle_id, robot in sorted(selection):
        csv_path = csv_root / battle_id / robot / filename
        if not csv_path.exists():
            continue
        df = pd.read_csv(csv_path)
        if row_frac < 1.0 and len(df) > 0:
            df = df.sample(frac=row_frac, random_state=seed)
        df['robot_name'] = robot
        df = _downcast(df)
        frames.append(df)

    if not frames:
        if verbose:
            print(f"⚠ No {filename} files found for the given selection.")
        return pd.DataFrame()

    combined = pd.concat(frames, ignore_index=True)
    if verbose:
        mem_mb = combined.memory_usage(deep=True).sum() / 1024 ** 2
        n_robots = combined['robot_name'].nunique()
        print(f"Loaded {len(frames)} {filename} files → "
              f"{combined.shape[0]:,} rows × {combined.shape[1]} cols, "
              f"{n_robots} robots (~{mem_mb:.1f} MB)")
    return combined


def numeric_feature_cols(
    df: pd.DataFrame,
    extra_exclude: Iterable[str] = (),
) -> list[str]:
    """Return numeric feature columns, excluding metadata.

    Excluded by default:
      - row keys: battle_id, round, tick, scan_available, robot_name
      - identity hashes: opponent_name_hash, opponent_bot_id_hash,
        opponent_version_hash (these are categorical IDs, not behavioral signals —
        feeding them into a generic regressor lets the model memorize the bot
        instead of learning behavior).
      - fingerprinting labels: observer_bot, opponent_bot

    NOT excluded (kept as real features):
      - battlefield_width / battlefield_height / gun_cooling_rate /
        num_rounds_total — small-int per-battle constants with genuine predictive
        value (e.g. battlefield size affects optimal distance distribution).
        Pass them via `extra_exclude` if you want pure tick-level behavior.
    """
    meta = {
        'battle_id', 'round', 'tick', 'scan_available', 'robot_name',
        'opponent_name_hash', 'opponent_bot_id_hash', 'opponent_version_hash',
        'observer_bot', 'opponent_bot',
    }
    meta.update(extra_exclude)
    return [c for c in df.select_dtypes(include='number').columns if c not in meta]


# Columns in scores.csv that are constant across all rounds of a battle.
# Notebooks needing them on tick rows should merge via `attach_battle_constants`.
BATTLE_CONSTANT_COLS = (
    'opponent_name_hash',
    'opponent_bot_id_hash',
    'opponent_version_hash',
    'battlefield_width',
    'battlefield_height',
    'gun_cooling_rate',
    'num_rounds_total',
)


def attach_battle_constants(
    target: pd.DataFrame,
    scores: pd.DataFrame,
    cols: Iterable[str] = BATTLE_CONSTANT_COLS,
) -> pd.DataFrame:
    """Left-join per-battle constant columns from scores.csv onto another frame.

    `scores` is the result of `load_stratified('scores.csv', ...)`.  Each battle
    has multiple round rows but the constants are identical across rounds, so we
    deduplicate on `(battle_id, robot_name)` first.

    Use this when a ticks/waves notebook needs the bot-id hash, battlefield size,
    or gun cooling rate without bloating ticks.csv.
    """
    keep = ['battle_id', 'robot_name'] + [c for c in cols if c in scores.columns]
    const = scores[keep].drop_duplicates(['battle_id', 'robot_name'])
    return target.merge(const, on=['battle_id', 'robot_name'], how='left')

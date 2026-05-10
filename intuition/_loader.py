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

# Default CSV root. May be overridden per-call. To load multiple chunked
# download trees at once (e.g. output/csv-25250609244/csv-chunk-N/...), pass a
# list of roots to build_robot_index/load_stratified.
CSV_ROOT_DEFAULT = Path('../output/csv')


def _downcast(df: pd.DataFrame) -> pd.DataFrame:
    """Shrink memory: float64 -> float32, int64 -> smallest signed int."""
    for c in df.select_dtypes(include=['float64']).columns:
        df[c] = df[c].astype('float32')
    for c in df.select_dtypes(include=['int64']).columns:
        df[c] = pd.to_numeric(df[c], downcast='integer')
    return df


def _iter_roots(roots) -> list[Path]:
    """Normalize a single root or iterable of roots into a list of Paths."""
    if isinstance(roots, (str, Path)):
        return [Path(roots)]
    return [Path(r) for r in roots]


# Cache mapping (battle_id, robot_name) -> directory holding ticks/waves/scores.
# Populated by build_robot_index() and consumed by load_stratified() so we
# don't reconstruct paths and can support chunked / multi-root layouts.
_PATH_INDEX: dict[tuple[str, str], Path] = {}


def build_robot_index(
    csv_root=CSV_ROOT_DEFAULT,
    max_robots: int = 50,
    battles_per_robot: int = 3,
    seed: int = 42,
    verbose: bool = True,
) -> set[tuple[str, str]]:
    """Pick a stratified subset of (battle_id, robot_name) pairs.

    `csv_root` may be a single Path or an iterable of Paths. Each root is
    walked with rglob('ticks.csv'); the parent dir is taken as the robot name
    and the grand-parent as the battle id, so any nested layout works
    (e.g. .../csv-chunk-N/<battle>/<robot>/ticks.csv).

    Strategy:
      1. Walk all roots once and group ticks.csv files by robot_name.
      2. Keep the top `max_robots` by appearance count.
      3. Randomly pick `battles_per_robot` battles from each kept robot.

    Side effect: populates module-level _PATH_INDEX so load_stratified can
    locate the actual files.
    """
    rng = random.Random(seed)
    roots = _iter_roots(csv_root)
    robot_to_files: dict[str, list[tuple[str, Path]]] = defaultdict(list)
    for root in roots:
        if not root.exists():
            if verbose:
                print(f"⚠ CSV root does not exist: {root}")
            continue
        for ticks_path in root.rglob('ticks.csv'):
            robot_name = ticks_path.parent.name
            battle_id  = ticks_path.parent.parent.name
            robot_to_files[robot_name].append((battle_id, ticks_path))

    total_files = sum(len(v) for v in robot_to_files.values())
    if verbose:
        print(f"Indexed {total_files} ticks.csv files across "
              f"{len(robot_to_files)} distinct robots from {len(roots)} root(s).")

    sorted_robots = sorted(robot_to_files.items(), key=lambda kv: -len(kv[1]))
    selected_robots = [name for name, _ in sorted_robots[:max_robots]]

    selection: set[tuple[str, str]] = set()
    _PATH_INDEX.clear()
    for robot in selected_robots:
        files = robot_to_files[robot]
        picked = rng.sample(files, min(battles_per_robot, len(files)))
        for battle_id, ticks_path in picked:
            selection.add((battle_id, robot))
            _PATH_INDEX[(battle_id, robot)] = ticks_path.parent

    if verbose:
        print(f"Selected {len(selected_robots)} robots x ~{battles_per_robot} battles "
              f"= {len(selection)} (battle, robot) pairs to load.")
    return selection


def load_stratified(
    filename: str,
    selection: Iterable[tuple[str, str]],
    csv_root=CSV_ROOT_DEFAULT,
    row_frac: float = 1.0,
    seed: int = 42,
    verbose: bool = True,
) -> pd.DataFrame:
    """Load `filename` from each (battle_id, robot) pair in `selection`.

    If `build_robot_index()` populated _PATH_INDEX (recommended), the cached
    directory is used directly. Otherwise we fall back to `csv_root / battle /
    robot / filename` for each root in `csv_root` (single Path or list).

    Args:
      filename:  e.g. 'ticks.csv', 'waves.csv', 'scores.csv'.
      selection: result of `build_robot_index()`.
      row_frac:  fraction of rows to keep per file (1.0 = keep all).
                 Use 0.1–0.2 for ticks.csv; keep 1.0 for waves/scores.
    """
    roots = _iter_roots(csv_root)
    frames = []
    for battle_id, robot in sorted(selection):
        csv_path = None
        cached_dir = _PATH_INDEX.get((battle_id, robot))
        if cached_dir is not None:
            candidate = cached_dir / filename
            if candidate.exists():
                csv_path = candidate
        if csv_path is None:
            for root in roots:
                candidate = root / battle_id / robot / filename
                if candidate.exists():
                    csv_path = candidate
                    break
        if csv_path is None:
            continue
        try:
            df = pd.read_csv(csv_path, low_memory=False)
        except Exception as exc:
            if verbose:
                print(f"⚠ Skipping {csv_path}: {exc}")
            continue
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
        print(f"Loaded {len(frames)} {filename} files -> "
              f"{combined.shape[0]:,} rows x {combined.shape[1]} cols, "
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


# Wave-tracking columns in ticks.csv that reset/update at the moment
# `opponent_fired = 1`. Including any of these as features when predicting
# `opponent_fired` is leakage — see archive/2026-05-02-intuition-4.md.
# Pass via `extra_exclude=WAVE_DERIVED_COLS` for an honest fire-event task.
#
# `opponent_inferred_gun_heat` is included here too: in Robocode the opponent
# can fire only when gun_heat == 0, and our inference resets it to 1 + power/5
# the same tick we detect the energy drop. So values close to 0 perfectly
# predict "is_about_to_fire". For predicting `opponent_fired` this is the
# strongest single leak (RF importance ≈ 0.91 in nb04 phase 1).
WAVE_DERIVED_COLS = (
    'opponent_wave_distance',
    'opponent_wave_remaining',
    'opponent_wave_eta',
    'ticks_since_opponent_fired',
    'escape_angle_coverage',
    'mea_for_opponent_bullet',
    'opponent_bullet_speed',
    'our_wave_distance',
    'our_wave_remaining',
    'opponent_inferred_gun_heat',
    # Multi-wave features updated at fire detection (include current fire):
    'n_opponent_waves_in_flight',
    'total_opponent_wave_damage',
    'nearest_opponent_wave_gap',
)


# Algebraic identities of `opponent_fire_power` once a fire has been detected:
#   opponent_bullet_speed     = 20 - 3 * opponent_fire_power
#   mea_for_opponent_bullet   = asin(8 / opponent_bullet_speed)
# Plus second-order leaks via state that updates at the fire-detection tick:
#   opponent_inferred_gun_heat — at the fire-detection tick the gun-heat
#   counter has just been reset to 1 + power/5 (Robocode sets gun heat AFTER
#   the bullet leaves and our pipeline observes the fire one tick later, when
#   the energy drop is visible). 1:1 algebraic restatement of the target.
#   escape_angle_coverage / opponent_wave_* — all recomputed using the new
#   bullet's MEA at the fire-detection tick, so they encode `bullet_speed`
#   and hence `power`.
# See archive/2026-05-02-intuition-5.md §5 (and §5b in the rerun).
# Including any of these when predicting fire power gives R² = 1.000 trivially.
FIRE_POWER_LEAKAGE_COLS = (
    'opponent_fire_power',
    'opponent_bullet_speed',
    'mea_for_opponent_bullet',
    'opponent_inferred_gun_heat',
    # Wave-tracking columns refreshed at the same tick as the fire event:
    'opponent_wave_distance',
    'opponent_wave_remaining',
    'opponent_wave_eta',
    'ticks_since_opponent_fired',
    'escape_angle_coverage',
    'gf_current_at_power_1',
    'gf_current_at_power_1_5',
    'gf_current_at_power_2',
    # Multi-wave features updated on the same tick (include current fire's wave):
    'n_opponent_waves_in_flight',
    'total_opponent_wave_damage',
    'nearest_opponent_wave_gap',
)


# Pairs of features with |r| ≈ 1.000 at full-rumble scale (notebook 02). Drop
# the partner of whichever you keep, otherwise models double-count the signal.
# - opponent_guess_factor ≡ our_lateral_velocity (by construction)
# - distance ≡ distance_norm (linear scaling)
# - gf_current_at_power_{1, 1_5, 2} pairwise r > 0.999
# - reachable_distance_min/max ≡ distance (r=1.00/0.99) — envelope distance
#   range at t+10 is almost entirely determined by current distance
REDUNDANT_FEATURE_PAIRS = (
    ('opponent_guess_factor', 'our_lateral_velocity'),
    ('distance_norm', 'distance'),
    ('gf_current_at_power_1', 'gf_current_at_power_2'),
    ('gf_current_at_power_1_5', 'gf_current_at_power_2'),
    ('reachable_distance_min', 'distance'),
    ('reachable_distance_max', 'distance'),
)


# Scan-quality features that predict label reliability, not opponent behavior.
# scan_coverage measures how well OUR radar tracks the opponent. High coverage
# means fire-detection labels are clean; low coverage means noisy labels.
# Including these in fire-power or fire-timing models is META-LEAKAGE: the model
# learns to predict label quality rather than opponent strategy.
# See archive/2026-05-03-gbm-intuition-7.md §1 Finding 2.
SCAN_META_COLS = (
    'scan_coverage_20',
    'scan_coverage_50',
    'scan_arc_width',
    'radar_locked',
    'radar_turn_direction',
    'ticks_between_scans',
)


# Cumulative combat progress columns. These are valid in-game observables
# (the robot tracks them on Whiteboard), but when predicting ROUND OUTCOME
# they encode the result: high cumulative_damage_dealt = winning. Safe for
# fire-power, movement, and fire-timing models. Exclude for round-outcome.
CUMULATIVE_OUTCOME_COLS = (
    'cumulative_damage_dealt',
    'cumulative_damage_received',
    'cumulative_our_hit_rate',
    'cumulative_opponent_hit_rate',
    'cumulative_shots_fired',
    'cumulative_shots_detected',
)


def drop_redundant_features(cols):
    """Remove the first member of each REDUNDANT_FEATURE_PAIRS pair from `cols`.

    Keeps the second (the survivor) so models don't double-count.
    """
    drop = {a for a, _ in REDUNDANT_FEATURE_PAIRS}
    return [c for c in cols if c not in drop]


# Columns in waves.csv that are inputs (state-of-world at fire-detection
# instant). No outcome columns exist today — if any future column records hit/
# miss, it MUST live in waves.csv only and be added to a parallel
# WAVE_OUTCOME_COLS tuple, not here.
WAVE_INPUT_COLS = (
    'wave_bullet_power',
    'wave_bullet_speed',
    'wave_fire_distance',
    'wave_mea',
    'wave_flight_time',
    'wave_lateral_velocity_at_fire',
)


def attach_opponent_bot(df, selection, csv_root=CSV_ROOT_DEFAULT):
    """Add an `opponent_bot` column by reading sibling perspective dir names.

    Each battle dir has exactly two perspective subdirs; given an observer
    (`robot_name`), the opponent is the other one. Used by notebooks 05/06/07
    that need to label ticks by who they're observing.
    """
    roots = _iter_roots(csv_root)
    pair_to_opp: dict[tuple[str, str], str] = {}
    for battle_id, observer in selection:
        cached_dir = _PATH_INDEX.get((battle_id, observer))
        if cached_dir is not None:
            battle_dir = cached_dir.parent
        else:
            battle_dir = None
            for root in roots:
                candidate = root / battle_id
                if candidate.is_dir():
                    battle_dir = candidate
                    break
        if battle_dir is None or not battle_dir.is_dir():
            continue
        names = [d.name for d in battle_dir.iterdir() if d.is_dir()]
        others = [n for n in names if n != observer]
        if others:
            pair_to_opp[(battle_id, observer)] = others[0]

    keys = list(zip(df['battle_id'].astype(str), df['robot_name'].astype(str)))
    df = df.copy()
    df['opponent_bot'] = [pair_to_opp.get(k) for k in keys]
    return df


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


def compute_strategic_axes(df: pd.DataFrame, num_rounds: int = 35) -> pd.DataFrame:
    """Add the 4 strategic-axis columns to a ticks/waves DataFrame.

    These are slow-moving context features that condition all predictors.
    Computed from per-tick observables only (no outcome leakage):
      - aggression: energy_ratio (already per-tick, ∈ [0,1])
      - preferred_range: distance (already per-tick, in px)
      - opponent_family: 0 placeholder (populated after fingerprint runs)
      - game_phase: round / num_rounds (∈ [0,1])

    For offline training, these approximate what the StrategyComputer would
    produce in-game. The in-game version will use the round-outcome predictor
    for aggression; for training we use the raw energy_ratio to avoid outcome
    leakage.
    """
    df = df.copy()
    df['axis_aggression'] = df['energy_ratio'] if 'energy_ratio' in df.columns else 0.5
    df['axis_preferred_range'] = df['distance'] if 'distance' in df.columns else 350.0
    df['axis_opponent_family'] = 0  # placeholder until fingerprint is integrated
    df['axis_game_phase'] = (df['round'] / max(1, num_rounds)) if 'round' in df.columns else 0.0
    return df

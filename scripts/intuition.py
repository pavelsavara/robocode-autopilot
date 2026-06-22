#!/usr/bin/env python3
"""ML Intuition report generator.

Implements ``intuition-implementation-plan.md`` against the contract in
``intuition-design.md``: discover a ``battle-csv-producer`` run directory, resolve the
producer file contract per perspective, expose the canonical statistical primitives and a
loud self-consistency assertion framework, and write ``wiki/ML-intuition.md`` plus its
asset directory (deterministic PNG figures keyed by question ID and a machine-readable
``intuition.json`` sidecar for CI regression-diffing).

Phase 1 = skeleton/CLI/data layer; Phase 2 = the A–I markdown tables; Phase 3 = figures,
JSON sidecar, determinism guards and report-assembly polish.

Usage:
  intuition.py [RUN_DIR] [--out wiki/ML-intuition.md] [--assets wiki/ml-intuition]
               [--max-model-rows 50000]
"""
from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import json
import os
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Optional, Sequence

import matplotlib

matplotlib.use("Agg")  # non-interactive, deterministic raster backend
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

# --------------------------------------------------------------------------------------
# Canonical constants (printed in the report header; identical across every section)
# --------------------------------------------------------------------------------------

#: Global random seed for any sampling / bootstrap / model fit (determinism).
SEED = 1_234_567

#: Fixed GuessFactor binning grid: 47 uniform bins over [-1, 1].
GF_BIN_COUNT = 47
GF_BIN_EDGES = np.linspace(-1.0, 1.0, GF_BIN_COUNT + 1)

#: Per-opponent cells below this resolved-wave count are flagged (`†`) and excluded
#: from cross-opponent rankings.
SMALL_N_THRESHOLD = 200

#: The six per-perspective files the script depends on (the producer contract).
REQUIRED_FILES = (
    "ticks.csv",
    "scan.csv",
    "their-waves.csv",
    "autopilot-waves.csv",
    "dejavu-waves.csv",
    "scores.csv",
)

#: Legacy single-file fallback (real + virtual together, autopilot gun only).
LEGACY_WAVE_FILE = "our-waves.csv"

#: Which report sections become "unavailable" when a given file is missing.
FILE_SECTIONS = {
    "dejavu-waves.csv": ["B (most)", "C1-C3", "C5-C9", "F", "G1", "G3", "H"],
    "autopilot-waves.csv": ["C4", "G1 (floor)", "G3 (cross-check)"],
    "their-waves.csv": ["D"],
    "ticks.csv": ["B5", "B7", "F1"],
    "scan.csv": ["A6", "A7", "B1-B6"],
    "scores.csv": ["A1", "G2"],
}

#: Columns the Phase-2/3 loaders read from the big descriptive files (selective load +
#: numeric downcast, per the design's performance guards).
TICKS_USECOLS = (
    "battle_id", "round", "tick", "our_x", "our_y", "our_velocity", "our_energy",
)
SCAN_USECOLS = (
    "battle_id", "round", "tick", "scan_tick", "scan_distance",
    "scan_opponent_id_hash", "scan_opponent_lateral_velocity",
    "scan_opponent_advancing_velocity", "scan_ticks_since_scan",
    "scan_their_inactivity_zap_active",
)
#: Columns C4/G3 need from autopilot-waves (load real rows only / these columns only).
AUTOPILOT_WAVE_USECOLS = (
    "battle_id", "round", "our_fire_tick", "our_fire_distance",
    "our_fire_lateral_velocity", "our_fire_mea", "our_fire_aim_gf",
    "our_fire_is_real", "our_break_gf", "our_break_hit",
)

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_PRODUCER_ROOT = REPO_ROOT / "pipeline" / "build" / "battle-csv-producer"
MATCHUP_SEP = "__vs__"

#: Robots whose battles are dropped entirely (as hero *and* as opponent) before any
#: analysis. ``cz.zamboch.Autopilot`` is the in-development bot and far weaker than the
#: top-bot field; including it would pollute cross-opponent aggregates and floor/hero
#: comparisons with conclusions we explicitly do not want to draw.
EXCLUDED_ROBOTS = frozenset({"cz.zamboch.Autopilot"})


# --------------------------------------------------------------------------------------
# Loud self-consistency assertion framework (used heavily in Phase 2)
# --------------------------------------------------------------------------------------

class IntuitionError(RuntimeError):
    """Raised on a structural failure or a self-consistency violation."""


def fail(message: str) -> "None":
    """Abort the run loudly with a clear message (exit code 2)."""
    print(f"intuition.py: ERROR: {message}", file=sys.stderr)
    raise SystemExit(2)


def require(condition: bool, message: str) -> None:
    """Assert ``condition`` or abort loudly. The Phase-2 tables call this on every
    self-consistency invariant (real+virtual==total, per-opponent N sums to global N,
    rates in [0, 1], GF in [-1, 1])."""
    if not condition:
        raise IntuitionError(message)


# --------------------------------------------------------------------------------------
# Canonical statistical helpers (fixed once; every section uses these identically)
# --------------------------------------------------------------------------------------

def canonical_hit(aim_gf, break_gf, fire_distance, mea, fire_bearing=None):
    """Canonical analytic hit rule:
    ``|aim_gf - break_gf| <= (18*box_factor/fire_distance)/mea``.

    Vectorized over numpy arrays / pandas Series. ``mea`` is each wave's own
    max-escape-angle and ``fire_distance`` its own fire-time distance, so the tolerance
    is computed per wave (robot half-width 18 px). ``box_factor = |cos t| + |sin t|`` of
    the absolute fire bearing ``t`` accounts for the axis-aligned 36x36 box that does
    NOT rotate: the hittable width is 1x at the cardinals up to sqrt(2)x at the
    diagonals. When ``fire_bearing`` is omitted the factor is 1 (cardinal/flat-18)."""
    aim_gf = np.asarray(aim_gf, dtype=np.float64)
    break_gf = np.asarray(break_gf, dtype=np.float64)
    fire_distance = np.asarray(fire_distance, dtype=np.float64)
    mea = np.asarray(mea, dtype=np.float64)
    if fire_bearing is None:
        box_factor = 1.0
    else:
        t = np.asarray(fire_bearing, dtype=np.float64)
        box_factor = np.abs(np.cos(t)) + np.abs(np.sin(t))
    with np.errstate(divide="ignore", invalid="ignore"):
        gf_tol = (18.0 * box_factor / fire_distance) / mea
    return np.abs(aim_gf - break_gf) <= gf_tol


def gf_bins():
    """Return the fixed 47-bin GuessFactor edge grid over [-1, 1]."""
    return GF_BIN_EDGES


def gf_hist_laplace(values):
    """Histogram ``values`` into the canonical 47-bin grid with Laplace (add-one)
    smoothing, returning a probability vector that never contains a zero bin (so entropy
    and KL stay finite)."""
    values = np.asarray(values, dtype=np.float64)
    values = values[np.isfinite(values)]
    counts, _ = np.histogram(values, bins=GF_BIN_EDGES)
    smoothed = counts.astype(np.float64) + 1.0
    return smoothed / smoothed.sum()


def wilson_ci(successes: int, n: int, z: float = 1.96):
    """95% Wilson score interval for a proportion. Returns ``(lo, hi)``; ``(nan, nan)``
    when ``n == 0``."""
    if n <= 0:
        return (float("nan"), float("nan"))
    phat = successes / n
    denom = 1.0 + z * z / n
    center = (phat + z * z / (2 * n)) / denom
    half = (z / denom) * np.sqrt(phat * (1 - phat) / n + z * z / (4 * n * n))
    return (max(0.0, center - half), min(1.0, center + half))


def bootstrap_ci(
    values: Sequence[float],
    stat_fn: Callable[[np.ndarray], float],
    n_resamples: int = 1000,
    seed: int = SEED,
    ci: float = 95.0,
):
    """Seeded bootstrap CI of ``stat_fn`` over ``values`` (1000 resamples by default).
    Returns ``(lo, hi)``; ``(nan, nan)`` when there are no values."""
    values = np.asarray(values, dtype=np.float64)
    values = values[np.isfinite(values)]
    if values.size == 0:
        return (float("nan"), float("nan"))
    rng = np.random.default_rng(seed)
    n = values.size
    stats = np.empty(n_resamples, dtype=np.float64)
    for i in range(n_resamples):
        stats[i] = stat_fn(values[rng.integers(0, n, n)])
    lo = np.percentile(stats, (100.0 - ci) / 2.0)
    hi = np.percentile(stats, 100.0 - (100.0 - ci) / 2.0)
    return (float(lo), float(hi))


def is_self_play(matchup_name: str) -> bool:
    """True when both sides of a matchup are the same robot (``X__vs__X``)."""
    parts = matchup_name.split(MATCHUP_SEP)
    return len(parts) == 2 and parts[0] == parts[1]


def per_opponent_groups(perspectives: Sequence["Perspective"]):
    """Group perspectives by the *opponent* robot, excluding self-play matchups (per the
    canonical self-play rule). Returns ``{opponent_name: [Perspective, ...]}``."""
    groups: dict[str, list[Perspective]] = {}
    for p in perspectives:
        if p.is_self_play:
            continue
        groups.setdefault(p.opponent, []).append(p)
    return groups


# --------------------------------------------------------------------------------------
# Data layer: run-dir discovery, perspective walk, contract resolution, loaders
# --------------------------------------------------------------------------------------

@dataclass
class WaveFileMeta:
    """Per-file metadata recorded in the report header."""
    name: str
    present: bool
    path: Optional[Path] = None
    sha256: Optional[str] = None
    data_rows: Optional[int] = None
    size_bytes: Optional[int] = None


@dataclass
class Perspective:
    """One robot-side observer view of one matchup."""
    matchup: str
    perspective_dir: str
    robot: str
    opponent: str
    is_self_play: bool
    path: Path
    files: dict[str, WaveFileMeta] = field(default_factory=dict)
    legacy_present: bool = False

    @property
    def label(self) -> str:
        return f"{self.matchup}/{self.perspective_dir}"

    def missing_required(self) -> list[str]:
        return [n for n in REQUIRED_FILES if not self.files.get(n, WaveFileMeta(n, False)).present]


def find_run_dir(explicit: Optional[str]) -> Path:
    """Resolve the run directory: an explicit path, else the newest directory under
    ``pipeline/build/battle-csv-producer/``. Aborts loudly if none is found."""
    if explicit:
        run = Path(explicit).expanduser().resolve()
        if not run.is_dir():
            fail(f"run directory does not exist: {run}")
        return run
    if not DEFAULT_PRODUCER_ROOT.is_dir():
        fail(
            "no producer output found at "
            f"{_rel(DEFAULT_PRODUCER_ROOT)} — run "
            "`./gradlew :pipeline:battleCsvProducer` first, or pass RUN_DIR explicitly."
        )
    candidates = [d for d in DEFAULT_PRODUCER_ROOT.iterdir() if d.is_dir()]
    if not candidates:
        fail(f"no run directories under {_rel(DEFAULT_PRODUCER_ROOT)}")
    newest = max(candidates, key=lambda d: d.stat().st_mtime)
    return newest.resolve()


def _rel(path: Path) -> str:
    """Workspace-relative POSIX path for display (falls back to absolute)."""
    try:
        return path.resolve().relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return path.as_posix()


def _sha256_and_rows(path: Path) -> tuple[str, int, int]:
    """Stream a file once, returning ``(sha256_hex, data_row_count, size_bytes)``.
    Data rows = newline count minus the header line."""
    h = hashlib.sha256()
    newlines = 0
    size = 0
    with path.open("rb") as fh:
        while True:
            chunk = fh.read(1 << 20)
            if not chunk:
                break
            h.update(chunk)
            newlines += chunk.count(b"\n")
            size += len(chunk)
    data_rows = max(0, newlines - 1)
    return h.hexdigest(), data_rows, size


def _parse_perspective_robot(perspective_dir: str) -> Optional[str]:
    """Extract ``<robot>`` from a ``PerspectiveN-<robot>`` directory name."""
    if not perspective_dir.startswith("Perspective"):
        return None
    dash = perspective_dir.find("-")
    if dash < 0:
        return None
    return perspective_dir[dash + 1:]


def walk_perspectives(run_dir: Path) -> list[Perspective]:
    """Enumerate ``<matchup>/PerspectiveN-<robot>/`` and resolve the file contract for
    each. Aborts loudly on a structurally broken run dir (no matchups / no perspectives)."""
    perspectives: list[Perspective] = []
    matchups = sorted(d for d in run_dir.iterdir() if d.is_dir())
    if not matchups:
        fail(f"run directory has no matchup sub-directories: {_rel(run_dir)}")

    for matchup_dir in matchups:
        matchup = matchup_dir.name
        parts = matchup.split(MATCHUP_SEP)
        if len(parts) != 2:
            fail(f"matchup directory is not '<A>{MATCHUP_SEP}<B>': {matchup}")
        robot_a, robot_b = parts
        self_play = robot_a == robot_b

        persp_dirs = sorted(d for d in matchup_dir.iterdir() if d.is_dir())
        if not persp_dirs:
            fail(f"matchup has no perspective sub-directories: {matchup}")

        for pdir in persp_dirs:
            robot = _parse_perspective_robot(pdir.name)
            if robot is None:
                fail(f"unexpected perspective directory name: {matchup}/{pdir.name}")
            opponent = robot_b if robot == robot_a else robot_a

            files: dict[str, WaveFileMeta] = {}
            for fname in REQUIRED_FILES:
                fpath = pdir / fname
                if fpath.is_file():
                    sha, rows, size = _sha256_and_rows(fpath)
                    files[fname] = WaveFileMeta(fname, True, fpath, sha, rows, size)
                else:
                    files[fname] = WaveFileMeta(fname, False)

            perspectives.append(
                Perspective(
                    matchup=matchup,
                    perspective_dir=pdir.name,
                    robot=robot,
                    opponent=opponent,
                    is_self_play=self_play,
                    path=pdir,
                    files=files,
                    legacy_present=(pdir / LEGACY_WAVE_FILE).is_file(),
                )
            )

    if not perspectives:
        fail(f"run directory contained no perspectives: {_rel(run_dir)}")
    return perspectives


def filter_excluded_robots(perspectives: Sequence[Perspective]) -> list[Perspective]:
    """Drop perspectives whose hero or opponent is in ``EXCLUDED_ROBOTS`` (i.e. every
    battle the excluded bot took part in, in either role). Returns the survivors and
    aborts loudly if nothing is left."""
    kept = [
        p for p in perspectives
        if p.robot not in EXCLUDED_ROBOTS and p.opponent not in EXCLUDED_ROBOTS
    ]
    dropped = len(perspectives) - len(kept)
    if dropped:
        print(
            f"intuition.py: excluded {dropped} perspective(s) involving "
            f"{', '.join(sorted(EXCLUDED_ROBOTS))} (hero or opponent)"
        )
    if not kept:
        fail(
            "all perspectives were excluded by EXCLUDED_ROBOTS "
            f"({', '.join(sorted(EXCLUDED_ROBOTS))})"
        )
    return kept


def resolve_contract(perspectives: Sequence[Perspective]) -> str:
    """Describe the producer contract that was resolved across the run."""
    have_dejavu = any(p.files["dejavu-waves.csv"].present for p in perspectives)
    have_autopilot = any(p.files["autopilot-waves.csv"].present for p in perspectives)
    legacy_only = (not have_dejavu) and any(p.legacy_present for p in perspectives)
    if have_dejavu and have_autopilot:
        return "full split (dejavu-waves + autopilot-waves)"
    if legacy_only:
        return f"legacy fallback ({LEGACY_WAVE_FILE} = autopilot gun only; dejavu sections unavailable)"
    if have_autopilot and not have_dejavu:
        return "autopilot-waves only (dejavu sections unavailable)"
    return "incomplete (no recognized wave files found)"


# ---- Phase-2/3 loaders (selective columns + downcast per the performance guards) ----

def load_waves(path: Path, usecols: Optional[Sequence[str]] = None) -> pd.DataFrame:
    """Load a wave CSV (dejavu/autopilot share the OUR_WAVES schema). Wave files are
    small and carry the labels, so they are loaded fully unless ``usecols`` is given."""
    return pd.read_csv(path, usecols=list(usecols) if usecols else None)


def load_scores(path: Path) -> pd.DataFrame:
    """Load ``scores.csv`` (per-round result + round_hit_rate)."""
    return pd.read_csv(path)


def load_ticks(path: Path) -> pd.DataFrame:
    """Load ``ticks.csv`` with selective columns + numeric downcast (1M+ rows)."""
    df = pd.read_csv(path, usecols=list(TICKS_USECOLS))
    return _downcast(df)


def load_scan(path: Path) -> pd.DataFrame:
    """Load ``scan.csv`` with selective columns + numeric downcast (1M+ rows)."""
    df = pd.read_csv(path, usecols=list(SCAN_USECOLS))
    return _downcast(df)


def _downcast(df: pd.DataFrame) -> pd.DataFrame:
    for col in df.columns:
        if pd.api.types.is_float_dtype(df[col]):
            df[col] = df[col].astype(np.float32)
        elif pd.api.types.is_integer_dtype(df[col]):
            df[col] = pd.to_numeric(df[col], downcast="integer")
    return df


# --------------------------------------------------------------------------------------
# Phase 2 — additional statistical helpers (built on the canonical primitives above)
# --------------------------------------------------------------------------------------

GF_BIN_CENTERS = (GF_BIN_EDGES[:-1] + GF_BIN_EDGES[1:]) / 2.0

#: Fire-time targeting features (from dejavu OUR_WAVES) used by C6/C7/E2/E3/E4.
TARGET_FEATURES = [
    "our_fire_distance",
    "our_fire_lateral_velocity",
    "our_fire_advancing_velocity",
    "our_fire_bullet_speed",
    "our_fire_mea",
    "our_fire_power",
    "our_fire_direction",
]

#: Units / description hints for the E1 feature catalog (default "—").
FEATURE_UNITS = {
    "our_fire_distance": "px", "our_fire_lateral_velocity": "px/tick",
    "our_fire_advancing_velocity": "px/tick", "our_fire_bullet_speed": "px/tick",
    "our_fire_mea": "rad", "our_fire_direction": "sign(±1)",
    "our_fire_bearing_absolute": "rad", "our_fire_power": "energy",
    "our_fire_x": "px", "our_fire_y": "px", "our_fire_opponent_x": "px",
    "our_fire_opponent_y": "px", "our_fire_tick": "tick", "our_fire_bullet_id": "id",
    "our_fire_aim_gf": "GF[-1,1]", "our_fire_is_real": "bool",
    "our_aim_distance": "px", "our_aim_bearing_absolute": "rad",
    "our_break_tick": "tick", "our_break_gf": "GF[-1,1]",
    "our_break_bearing_offset": "rad", "our_break_hit": "bool",
    "their_fire_power": "energy", "their_bullet_speed": "px/tick",
    "their_fire_distance": "px", "their_break_gf": "GF[-1,1]", "their_hit_us": "bool",
    "scan_distance": "px", "scan_opponent_lateral_velocity": "px/tick",
    "scan_opponent_advancing_velocity": "px/tick", "scan_ticks_since_scan": "tick",
    "scan_their_inactivity_zap_active": "bool", "scan_opponent_id_hash": "hash",
}

#: Battlefield geometry (verified from the producer: ObserverRobotPeer(800, 600)).
FIELD_W, FIELD_H, ROBOT_HALF = 800.0, 600.0, 18.0


def shannon_entropy_bits(p) -> float:
    """Shannon entropy (bits) of a probability vector."""
    p = np.asarray(p, dtype=np.float64)
    p = p[p > 0]
    return float(-np.sum(p * np.log2(p)))


def effective_bins(p) -> float:
    """Perplexity (effective number of occupied bins) = 2**entropy."""
    return float(2.0 ** shannon_entropy_bits(p))


def kl_bits(p, q) -> float:
    """KL(p‖q) in bits over two (already Laplace-smoothed) probability vectors."""
    p = np.asarray(p, dtype=np.float64)
    q = np.asarray(q, dtype=np.float64)
    return float(np.sum(p * np.log2(p / q)))


def kl_adapt_ci(g0, gl, n_boot: int = 1000):
    """Bias-corrected round-to-round KL(round 0 ‖ round last) in bits, plus a basic
    (pivotal) bootstrap 95% CI.

    The plug-in KL of two Laplace-smoothed histograms is positively biased at finite N:
    it is > 0 even when both rounds are drawn from the *same* distribution, with the bias
    growing as (#bins) / N. Resampling both rounds therefore yields a bootstrap cloud that
    sits *above* the plug-in point, so a naive percentile CI excludes its own estimate from
    below. We apply the textbook bootstrap bias correction (``2·plug − mean(boot)``) and the
    matching basic/pivotal interval, both clamped at 0 so a non-adapting opponent reads ≈ 0
    instead of a spurious positive shift. Returns ``(kl_corrected, ci_lo, ci_hi)`` in bits.
    """
    g0 = np.asarray(g0, dtype=np.float64)
    gl = np.asarray(gl, dtype=np.float64)
    plug = kl_bits(gf_hist_laplace(g0), gf_hist_laplace(gl))
    rng = np.random.default_rng(SEED)
    boot = np.empty(n_boot, dtype=np.float64)
    for i in range(n_boot):
        b0 = g0[rng.integers(0, len(g0), len(g0))]
        bl = gl[rng.integers(0, len(gl), len(gl))]
        boot[i] = kl_bits(gf_hist_laplace(b0), gf_hist_laplace(bl))
    boot_mean = float(boot.mean())
    corrected = max(0.0, 2.0 * plug - boot_mean)
    ci_lo = max(0.0, 2.0 * plug - float(np.percentile(boot, 97.5)))
    ci_hi = max(0.0, 2.0 * plug - float(np.percentile(boot, 2.5)))
    return corrected, ci_lo, ci_hi


def correlation_ratio(categories: np.ndarray, values: np.ndarray) -> float:
    """Correlation ratio η² = between-group variance / total variance (variance of a
    continuous target explained by a categorical/segment grouping)."""
    values = np.asarray(values, dtype=np.float64)
    mask = np.isfinite(values)
    values, categories = values[mask], np.asarray(categories)[mask]
    if values.size < 2:
        return float("nan")
    grand = values.mean()
    ss_total = np.sum((values - grand) ** 2)
    if ss_total == 0:
        return float("nan")
    ss_between = 0.0
    for c in pd.unique(categories):
        grp = values[categories == c]
        if grp.size:
            ss_between += grp.size * (grp.mean() - grand) ** 2
    return float(ss_between / ss_total)


def quantize_gf(gf) -> np.ndarray:
    """Snap GF values to the center of the canonical bin that contains them."""
    gf = np.asarray(gf, dtype=np.float64)
    idx = np.clip(np.digitize(gf, GF_BIN_EDGES) - 1, 0, GF_BIN_COUNT - 1)
    return GF_BIN_CENTERS[idx]


def peak_gf(values) -> float:
    """Center of the most-populated canonical GF bin."""
    values = np.asarray(values, dtype=np.float64)
    values = values[np.isfinite(values)]
    if values.size == 0:
        return float("nan")
    counts, _ = np.histogram(values, bins=GF_BIN_EDGES)
    return float(GF_BIN_CENTERS[int(np.argmax(counts))])


def hit_rate_analytic(df: pd.DataFrame, aim) -> tuple[int, int]:
    """Return (hits, n) for the canonical analytic hit rule over a wave frame, where
    ``aim`` is a scalar/array aim-GF. Only resolved waves (finite break_gf) count."""
    brk = df["our_break_gf"].to_numpy(dtype=np.float64)
    dist = df["our_fire_distance"].to_numpy(dtype=np.float64)
    mea = df["our_fire_mea"].to_numpy(dtype=np.float64)
    bearing = (df["our_fire_bearing_absolute"].to_numpy(dtype=np.float64)
               if "our_fire_bearing_absolute" in df.columns else None)
    mask = np.isfinite(brk) & np.isfinite(dist) & np.isfinite(mea) & (mea > 0)
    if isinstance(aim, np.ndarray):
        aim = aim[mask]
    fb = bearing[mask] if bearing is not None else None
    hits = canonical_hit(aim, brk[mask], dist[mask], mea[mask], fb)
    return int(np.sum(hits)), int(mask.sum())


def best_static_gf(df: pd.DataFrame) -> tuple[float, float, int]:
    """Best single fixed aim-GF per the canonical hit rule. Returns (best_gf, rate, n)."""
    n = int(np.isfinite(df["our_break_gf"]).sum())
    if n == 0:
        return (float("nan"), float("nan"), 0)
    best_g, best_rate = float("nan"), -1.0
    for g in GF_BIN_CENTERS:
        h, m = hit_rate_analytic(df, g)
        r = h / m if m else 0.0
        if r > best_rate:
            best_rate, best_g = r, float(g)
    return (best_g, best_rate, n)


# --------------------------------------------------------------------------------------
# Phase 2 — dataset loader (tagged, concatenated frames) + per-opponent views
# --------------------------------------------------------------------------------------

TAG_COLS = ("matchup", "perspective", "robot", "opponent", "self_play")


@dataclass
class Dataset:
    """All tagged, concatenated frames the A–I sections consume."""
    run_dir: Path
    perspectives: list[Perspective]
    dejavu: pd.DataFrame
    autopilot_real: pd.DataFrame
    their: pd.DataFrame
    scores: pd.DataFrame
    ticks: pd.DataFrame
    scan: pd.DataFrame
    autopilot_real_count: int
    autopilot_virtual_count: int

    def opponents(self) -> list[str]:
        """Sorted robot names that appear as an opponent in a non-self-play matchup."""
        return sorted({p.opponent for p in self.perspectives if not p.is_self_play})

    def dejavu_for_opponent(self, opp: str) -> pd.DataFrame:
        return self.dejavu[(self.dejavu["opponent"] == opp) & (~self.dejavu["self_play"])]

    def their_for_opponent(self, opp: str) -> pd.DataFrame:
        return self.their[(self.their["opponent"] == opp) & (~self.their["self_play"])]


def _tag(df: pd.DataFrame, p: Perspective) -> pd.DataFrame:
    df["matchup"] = p.matchup
    df["perspective"] = p.perspective_dir
    df["robot"] = p.robot
    df["opponent"] = p.opponent
    df["self_play"] = p.is_self_play
    return df


def _categorize_tags(df: pd.DataFrame) -> pd.DataFrame:
    for c in ("matchup", "perspective", "robot", "opponent"):
        if c in df.columns:
            df[c] = df[c].astype("category")
    return df


def load_dataset(run_dir: Path, perspectives: Sequence[Perspective]) -> Dataset:
    """Load and concatenate every perspective's files into tagged frames. Wave/score
    files load fully; ticks/scan load with selective columns + downcast. Missing files
    are simply skipped (their dependent sections render 'unavailable')."""
    dej_l, auto_l, their_l, score_l, ticks_l, scan_l = [], [], [], [], [], []
    auto_real_n = auto_virt_n = 0

    for p in perspectives:
        f = p.files
        if f["dejavu-waves.csv"].present:
            dej_l.append(_tag(load_waves(f["dejavu-waves.csv"].path), p))
        if f["autopilot-waves.csv"].present:
            a = load_waves(f["autopilot-waves.csv"].path, AUTOPILOT_WAVE_USECOLS)
            real = a["our_fire_is_real"] == 1
            auto_real_n += int(real.sum())
            auto_virt_n += int((~real).sum())
            auto_l.append(_tag(a[real].copy(), p))
        if f["their-waves.csv"].present:
            their_l.append(_tag(load_waves(f["their-waves.csv"].path), p))
        if f["scores.csv"].present:
            score_l.append(_tag(load_scores(f["scores.csv"].path), p))
        if f["ticks.csv"].present:
            ticks_l.append(_tag(load_ticks(f["ticks.csv"].path), p))
        if f["scan.csv"].present:
            scan_l.append(_tag(load_scan(f["scan.csv"].path), p))

    def cat(frames):
        if not frames:
            return pd.DataFrame()
        return _categorize_tags(pd.concat(frames, ignore_index=True))

    return Dataset(
        run_dir=run_dir,
        perspectives=list(perspectives),
        dejavu=cat(dej_l),
        autopilot_real=cat(auto_l),
        their=cat(their_l),
        scores=cat(score_l),
        ticks=cat(ticks_l),
        scan=cat(scan_l),
        autopilot_real_count=auto_real_n,
        autopilot_virtual_count=auto_virt_n,
    )


# --------------------------------------------------------------------------------------
# Phase 2 — markdown rendering helpers
# --------------------------------------------------------------------------------------

def _f(x, d: int = 2) -> str:
    if x is None:
        return "—"
    try:
        xf = float(x)
    except (TypeError, ValueError):
        return str(x)
    if not np.isfinite(xf):
        return "—"
    return f"{xf:.{d}f}"


def _pct(x, d: int = 1) -> str:
    if x is None or not np.isfinite(float(x)):
        return "—"
    return f"{100.0 * float(x):.{d}f}%"


def _ci_pct(lo, hi, d: int = 1) -> str:
    if lo is None or hi is None or not (np.isfinite(lo) and np.isfinite(hi)):
        return "—"
    return f"[{100.0 * lo:.{d}f}, {100.0 * hi:.{d}f}]"


def _ci_val(lo, hi, d: int = 2) -> str:
    if lo is None or hi is None or not (np.isfinite(lo) and np.isfinite(hi)):
        return "—"
    return f"[{lo:.{d}f}, {hi:.{d}f}]"


def md_table(headers: Sequence[str], rows: Sequence[Sequence[str]]) -> str:
    """Render a GitHub-flavored markdown table.

    Literal ``|`` in any header or cell is escaped to ``\\|`` so values such as
    ``Floor |GF err|`` or ``Δ|latV|`` don't get parsed as extra column separators.
    """
    def esc(c: object) -> str:
        return str(c).replace("|", "\\|")

    head = "| " + " | ".join(esc(h) for h in headers) + " |"
    sep = "|" + "|".join("---" for _ in headers) + "|"
    body = "\n".join("| " + " | ".join(esc(c) for c in r) + " |" for r in rows)
    return "\n".join([head, sep, body]) if rows else "\n".join([head, sep, "| _(no data)_ |"])


def _section(letter: str, title: str, body: str) -> str:
    titles = dict(SECTION_TITLES)
    return f"## Section {letter} — {titles[letter]}\n\n{body}\n"


def _flag(n: int) -> str:
    """Small-N marker."""
    return " †" if n < SMALL_N_THRESHOLD else ""


# --------------------------------------------------------------------------------------
# Report generation: header + A–I scaffold
# --------------------------------------------------------------------------------------

SECTION_TITLES = [
    ("A", "Dataset inventory & integrity"),
    ("B", "Game & physics orientation"),
    ("C", "Targeting (offensive gun): predicting GuessFactor"),
    ("D", "Movement (defensive surfing): avoiding being hit"),
    ("E", "Feature analysis for ML"),
    ("F", "Learning dynamics"),
    ("G", "Baselines & benchmarks"),
    ("H", "Per-opponent profiles"),
    ("I", "ML readiness & recommendations"),
]


def _fmt_bytes(n: int) -> str:
    mb = n / (1024 * 1024)
    if mb >= 1.0:
        return f"{mb:.1f} MB"
    return f"{n / 1024:.1f} KB"


def build_header(run_dir: Path, perspectives: Sequence[Perspective], assets_dir: Path) -> str:
    contract = resolve_contract(perspectives)
    matchups = sorted({p.matchup for p in perspectives})
    robots = sorted({p.robot for p in perspectives})
    self_play = sorted({p.matchup for p in perspectives if p.is_self_play})
    total_bytes = sum(
        m.size_bytes or 0 for p in perspectives for m in p.files.values() if m.present
    )
    generated = _dt.datetime.now(_dt.timezone.utc).strftime("%Y-%m-%d %H:%M:%SZ")

    lines: list[str] = []
    lines.append("# ML Intuition — Battle Dataset Report")
    lines.append("")
    lines.append(
        "> Auto-generated by [`scripts/intuition.py`](../scripts/intuition.py). "
        "Fully regenerated each run; do not edit by hand."
    )
    lines.append("")
    lines.append("## Run header")
    lines.append("")
    lines.append(f"- **Source run directory:** `{_rel(run_dir)}`")
    lines.append(f"- **Generated (UTC):** {generated}")
    lines.append(f"- **Resolved producer contract:** {contract}")
    lines.append(f"- **Assets directory:** `{_rel(assets_dir)}`")
    lines.append(
        f"- **Inventory:** {len(matchups)} matchups · {len(robots)} distinct robots · "
        f"{len(perspectives)} perspectives · {len(self_play)} self-play matchups · "
        f"{_fmt_bytes(total_bytes)} of CSV"
    )
    lines.append(f"- **Robots:** {', '.join(f'`{r}`' for r in robots)}")
    if self_play:
        lines.append(
            "- **Self-play matchups (shown but excluded from cross-opponent aggregates):** "
            + ", ".join(f"`{m}`" for m in self_play)
        )
    lines.append("")

    # Canonical definitions
    lines.append("## Canonical definitions")
    lines.append("")
    lines.append(
        "- **Hit (canonical, analytic):** `|aim_gf − break_gf| ≤ (18 / fire_distance) / mea`, "
        "computed per wave (robot half-width 18 px). Engine hits (`our_break_hit==1`) are "
        "reported only in a separate column."
    )
    lines.append(
        f"- **GF binning:** fixed {GF_BIN_COUNT}-bin uniform grid over [−1, 1] with Laplace "
        "(add-one) smoothing for entropy/KL."
    )
    lines.append(
        f"  - Bin edges: `[{GF_BIN_EDGES[0]:.4f}, {GF_BIN_EDGES[1]:.4f}, …, "
        f"{GF_BIN_EDGES[-2]:.4f}, {GF_BIN_EDGES[-1]:.4f}]` "
        f"(width {GF_BIN_EDGES[1] - GF_BIN_EDGES[0]:.6f})."
    )
    lines.append(
        "- **Uncertainty:** Wilson 95% CI for proportions; seeded 1000-resample bootstrap "
        "for GF peak/entropy; bias-corrected basic-bootstrap CI for round-to-round KL."
    )
    lines.append(
        f"- **Small-N flag:** per-opponent cells with N < {SMALL_N_THRESHOLD} resolved waves "
        "are flagged `†` and excluded from cross-opponent rankings."
    )
    lines.append(f"- **Random seed:** {SEED} (deterministic across runs).")
    lines.append("")

    # Producer contract table (per-perspective file presence + checksums)
    lines.append("## Producer contract & checksums")
    lines.append("")
    lines.append(
        "Per-perspective file presence, data-row count, and SHA-256 of each CSV. "
        "Two reports are comparable only when these checksums match."
    )
    lines.append("")
    lines.append("| Perspective | File | Present | Rows | Size | SHA-256 |")
    lines.append("|-------------|------|:-------:|-----:|-----:|---------|")
    for p in perspectives:
        for fname in REQUIRED_FILES:
            m = p.files[fname]
            if m.present:
                lines.append(
                    f"| `{p.label}` | `{fname}` | ✓ | {m.data_rows:,} | "
                    f"{_fmt_bytes(m.size_bytes or 0)} | `{m.sha256}` |"
                )
            else:
                lines.append(f"| `{p.label}` | `{fname}` | ✗ | — | — | — |")
    lines.append("")

    # Data availability / unavailable sections
    lines.append("## Data availability")
    lines.append("")
    any_missing = False
    for p in perspectives:
        missing = p.missing_required()
        if missing:
            any_missing = True
            affected = sorted({s for f in missing for s in FILE_SECTIONS.get(f, [])})
            lines.append(
                f"- `{p.label}`: **missing** {', '.join(f'`{f}`' for f in missing)} → "
                f"sections unavailable: {', '.join(affected)}"
            )
            if "dejavu-waves.csv" in missing and p.legacy_present:
                lines.append(
                    f"  - legacy `{LEGACY_WAVE_FILE}` present → treated as the autopilot gun "
                    "only; dejavu-sourced sections remain unavailable."
                )
    if not any_missing:
        lines.append("- All required files present in every perspective. No sections are unavailable.")
    lines.append("")
    return "\n".join(lines)


def write_report(out_path: Path, header: str, body: str) -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(header + "\n" + body, encoding="utf-8")


# --------------------------------------------------------------------------------------
# Phase 3 — deterministic figure generation (PNG assets keyed by question ID)
# --------------------------------------------------------------------------------------

#: PNG metadata stripped of any varying fields so two runs produce byte-identical assets.
PNG_METADATA = {"Software": None, "Creation Time": None}


def _safe_name(s: str) -> str:
    """Filesystem/link-safe token for an opponent name embedded in a filename."""
    return "".join(c if (c.isalnum() or c in "._-") else "_" for c in s)


@dataclass
class Figures:
    """Writes deterministic PNGs into the assets dir and returns markdown image links
    relative to the report file. All assets are overwritten on every run and use stable
    names so links never break."""

    assets_dir: Path
    report_dir: Path
    written: list[str] = field(default_factory=list)

    def link(self, name: str, alt: str) -> str:
        rel = os.path.relpath(self.assets_dir / name, self.report_dir).replace(os.sep, "/")
        return f"![{alt}]({rel})"

    def save(self, fig, name: str, alt: str) -> str:
        self.assets_dir.mkdir(parents=True, exist_ok=True)
        fig.savefig(self.assets_dir / name, dpi=100, bbox_inches="tight",
                    metadata=PNG_METADATA)
        plt.close(fig)
        if name not in self.written:
            self.written.append(name)
        return self.link(name, alt)


def _ax(w: float = 6.4, h: float = 3.6):
    fig, ax = plt.subplots(figsize=(w, h))
    return fig, ax


def _fig_hist(values, title: str, xlabel: str, bins: int = 40, color: str = "#4477aa"):
    """Single descriptive histogram (binned, never a scatter)."""
    v = np.asarray(values, dtype=np.float64)
    v = v[np.isfinite(v)]
    fig, ax = _ax()
    if v.size:
        ax.hist(v, bins=bins, color=color, edgecolor="white", linewidth=0.3)
    ax.set_title(title)
    ax.set_xlabel(xlabel)
    ax.set_ylabel("waves")
    ax.grid(True, axis="y", alpha=0.25)
    fig.tight_layout()
    return fig


def _fig_gf_hist(values, title: str, color: str = "#4477aa"):
    """GF histogram on the canonical fixed bin grid."""
    v = np.asarray(values, dtype=np.float64)
    v = v[np.isfinite(v)]
    fig, ax = _ax()
    if v.size:
        ax.hist(v, bins=GF_BIN_EDGES, color=color, edgecolor="white", linewidth=0.2)
    ax.axvline(0.0, color="#bb5566", linestyle="--", linewidth=1.0, label="head-on")
    ax.set_title(title)
    ax.set_xlabel("GuessFactor (−1 … +1)")
    ax.set_ylabel("waves")
    ax.set_xlim(-1, 1)
    ax.grid(True, axis="y", alpha=0.25)
    ax.legend(loc="upper right", fontsize=8)
    fig.tight_layout()
    return fig


def _fig_heatmap(x, y, title: str):
    """2-D position heatmap rendered from a binned histogram (per the perf guard)."""
    x = np.asarray(x, dtype=np.float64)
    y = np.asarray(y, dtype=np.float64)
    ok = np.isfinite(x) & np.isfinite(y)
    x, y = x[ok], y[ok]
    fig, ax = _ax(5.6, 4.2)
    if x.size:
        H, xe, ye = np.histogram2d(
            x, y, bins=(40, 30), range=[[0, FIELD_W], [0, FIELD_H]])
        ax.imshow(H.T, origin="lower", extent=[0, FIELD_W, 0, FIELD_H],
                  aspect="equal", cmap="magma")
    ax.set_title(title)
    ax.set_xlabel("x (px)")
    ax.set_ylabel("y (px)")
    fig.tight_layout()
    return fig


def _gun_sweep(df: pd.DataFrame, n: int = 81):
    """Hit-rate vs a swept fixed aim-GF ('virtual gun' curve), canonical rule."""
    aims = np.linspace(-1.0, 1.0, n)
    rates = np.full(n, np.nan)
    for i, a in enumerate(aims):
        h, m = hit_rate_analytic(df, float(a))
        if m:
            rates[i] = h / m
    return aims, rates


def _h(letter: str) -> str:
    return f"## Section {letter} — {dict(SECTION_TITLES)[letter]}"


def _nan_summary(df: pd.DataFrame) -> str:
    cols = [c for c in df.columns if c not in TAG_COLS]
    if df.empty:
        return "_(file absent)_"
    rates = {c: df[c].isna().mean() for c in cols}
    bad = {c: r for c, r in rates.items() if r > 0}
    if not bad:
        return "no missing values"
    return ", ".join(f"`{c}` {_pct(r, 2)}" for c, r in sorted(bad.items(), key=lambda kv: -kv[1]))


# --------------------------------------------------------------------------------------
# Section A — Dataset inventory & integrity
# --------------------------------------------------------------------------------------

def section_a(ds: Dataset) -> str:
    P = ds.perspectives
    out = [_h("A"), ""]

    # A1 — inventory
    file_rows = {fn: 0 for fn in REQUIRED_FILES}
    file_present = {fn: 0 for fn in REQUIRED_FILES}
    total_bytes = 0
    for p in P:
        for fn in REQUIRED_FILES:
            m = p.files[fn]
            if m.present:
                file_present[fn] += 1
                file_rows[fn] += m.data_rows or 0
                total_bytes += m.size_bytes or 0
    rounds_per_battle = (
        int(ds.ticks.groupby(["matchup", "perspective"], observed=True)["round"].max().mode().iloc[0]) + 1
        if not ds.ticks.empty else 0
    )
    rows = [
        (f"`{fn}`", f"{file_present[fn]}/{len(P)}", f"{file_rows[fn]:,}")
        for fn in REQUIRED_FILES
    ]
    out += [
        "### A1 — Inventory",
        "",
        f"{len({p.matchup for p in P})} matchups · {len({p.robot for p in P})} distinct robots · "
        f"{len(P)} perspectives · {rounds_per_battle} rounds/battle · "
        f"{_fmt_bytes(total_bytes)} total.",
        "",
        md_table(["File", "Perspectives present", "Total data rows"], rows),
        "",
    ]

    # A2 — real / virtual / total per wave file
    dej_total = file_rows["dejavu-waves.csv"]
    auto_total = file_rows["autopilot-waves.csv"]
    their_total = file_rows["their-waves.csv"]
    require(
        ds.autopilot_real_count + ds.autopilot_virtual_count == auto_total,
        f"A2: autopilot real ({ds.autopilot_real_count}) + virtual "
        f"({ds.autopilot_virtual_count}) != total ({auto_total})",
    )
    require(
        len(ds.dejavu) == dej_total,
        f"A2: loaded dejavu rows ({len(ds.dejavu)}) != file total ({dej_total})",
    )
    a2 = [
        ("`dejavu-waves.csv`", f"{dej_total:,}", "0", f"{dej_total:,}", "in-game hero real gun"),
        ("`autopilot-waves.csv`", f"{ds.autopilot_real_count:,}",
         f"{ds.autopilot_virtual_count:,}", f"{auto_total:,}", "shadow gun (real + virtual fan)"),
        ("`their-waves.csv`", f"{their_total:,}", "0", f"{their_total:,}", "incoming (defensive)"),
    ]
    out += [
        "### A2 — Real / virtual / total labels per wave file",
        "",
        "The honest targeting label budget is dejavu's real waves; the autopilot virtual fan "
        "is a biased counterfactual, not independent labels.",
        "",
        md_table(["File", "Real", "Virtual", "Total", "Meaning"], a2),
        "",
    ]

    # A3 — per-column NaN
    out += [
        "### A3 — Missing-value (NaN) rate per column",
        "",
        md_table(
            ["File", "Columns with NaN (rate)"],
            [
                ("`dejavu-waves.csv`", _nan_summary(ds.dejavu)),
                ("`autopilot-waves.csv` (real)", _nan_summary(ds.autopilot_real)),
                ("`their-waves.csv`", _nan_summary(ds.their)),
                ("`ticks.csv`", _nan_summary(ds.ticks)),
                ("`scan.csv`", _nan_summary(ds.scan)),
                ("`scores.csv`", _nan_summary(ds.scores)),
            ],
        ),
        "",
    ]

    # A4 — unresolved waves
    dej_unres = ds.dejavu["our_break_gf"].isna().mean() if not ds.dejavu.empty else float("nan")
    auto_unres = ds.autopilot_real["our_break_gf"].isna().mean() if not ds.autopilot_real.empty else float("nan")
    their_unres = ds.their["their_break_gf"].isna().mean() if not ds.their.empty else float("nan")
    out += [
        "### A4 — Unresolved-wave rate (break columns NaN — round ended first)",
        "",
        "Unresolved waves are dropped from label tables but are **not** missing at random "
        "(they cluster at round end / low energy — trap #6).",
        "",
        md_table(
            ["Wave source", "Unresolved rate"],
            [
                ("dejavu (outgoing real)", _pct(dej_unres, 2)),
                ("autopilot (outgoing real)", _pct(auto_unres, 2)),
                ("their (incoming)", _pct(their_unres, 2)),
            ],
        ),
        "",
    ]

    # A5 — id collisions (keyed within matchup+perspective+round)
    def dup_count(df, idcol):
        if df.empty or idcol not in df.columns:
            return 0, 0
        key = df[["matchup", "perspective", "round", idcol]]
        return int(key.duplicated().sum()), len(df)
    dej_dup, dej_n = dup_count(ds.dejavu, "our_fire_bullet_id")
    their_dup, their_n = dup_count(ds.their, "their_fire_tick")
    hash_per_persp = (
        ds.scan.groupby(["matchup", "perspective"], observed=True)["scan_opponent_id_hash"].nunique()
        if not ds.scan.empty else pd.Series(dtype=int)
    )
    multi_hash = int((hash_per_persp > 1).sum())
    out += [
        "### A5 — Identifier collisions (keyed by matchup+perspective+round)",
        "",
        "`our_fire_bullet_id` and `their_fire_tick` legitimately repeat *across* rounds, so "
        "the leakage-safe key includes the round. Within-round duplicates would corrupt "
        "per-fire grouping.",
        "",
        md_table(
            ["Identifier", "Within-key duplicates", "Rows"],
            [
                ("`our_fire_bullet_id` (dejavu real)", f"{dej_dup:,}", f"{dej_n:,}"),
                ("`their_fire_tick` (incoming)", f"{their_dup:,}", f"{their_n:,}"),
                ("`scan_opponent_id_hash` (perspectives w/ >1 hash)", f"{multi_hash}",
                 f"{len(hash_per_persp)} perspectives"),
            ],
        ),
        "",
    ]

    # A6 — hash <-> name stability
    out += ["### A6 — Opponent hash ↔ name stability", ""]
    if ds.scan.empty:
        out += ["_scan.csv unavailable._", ""]
    else:
        pair = ds.scan.groupby("scan_opponent_id_hash", observed=True)["opponent"].agg(
            lambda s: sorted(set(map(str, s)))
        )
        a6_rows, stable = [], True
        for h, names in pair.items():
            if len(names) != 1:
                stable = False
            a6_rows.append((f"`{int(h)}`", ", ".join(f"`{n}`" for n in names),
                            "✓" if len(names) == 1 else "✗ AMBIGUOUS"))
        require(stable, "A6: a scan_opponent_id_hash maps to more than one opponent name")
        out += [
            f"Every hash maps to exactly one opponent name ({'stable' if stable else 'UNSTABLE'}).",
            "",
            md_table(["scan_opponent_id_hash", "Opponent name(s)", "Stable"], a6_rows),
            "",
        ]

    # A7 — zap prevalence + zap∩fire overlap
    out += ["### A7 — Inactivity-zap prevalence (per opponent) + zap∩fire overlap", ""]
    if ds.scan.empty:
        out += ["_scan.csv unavailable._", ""]
    else:
        zap_keys = set()
        zap_scan = ds.scan[ds.scan["scan_their_inactivity_zap_active"] == 1]
        if not zap_scan.empty:
            zap_keys = set(
                map(tuple, zap_scan[["matchup", "perspective", "round", "tick"]].to_numpy())
            )
        a7_rows = []
        for opp in ds.opponents():
            sc = ds.scan[(ds.scan["opponent"] == opp) & (~ds.scan["self_play"])]
            zap_tick_rate = sc["scan_their_inactivity_zap_active"].mean() if len(sc) else float("nan")
            tw = ds.their_for_opponent(opp)
            if len(tw) and zap_keys:
                keys = tw[["matchup", "perspective", "round", "their_fire_tick"]].to_numpy()
                overlap = np.mean([tuple(k) in zap_keys for k in keys])
            else:
                overlap = 0.0
            a7_rows.append((f"`{opp}`", f"{len(sc):,}", _pct(zap_tick_rate, 2),
                            f"{len(tw):,}", _pct(overlap, 2)))
        out += [
            "Zap is a coarse per-scan-tick blip; it should be concentrated in a few low-energy "
            "bots and overlap almost no logged incoming fires (validates 'treat incoming as real').",
            "",
            md_table(["Opponent", "Scan ticks", "Zap-active rate", "Incoming fires", "Fires on zap tick"],
                     a7_rows),
            "",
        ]
    return "\n".join(out)


# --------------------------------------------------------------------------------------
# Section B — Game & physics orientation
# --------------------------------------------------------------------------------------

def _pcts(s: pd.Series, qs=(0.1, 0.5, 0.9)) -> list[str]:
    s = s.dropna()
    if s.empty:
        return ["—"] * len(qs)
    return [_f(s.quantile(q), 1) for q in qs]


def section_b(ds: Dataset, figs: Figures) -> str:
    out = [_h("B"), ""]
    dej = ds.dejavu[~ds.dejavu["self_play"]] if not ds.dejavu.empty else ds.dejavu
    their = ds.their[~ds.their["self_play"]] if not ds.their.empty else ds.their

    # B1 — distance percentiles
    out += ["### B1 — Engagement distance at fire time (px)", ""]
    if dej.empty:
        out += ["_dejavu-waves.csv unavailable._", ""]
    else:
        rows = [("overall", f"{len(dej):,}", *_pcts(dej["our_fire_distance"]))]
        for opp in ds.opponents():
            d = ds.dejavu_for_opponent(opp)["our_fire_distance"]
            rows.append((f"`{opp}`", f"{len(d):,}", *_pcts(d)))
        img = figs.save(
            _fig_hist(dej["our_fire_distance"], "B1 — Engagement distance at fire time",
                      "distance (px)"),
            "B1-distance-hist.png", "B1 engagement-distance histogram")
        out += [md_table(["Scope", "N", "p10", "p50", "p90"], rows), "", img, ""]

    # B2 — bullet power (ours = dejavu vs theirs = their-waves)
    out += ["### B2 — Bullet power: ours (dejavu) vs theirs (incoming)", ""]
    if dej.empty or their.empty:
        out += ["_requires dejavu-waves.csv and their-waves.csv._", ""]
    else:
        rows = [("overall",
                 _f(dej["our_fire_power"].mean()), _f(dej["our_fire_power"].median()),
                 _f(their["their_fire_power"].mean()), _f(their["their_fire_power"].median()))]
        for opp in ds.opponents():
            od = ds.dejavu_for_opponent(opp)["our_fire_power"]
            td = ds.their_for_opponent(opp)["their_fire_power"]
            rows.append((f"`{opp}`", _f(od.mean()), _f(od.median()), _f(td.mean()), _f(td.median())))
        out += [md_table(["Scope", "Our mean", "Our p50", "Their mean", "Their p50"], rows), ""]

    # B3 — lateral / advancing velocity marginals
    out += ["### B3 — Velocity at fire time (px/tick)", ""]
    if dej.empty:
        out += ["_dejavu-waves.csv unavailable._", ""]
    else:
        lv, av = dej["our_fire_lateral_velocity"], dej["our_fire_advancing_velocity"]
        out += [md_table(
            ["Quantity", "mean", "std", "p10", "p50", "p90"],
            [("lateral velocity", _f(lv.mean()), _f(lv.std()), *_pcts(lv)),
             ("advancing velocity", _f(av.mean()), _f(av.std()), *_pcts(av))],
        ), ""]

    # B4 — flight time + trend with distance/power
    out += ["### B4 — Bullet flight time (ticks) = label latency", ""]
    if dej.empty:
        out += ["_dejavu-waves.csv unavailable._", ""]
    else:
        ft = (dej["our_break_tick"] - dej["our_fire_tick"]).astype(float)
        ok = ft.notna() & dej["our_fire_distance"].notna()
        r_dist = float(np.corrcoef(ft[ok], dej["our_fire_distance"][ok])[0, 1]) if ok.sum() > 2 else float("nan")
        ok2 = ft.notna() & dej["our_fire_power"].notna()
        r_pow = float(np.corrcoef(ft[ok2], dej["our_fire_power"][ok2])[0, 1]) if ok2.sum() > 2 else float("nan")
        out += [md_table(
            ["mean", "p10", "p50", "p90", "corr(flight, distance)", "corr(flight, power)"],
            [(_f(ft.mean()), *_pcts(ft), _f(r_dist, 3), _f(r_pow, 3))],
        ),
            "Latency scales with distance/power, so online evaluation must use each wave's own "
            "flight time, not a global constant.",
            "",
            figs.save(_fig_hist(ft, "B4 — Bullet flight time (label latency)", "flight time (ticks)",
                                color="#228833"),
                      "B4-flight-time-hist.png", "B4 flight-time histogram"),
            ""]

    # B5 — round length
    out += ["### B5 — Round length (ticks)", ""]
    if ds.ticks.empty:
        out += ["_ticks.csv unavailable._", ""]
    else:
        # `tick` is global across the whole battle, so round length is the per-round row
        # count (one logged row per tick of that round), not max(tick).
        rl = ds.ticks.groupby(["matchup", "perspective", "round"], observed=True).size()
        t = ds.ticks.copy()
        t["_rt"] = t.groupby(["matchup", "perspective", "round"], observed=True).cumcount()
        curve = t.groupby("_rt", observed=True)["our_energy"].mean()
        curve = curve[curve.index < int(rl.quantile(0.9))]
        fig, ax = _ax()
        ax.plot(curve.index.to_numpy(), curve.to_numpy(), color="#aa3377", linewidth=1.2)
        ax.set_title("B5 — Mean energy vs within-round tick")
        ax.set_xlabel("tick within round")
        ax.set_ylabel("mean our_energy")
        ax.grid(True, alpha=0.25)
        fig.tight_layout()
        out += [md_table(["N rounds", "p10", "p50", "p90", "max"],
                         [(f"{len(rl):,}", *_pcts(rl), _f(rl.max(), 0))]),
                "",
                figs.save(fig, "B5-energy-vs-tick.png", "B5 energy-vs-tick curve"), ""]

    # B6 — scan coverage
    out += ["### B6 — Scan coverage (fresh-scan exposure)", ""]
    if ds.scan.empty or ds.ticks.empty:
        out += ["_requires scan.csv and ticks.csv._", ""]
    else:
        scan_n = ds.scan.groupby("opponent", observed=True).size()
        tick_n = ds.ticks.groupby("opponent", observed=True).size()
        rows = []
        for opp in ds.opponents():
            cov = scan_n.get(opp, 0) / tick_n.get(opp, np.nan) if tick_n.get(opp, 0) else float("nan")
            med_gap = ds.scan[(ds.scan["opponent"] == opp) & (~ds.scan["self_play"])]["scan_ticks_since_scan"].median()
            rows.append((f"`{opp}`", _pct(cov, 1), _f(med_gap, 1)))
        out += [md_table(["Opponent", "Scan coverage", "Median ticks-since-scan"], rows), ""]

    # B7 — wall proximity at fire time
    out += ["### B7 — Wall proximity at fire time (edge-to-wall gap, px)", ""]
    if dej.empty:
        out += ["_dejavu-waves.csv unavailable._", ""]
    else:
        x, y = dej["our_fire_x"], dej["our_fire_y"]
        gap = np.minimum.reduce([x, FIELD_W - x, y, FIELD_H - y]) - ROBOT_HALF
        gap = pd.Series(gap)
        out += [md_table(["N", "p10", "p50", "p90"],
                         [(f"{len(gap):,}", *_pcts(gap))]),
                f"Battlefield {int(FIELD_W)}×{int(FIELD_H)} px, robot half-width {int(ROBOT_HALF)} px.",
                "",
                figs.save(_fig_heatmap(x, y, "B7 — Fire-position density"),
                          "B7-position-heatmap.png", "B7 fire-position heatmap"), ""]
    return "\n".join(out)


# --------------------------------------------------------------------------------------
# Section C — Targeting (offensive gun)
# --------------------------------------------------------------------------------------

def section_c(ds: Dataset, max_model_rows: int, figs: Figures) -> str:
    out = [_h("C"), ""]
    if ds.dejavu.empty:
        return "\n".join(out + ["_dejavu-waves.csv unavailable — Section C cannot be computed._", ""])

    opps = ds.opponents()

    # C1 — GF shape per opponent
    c1 = []
    for opp in opps:
        g = ds.dejavu_for_opponent(opp)["our_break_gf"].dropna()
        n = len(g)
        p = gf_hist_laplace(g)
        top1 = float(p.max())
        shape = "peaked" if top1 > 0.10 else "flat"
        c1.append((f"`{opp}`", f"{n:,}{_flag(n)}", _f(g.mean(), 3), _f(g.std(), 3),
                   _f(peak_gf(g), 3), _pct(top1, 1), shape))
    out += ["### C1 — GuessFactor distribution shape (dejavu real waves)", "",
            md_table(["Opponent", "N", "GF mean", "GF std", "Peak bin", "Top-1 mass", "Shape"], c1),
            ""]
    for opp in opps:
        g = ds.dejavu_for_opponent(opp)["our_break_gf"].dropna()
        out.append(figs.save(
            _fig_gf_hist(g, f"C1 — GF distribution vs `{opp}`"),
            f"C1-gf-hist-{_safe_name(opp)}.png", f"C1 GF histogram vs {opp}"))
    out.append("")

    # C2 — entropy + head-on hit baseline
    c2 = []
    for opp in opps:
        d = ds.dejavu_for_opponent(opp)
        g = d["our_break_gf"].dropna()
        n = len(g)
        ent = shannon_entropy_bits(gf_hist_laplace(g))
        h, m = hit_rate_analytic(d, 0.0)
        lo, hi = wilson_ci(h, m)
        c2.append((f"`{opp}`", f"{n:,}{_flag(n)}", _f(ent, 2),
                   _pct(h / m if m else float('nan'), 2), _ci_pct(lo, hi)))
    out += ["### C2 — GF entropy (bits) + head-on hit baseline (canonical, aim=0)", "",
            md_table(["Opponent", "N", "GF entropy", "Head-on hit", "95% CI"], c2),
            "Entropy is intrinsic targeting difficulty; head-on is the zero-learning baseline to beat.", ""]

    # C3 — concentration
    c3 = []
    for opp in opps:
        g = ds.dejavu_for_opponent(opp)["our_break_gf"].dropna()
        p = gf_hist_laplace(g)
        c3.append((f"`{opp}`", f"{len(g):,}{_flag(len(g))}", _pct(float(p.max()), 1),
                   _f(effective_bins(p), 1)))
    out += ["### C3 — GF mass concentration", "",
            md_table(["Opponent", "N", "Top-1 bin mass", "Effective bins (perplexity)"], c3),
            f"Out of {GF_BIN_COUNT} canonical bins; low effective-bins ⇒ peaked/learnable.", ""]

    # C4 — floor (autopilot) vs hero (dejavu) + selection bias
    out += ["### C4 — Current-autopilot floor vs in-game hero (unpaired) + selection bias", ""]
    if ds.autopilot_real.empty:
        out += ["_autopilot-waves.csv unavailable — floor line cannot be computed._", ""]
    else:
        c4 = []
        for opp in opps:
            dh = ds.dejavu_for_opponent(opp)
            af = ds.autopilot_real[(ds.autopilot_real["opponent"] == opp) & (~ds.autopilot_real["self_play"])]
            hh, hm = hit_rate_analytic(dh, dh["our_fire_aim_gf"].to_numpy(dtype=float))
            fh, fm = hit_rate_analytic(af, af["our_fire_aim_gf"].to_numpy(dtype=float))
            mae_h = (dh["our_fire_aim_gf"] - dh["our_break_gf"]).abs().mean()
            mae_f = (af["our_fire_aim_gf"] - af["our_break_gf"]).abs().mean()
            # selection bias: fire-time feature distance between the two guns
            dist_gap = af["our_fire_distance"].mean() - dh["our_fire_distance"].mean()
            lv_gap = af["our_fire_lateral_velocity"].abs().mean() - dh["our_fire_lateral_velocity"].abs().mean()
            c4.append((f"`{opp}`",
                       f"{_pct(fh / fm if fm else float('nan'), 2)} (N={fm}{_flag(fm)})",
                       f"{_pct(hh / hm if hm else float('nan'), 2)} (N={hm}{_flag(hm)})",
                       _f(mae_f, 3), _f(mae_h, 3), _f(dist_gap, 1), _f(lv_gap, 2)))
        out += [md_table(
            ["Opponent", "Floor hit", "Hero hit", "Floor |GF err|", "Hero |GF err|",
             "Δdist (floor−hero)", "Δ|latV|"], c4),
            "The floor fires at its own self-selected ticks/states, so the floor↔hero gap is "
            "**not** pure aim quality (trap #7): Δdist / Δ|latV| show how far apart the two "
            "fire-time samples are.", ""]

    # C5 — ceiling (quantization bound vs best-static vs CV-model vs current)
    out += ["### C5 — Achievable ceiling", ""]
    from sklearn.ensemble import RandomForestRegressor
    from sklearn.model_selection import GroupKFold, cross_val_predict
    rng = np.random.default_rng(SEED)
    c5 = []
    for opp in opps:
        d = ds.dejavu_for_opponent(opp).dropna(subset=["our_break_gf"] + TARGET_FEATURES)
        n = len(d)
        if n == 0:
            continue
        qh, qm = hit_rate_analytic(d, quantize_gf(d["our_break_gf"].to_numpy(dtype=float)))
        bg, br, _ = best_static_gf(d)
        cur_h, cur_m = hit_rate_analytic(d, d["our_fire_aim_gf"].to_numpy(dtype=float))
        # CV model (GroupKFold by round) — only when enough data and >=2 round groups
        cv_rate = float("nan")
        groups = d["round"].to_numpy()
        if n >= 200 and len(np.unique(groups)) >= 3:
            ds_d = d
            if n > max_model_rows:
                idx = rng.choice(n, max_model_rows, replace=False)
                ds_d = d.iloc[idx]
                groups = ds_d["round"].to_numpy()
            X = ds_d[TARGET_FEATURES].to_numpy(dtype=float)
            yv = ds_d["our_break_gf"].to_numpy(dtype=float)
            k = min(5, len(np.unique(groups)))
            model = RandomForestRegressor(n_estimators=60, max_depth=8, random_state=SEED, n_jobs=-1)
            pred = cross_val_predict(model, X, yv, cv=GroupKFold(k), groups=groups)
            ch, cm = canonical_hit(pred, yv, ds_d["our_fire_distance"].to_numpy(dtype=float),
                                   ds_d["our_fire_mea"].to_numpy(dtype=float),
                                   ds_d["our_fire_bearing_absolute"].to_numpy(dtype=float)), len(yv)
            cv_rate = float(np.mean(ch))
        c5.append((f"`{opp}`", f"{n:,}{_flag(n)}",
                   _pct(qh / qm if qm else float('nan'), 1),
                   f"{_pct(br, 2)} @ GF={_f(bg, 2)}",
                   _pct(cv_rate, 2), _pct(cur_h / cur_m if cur_m else float('nan'), 2)))
    out += [md_table(
        ["Opponent", "N", "Quantization bound", "Best-static", "CV-model", "Current (hero)"], c5),
        "_Quantization bound = aim the realized bin every time (sanity bound, **not** learnable — "
        "aim = label). The learnable ceiling is best-static / CV-model._", ""]

    # C6 — correlation + mutual information vs break_gf
    out += ["### C6 — Fire-time feature relation to `our_break_gf` (pooled dejavu)", ""]
    from sklearn.feature_selection import mutual_info_regression
    d = ds.dejavu[~ds.dejavu["self_play"]].dropna(subset=["our_break_gf"] + TARGET_FEATURES)
    if len(d) > max_model_rows:
        d = d.iloc[rng.choice(len(d), max_model_rows, replace=False)]
    y = d["our_break_gf"].to_numpy(dtype=float)
    Xc = d[TARGET_FEATURES].to_numpy(dtype=float)
    mi = mutual_info_regression(Xc, y, random_state=SEED)
    c6 = []
    for i, feat in enumerate(TARGET_FEATURES):
        r = float(np.corrcoef(Xc[:, i], y)[0, 1])
        c6.append((f"`{feat}`", _f(r, 3), _f(mi[i], 4)))
    c6.sort(key=lambda t: -float(t[2]))
    capped = len(d) >= max_model_rows
    note = (f"Computed on a seeded subsample of {len(d):,} dejavu real waves "
            f"(capped at --max-model-rows={max_model_rows:,}; **not** full-data correlation)."
            if capped else f"Pooled over {len(d):,} dejavu real waves (below the "
            f"--max-model-rows={max_model_rows:,} cap, so full-data).")
    out += [md_table(["Feature", "Pearson r", "Mutual info (nats)"], c6), note, ""]

    # C7 — segmentation variance explained (eta^2)
    out += ["### C7 — GF variance explained by segmentation axes (η²)", ""]
    seg_axes = {
        "distance (5 bins)": "our_fire_distance",
        "lateral velocity (5 bins)": "our_fire_lateral_velocity",
        "advancing velocity (5 bins)": "our_fire_advancing_velocity",
        "wall gap (3 bins)": None,
    }
    c7 = []
    dseg = ds.dejavu[~ds.dejavu["self_play"]].dropna(subset=["our_break_gf"])
    yv = dseg["our_break_gf"].to_numpy(dtype=float)
    for label, col in seg_axes.items():
        if col is None:
            x = dseg["our_fire_x"].to_numpy(dtype=float)
            yy = dseg["our_fire_y"].to_numpy(dtype=float)
            vals = np.minimum.reduce([x, FIELD_W - x, yy, FIELD_H - yy]) - ROBOT_HALF
            nb = 3
        else:
            vals = dseg[col].to_numpy(dtype=float)
            nb = 5
        try:
            cats = pd.qcut(vals, nb, duplicates="drop")
            eta = correlation_ratio(cats.codes, yv)
        except (ValueError, IndexError):
            eta = float("nan")
        c7.append((label, _f(eta, 4)))
    c7.sort(key=lambda t: -(float(t[1]) if t[1] != "—" else -1))
    out += [md_table(["Segmentation axis", "η² (variance explained)"], c7),
            "Higher η² ⇒ the axis separates GF and is worth its data cost.", ""]

    # C8 — temporal autocorrelation of break_gf within rounds
    out += ["### C8 — Temporal autocorrelation of `our_break_gf` (within round)", ""]
    c8 = []
    lag1_mean = float('nan')
    for lag in (1, 2, 3):
        vals = []
        for _, grp in ds.dejavu[~ds.dejavu["self_play"]].groupby(
                ["matchup", "perspective", "round"], observed=True):
            s = grp.sort_values("our_fire_tick")["our_break_gf"].dropna().to_numpy(dtype=float)
            if s.size > lag + 2:
                a, b = s[:-lag], s[lag:]
                if a.std() > 0 and b.std() > 0:
                    vals.append(np.corrcoef(a, b)[0, 1])
        m = float(np.mean(vals)) if vals else float('nan')
        if lag == 1:
            lag1_mean = m
        c8.append((f"lag {lag}", _f(m, 3), f"{len(vals)} rounds"))
    note = ("Gun-side `our_break_gf` is essentially **not** autocorrelated on the corrected "
            "aim-time frame (|lag-1| < 0.15), so a sequence/pattern *gun* gains little; the "
            "lag-1 structure earlier passes saw lives on the **movement** channel "
            "(`their_break_gf`, see [sequence.md](sequence.md))."
            if (np.isfinite(lag1_mean) and abs(lag1_mean) < 0.15) else
            "Non-zero autocorrelation ⇒ a sequence/pattern gun could beat the memoryless VCS.")
    out += [md_table(["Lag", "Mean autocorrelation", "Rounds"], c8), note, ""]

    # C9 — hit rate vs distance
    out += ["### C9 — Hit rate vs engagement distance (canonical, hero aim)", ""]
    dd = ds.dejavu[~ds.dejavu["self_play"]].dropna(subset=["our_break_gf"])
    bins = [0, 150, 300, 450, 600, 900]
    labels = ["0–150", "150–300", "300–450", "450–600", "600+"]
    dd = dd.assign(_db=pd.cut(dd["our_fire_distance"], bins=bins, labels=labels, right=False))
    c9 = []
    for lab in labels:
        sub = dd[dd["_db"] == lab]
        h, m = hit_rate_analytic(sub, sub["our_fire_aim_gf"].to_numpy(dtype=float))
        lo, hi = wilson_ci(h, m)
        c9.append((lab, f"{m:,}{_flag(m)}", _pct(h / m if m else float('nan'), 2), _ci_pct(lo, hi)))
    fig, ax = _ax()
    xs = [("0\u2013150", 75), ("150\u2013300", 225), ("300\u2013450", 375),
          ("450\u2013600", 525), ("600+", 700)]
    yvals = []
    for lab, _ in xs:
        sub = dd[dd["_db"] == lab]
        h, m = hit_rate_analytic(sub, sub["our_fire_aim_gf"].to_numpy(dtype=float))
        yvals.append(100.0 * (h / m) if m else np.nan)
    ax.plot([c for _, c in xs], yvals, marker="o", color="#4477aa", linewidth=1.4)
    ax.set_title("C9 — Hero hit rate vs engagement distance")
    ax.set_xlabel("distance (px, bin center)")
    ax.set_ylabel("hit rate (%)")
    ax.grid(True, alpha=0.25)
    fig.tight_layout()
    out += [md_table(["Distance (px)", "N", "Hero hit", "95% CI"], c9), "",
            figs.save(fig, "C9-hitrate-vs-distance.png", "C9 hit-rate vs distance"), ""]
    return "\n".join(out)


# --------------------------------------------------------------------------------------
# Section D — Movement (defensive surfing)
# --------------------------------------------------------------------------------------

def section_d(ds: Dataset, figs: Figures) -> str:
    out = [_h("D"), ""]
    if ds.their.empty:
        return "\n".join(out + ["_their-waves.csv unavailable — Section D cannot be computed._", ""])
    opps = ds.opponents()

    # D1 — our dodge profile (their_break_gf) flatness
    d1 = []
    for opp in opps:
        g = ds.their_for_opponent(opp)["their_break_gf"].dropna()
        n = len(g)
        p = gf_hist_laplace(g)
        d1.append((f"`{opp}`", f"{n:,}{_flag(n)}", _f(g.mean(), 3),
                   _f(shannon_entropy_bits(p), 2), _f(peak_gf(g), 3), _pct(float(p.max()), 1)))
    out += ["### D1 — Our dodge profile (`their_break_gf` — where we end up on their waves)", "",
            md_table(["Opponent", "N", "GF mean", "GF entropy", "Peak bin", "Top-1 mass"], d1),
            "A flat profile (high entropy) = hard to hit; a peak reveals an exploitable movement bias.",
            ""]
    for opp in opps:
        g = ds.their_for_opponent(opp)["their_break_gf"].dropna()
        out.append(figs.save(
            _fig_gf_hist(g, f"D1 — Dodge profile vs `{opp}`", color="#ee6677"),
            f"D1-dodge-hist-{_safe_name(opp)}.png", f"D1 dodge-profile histogram vs {opp}"))
    out.append("")

    # D2 — hit-us rate per opponent + power band
    d2 = []
    for opp in opps:
        tw = ds.their_for_opponent(opp)
        n = len(tw)
        hu = tw["their_hit_us"].mean()
        lo_pw = tw[tw["their_fire_power"] < 1.5]["their_hit_us"]
        hi_pw = tw[tw["their_fire_power"] >= 1.5]["their_hit_us"]
        # most dangerous GF bin
        res = tw.dropna(subset=["their_break_gf"])
        danger = "—"
        if len(res):
            res = res.assign(_b=pd.cut(res["their_break_gf"], bins=GF_BIN_EDGES))
            grp = res.groupby("_b", observed=True)["their_hit_us"].mean()
            if grp.notna().any():
                danger = f"{grp.idxmax()}"
        d2.append((f"`{opp}`", f"{n:,}{_flag(n)}", _pct(hu, 2),
                   _pct(lo_pw.mean() if len(lo_pw) else float('nan'), 2),
                   _pct(hi_pw.mean() if len(hi_pw) else float('nan'), 2), danger))
    out += ["### D2 — Hit-us rate by opponent and bullet-power band", "",
            md_table(["Opponent", "N", "Hit-us", "Power<1.5", "Power≥1.5", "Most-dangerous GF bin"], d2), ""]

    # D3 — opponent aim bias
    d3 = []
    for opp in opps:
        g = ds.their_for_opponent(opp)["their_break_gf"].dropna()
        lo, hi = bootstrap_ci(g.to_numpy(dtype=float), np.mean) if len(g) else (float('nan'), float('nan'))
        d3.append((f"`{opp}`", f"{len(g):,}{_flag(len(g))}", _f(g.mean(), 3), _ci_val(lo, hi, 3),
                   _f(peak_gf(g), 3)))
    out += ["### D3 — Where opponents aim relative to head-on (`their_break_gf` bias)", "",
            md_table(["Opponent", "N", "Mean aim GF", "95% CI", "Peak bin"], d3),
            "Same `their_break_gf` distribution as D1, read as opponent aim bias rather than our "
            "dodge profile. A bias away from 0 = a predictable gun that movement can "
            "deterministically dodge.", ""]

    # D4 — incoming power x distance + zap
    d4 = []
    zap_keys = set()
    if not ds.scan.empty:
        zs = ds.scan[ds.scan["scan_their_inactivity_zap_active"] == 1]
        if not zs.empty:
            zap_keys = set(map(tuple, zs[["matchup", "perspective", "round", "tick"]].to_numpy()))
    for opp in opps:
        tw = ds.their_for_opponent(opp)
        if not zap_keys or not len(tw):
            zap_overlap = 0.0
        else:
            keys = tw[["matchup", "perspective", "round", "their_fire_tick"]].to_numpy()
            zap_overlap = float(np.mean([tuple(k) in zap_keys for k in keys]))
        d4.append((f"`{opp}`", f"{len(tw):,}", _f(tw["their_fire_power"].mean()),
                   _f(tw["their_fire_distance"].mean(), 0), _pct(zap_overlap, 2)))
    out += ["### D4 — Incoming bullet power × distance + zap overlap", "",
            md_table(["Opponent", "N", "Mean power", "Mean distance", "Fires on zap tick"], d4),
            "Near-zero zap overlap confirms incoming bullets are real threats, not zap artifacts.", ""]

    # D5 — conditional hit-us by own state (join their-waves with ticks state at fire tick)
    out += ["### D5 — Conditional hit-us by own state at incoming fire time", ""]
    tw = ds.their[~ds.their["self_play"]].dropna(subset=["their_hit_us"]).copy()
    dist = tw["their_fire_distance"].to_numpy(dtype=float)
    near = tw["their_hit_us"][dist < 300]
    far = tw["their_hit_us"][dist >= 300]
    # wall proximity from their_fire_our_x/y
    ox, oy = tw["their_fire_our_x"], tw["their_fire_our_y"]
    wall_gap = np.minimum.reduce([ox, FIELD_W - ox, oy, FIELD_H - oy]) - ROBOT_HALF
    near_wall = tw["their_hit_us"][wall_gap < 50]
    open_field = tw["their_hit_us"][wall_gap >= 50]
    out += [md_table(
        ["Condition", "N", "Hit-us"],
        [("close (<300 px)", f"{len(near):,}", _pct(near.mean(), 2)),
         ("far (≥300 px)", f"{len(far):,}", _pct(far.mean(), 2)),
         ("near wall (<50 px)", f"{len(near_wall):,}", _pct(near_wall.mean(), 2)),
         ("open field (≥50 px)", f"{len(open_field):,}", _pct(open_field.mean(), 2))]),
        "Identifies dangerous states for movement constraints/penalties.", ""]

    # D6 — dodge time budget (incoming flight time)
    out += ["### D6 — Dodge time budget (incoming flight time, ticks)", ""]
    ft = (ds.their[~ds.their["self_play"]]["their_break_tick"]
          - ds.their[~ds.their["self_play"]]["their_fire_tick"]).astype(float)
    out += [md_table(["N", "p10", "p50", "p90"],
                     [(f"{ft.notna().sum():,}", *_pcts(ft))]),
            "Ticks the surfer has to react after detecting a wave.", ""]
    return "\n".join(out)


# --------------------------------------------------------------------------------------
# Section E — Feature analysis for ML
# --------------------------------------------------------------------------------------

def section_e(ds: Dataset, max_model_rows: int, figs: Figures) -> str:
    out = [_h("E"), ""]

    # E1 — feature catalog
    out += ["### E1 — Candidate feature inventory", ""]
    cat_rows = []
    for fname, df in (("dejavu-waves.csv", ds.dejavu), ("their-waves.csv", ds.their),
                      ("scan.csv", ds.scan)):
        if df.empty:
            continue
        for c in df.columns:
            if c in TAG_COLS or c in ("battle_id", "round", "tick"):
                continue
            s = df[c]
            rng_s = f"[{_f(s.min(), 1)}, {_f(s.max(), 1)}]" if pd.api.types.is_numeric_dtype(s) else "—"
            cat_rows.append((f"`{c}`", f"`{fname}`", str(s.dtype),
                             rng_s, _pct(s.isna().mean(), 1), FEATURE_UNITS.get(c, "—")))
    out += [md_table(["Feature", "File", "dtype", "Range", "%NaN", "Units"], cat_rows), ""]

    if ds.dejavu.empty:
        out += ["### E2–E5", "", "_dejavu-waves.csv unavailable — model-based feature analysis skipped._", ""]
        return "\n".join(out)

    rng = np.random.default_rng(SEED)
    d = ds.dejavu[~ds.dejavu["self_play"]].dropna(subset=["our_break_gf"] + TARGET_FEATURES)
    if len(d) > max_model_rows:
        d = d.iloc[rng.choice(len(d), max_model_rows, replace=False)]
    X = d[TARGET_FEATURES].to_numpy(dtype=float)
    y = d["our_break_gf"].to_numpy(dtype=float)

    # E2 — redundancy (|r| > 0.8)
    corr = np.corrcoef(X, rowvar=False)
    pairs = []
    for i in range(len(TARGET_FEATURES)):
        for j in range(i + 1, len(TARGET_FEATURES)):
            if abs(corr[i, j]) > 0.8:
                pairs.append((f"`{TARGET_FEATURES[i]}` ↔ `{TARGET_FEATURES[j]}`", _f(corr[i, j], 3)))
    out += ["### E2 — Redundant feature pairs (|Pearson r| > 0.8)", "",
            md_table(["Feature pair", "r"], pairs) if pairs else "No pair exceeds |r| > 0.8.",
            ""]
    fig, ax = _ax(5.6, 5.0)
    im = ax.imshow(corr, cmap="coolwarm", vmin=-1, vmax=1)
    short = [f.replace("our_fire_", "") for f in TARGET_FEATURES]
    ax.set_xticks(range(len(short)))
    ax.set_xticklabels(short, rotation=45, ha="right", fontsize=7)
    ax.set_yticks(range(len(short)))
    ax.set_yticklabels(short, fontsize=7)
    for i in range(len(short)):
        for j in range(len(short)):
            ax.text(j, i, f"{corr[i, j]:.2f}", ha="center", va="center", fontsize=6,
                    color="black")
    ax.set_title("E2 — Fire-time feature correlation matrix")
    fig.colorbar(im, ax=ax, fraction=0.046, pad=0.04)
    fig.tight_layout()
    out += [figs.save(fig, "E2-correlation-matrix.png", "E2 correlation matrix"), ""]

    # E3 — GF-prediction importance (impurity + permutation)
    from sklearn.ensemble import RandomForestRegressor
    from sklearn.inspection import permutation_importance
    reg = RandomForestRegressor(n_estimators=80, max_depth=10, random_state=SEED, n_jobs=-1).fit(X, y)
    perm = permutation_importance(reg, X, y, n_repeats=5, random_state=SEED, n_jobs=-1)
    e3 = sorted(
        [(f"`{f}`", _f(reg.feature_importances_[i], 4), _f(perm.importances_mean[i], 4))
         for i, f in enumerate(TARGET_FEATURES)],
        key=lambda t: -float(t[2]))
    out += ["### E3 — Feature importance for GF prediction (RandomForest)", "",
            md_table(["Feature", "Impurity importance", "Permutation importance"], e3),
            f"Fit on {len(d):,} dejavu real waves (capped at --max-model-rows={max_model_rows:,}).", ""]

    # E4 — hit/no-hit importance (PR-AUC permutation)
    from sklearn.ensemble import RandomForestClassifier
    from sklearn.metrics import average_precision_score
    hit = canonical_hit(d["our_fire_aim_gf"].to_numpy(dtype=float), y,
                        d["our_fire_distance"].to_numpy(dtype=float),
                        d["our_fire_mea"].to_numpy(dtype=float),
                        d["our_fire_bearing_absolute"].to_numpy(dtype=float)).astype(int)
    base = hit.mean()
    out += ["### E4 — Feature importance for hit / no-hit (PR-AUC-scored)", ""]
    if hit.sum() < 10 or hit.sum() == len(hit):
        out += [f"_Too few positives ({int(hit.sum())}/{len(hit)}) for a stable classifier._", ""]
    else:
        clf = RandomForestClassifier(n_estimators=80, max_depth=10, random_state=SEED,
                                     class_weight="balanced", n_jobs=-1).fit(X, hit)
        permc = permutation_importance(clf, X, hit, n_repeats=5, random_state=SEED,
                                       scoring="average_precision", n_jobs=-1)
        ap = average_precision_score(hit, clf.predict_proba(X)[:, 1])
        e4 = sorted([(f"`{f}`", _f(permc.importances_mean[i], 4))
                     for i, f in enumerate(TARGET_FEATURES)], key=lambda t: -float(t[1]))
        out += [md_table(["Feature", "Permutation importance (PR-AUC)"], e4),
                f"Positive base rate {_pct(base, 2)} ({hit.sum():,}/{len(hit):,}); "
                f"in-sample PR-AUC {_f(ap, 3)}. At ~{_f((1-base)/base,0)}:1 imbalance, accuracy "
                "and impurity importance mislead, so PR-AUC is the fixed metric.", ""]

    # E5 — recommended v1 feature set
    redundant = {TARGET_FEATURES[j] for i in range(len(TARGET_FEATURES))
                 for j in range(i + 1, len(TARGET_FEATURES)) if abs(corr[i, j]) > 0.8}
    ranked = [f.strip("`") for f, *_ in e3]
    recommended = [f for f in ranked if f not in redundant][:5]
    out += ["### E5 — Recommended v1 feature set", "",
            "Top permutation-ranked GF features with one of each redundant pair dropped:",
            "", ", ".join(f"`{f}`" for f in recommended) or "_(none)_",
            "", f"Dropped as redundant: {', '.join(f'`{f}`' for f in sorted(redundant)) or 'none'}.", ""]
    return "\n".join(out)


# --------------------------------------------------------------------------------------
# Section F — Learning dynamics
# --------------------------------------------------------------------------------------

def section_f(ds: Dataset, figs: Figures) -> str:
    out = [_h("F"), ""]
    if ds.dejavu.empty:
        return "\n".join(out + ["_dejavu-waves.csv unavailable — Section F cannot be computed._", ""])
    opps = ds.opponents()

    # F1 — accumulation by round (per-matchup; the cold-start budget is per battle, not pooled)
    d = ds.dejavu[~ds.dejavu["self_play"]]
    per = d.groupby(["matchup", "round"], observed=True).size().unstack("round", fill_value=0)
    per = per.reindex(sorted(per.columns), axis=1)
    cum_per = per.cumsum(axis=1)
    rounds = [int(r) for r in per.columns]
    f1 = []
    for r in per.columns:
        wr, cm = per[r], cum_per[r]
        f1.append((str(int(r)), f"{wr.mean():.0f}", f"{wr.median():.0f}",
                   f"{cm.mean():.0f}", f"{cm.median():.0f}"))
    fig, ax = _ax()
    ax.plot(rounds, [cum_per[r].mean() for r in per.columns], marker="o",
            color="#4477aa", linewidth=1.4, label="mean")
    ax.plot(rounds, [cum_per[r].median() for r in per.columns], marker="s",
            color="#228833", linewidth=1.4, label="median")
    ax.set_title("F1 — Per-matchup cumulative real waves")
    ax.set_xlabel("round")
    ax.set_ylabel("cumulative waves / matchup")
    ax.grid(True, alpha=0.25)
    ax.legend(fontsize=8)
    fig.tight_layout()
    out += ["### F1 — Real-wave accumulation by round (per-matchup cold-start budget)", "",
            md_table(["Round", "Waves/round (mean)", "Waves/round (median)",
                      "Cumulative (mean)", "Cumulative (median)"], f1),
            f"Per-matchup statistics across {per.shape[0]} matchups (**not** the pooled "
            "cross-matchup total); the cold-start budget is what a single battle accumulates.",
            "",
            figs.save(fig, "F1-cold-start-curve.png", "F1 per-matchup cold-start curve"), ""]

    # F2 — KL round 0 -> last per opponent
    last_round = int(d["round"].max())
    f2 = []
    for opp in opps:
        do = ds.dejavu_for_opponent(opp)
        g0 = do[do["round"] == 0]["our_break_gf"].dropna().to_numpy(dtype=float)
        gl = do[do["round"] == last_round]["our_break_gf"].dropna().to_numpy(dtype=float)
        if len(g0) < 20 or len(gl) < 20:
            f2.append((f"`{opp}`", f"{len(g0)}/{len(gl)}", "—", "—"))
            continue
        kl, lo, hi = kl_adapt_ci(g0, gl)
        flag = _flag(min(len(g0), len(gl)))
        f2.append((f"`{opp}`", f"{len(g0)}/{len(gl)}{flag}", _f(kl, 3), _ci_val(lo, hi, 3)))
    out += [f"### F2 — Non-stationarity: KL(round 0 ‖ round {last_round}) per opponent (bits)", "",
            md_table(["Opponent", "N(r0/last)", "KL (bits)", "95% CI"], f2),
            "Bias-corrected KL (the finite-sample plug-in KL is positively biased and is "
            "corrected toward 0). High KL ⇒ the opponent adapts across rounds and recency-"
            "weighting matters; ≈0 ⇒ stationary within sampling noise.", ""]

    # F3 — VCS grid occupancy (distance 5 x lateralV 5 x advancingV 3)
    out += ["### F3 — Segmented VCS grid occupancy (distance 5 × lateralV 5 × advancingV 3 = 75 cells)", ""]
    f3 = []
    for opp in opps:
        do = ds.dejavu_for_opponent(opp).dropna(subset=["our_break_gf"])
        n = len(do)
        if n == 0:
            continue
        try:
            db = pd.qcut(do["our_fire_distance"], 5, duplicates="drop").cat.codes
            lb = pd.qcut(do["our_fire_lateral_velocity"], 5, duplicates="drop").cat.codes
            ab = pd.qcut(do["our_fire_advancing_velocity"], 3, duplicates="drop").cat.codes
            occupied = len(set(zip(db, lb, ab)))
        except (ValueError, IndexError):
            occupied = float("nan")
        f3.append((f"`{opp}`", f"{n:,}{_flag(n)}", f"{occupied}/75",
                   _f(n / 75, 1)))
    out += [md_table(["Opponent", "N", "Occupied cells", "Mean rows/cell"], f3),
            "Sparse grids (few rows/cell) argue for KNN over a fixed grid.", ""]

    # F4 — stability vs N (bootstrap variance of peak bin)
    out += ["### F4 — GF stability vs sample size (bootstrap std of peak-bin GF)", ""]
    pooled = d["our_break_gf"].dropna().to_numpy(dtype=float)
    f4 = []
    f4_pts = []
    rng = np.random.default_rng(SEED)
    for nn in (50, 100, 200, 500, 1000):
        if len(pooled) < nn:
            f4.append((str(nn), "—"))
            continue
        peaks = [peak_gf(pooled[rng.integers(0, len(pooled), nn)]) for _ in range(200)]
        sd = float(np.std(peaks))
        f4.append((str(nn), _f(sd, 3)))
        f4_pts.append((nn, sd))
    fig, ax = _ax()
    if f4_pts:
        ax.plot([n for n, _ in f4_pts], [s for _, s in f4_pts], marker="o",
                color="#aa3377", linewidth=1.4)
    ax.set_title("F4 — Peak-GF stability vs sample size")
    ax.set_xlabel("sample size N")
    ax.set_ylabel("bootstrap std of peak-bin GF")
    ax.grid(True, alpha=0.25)
    fig.tight_layout()
    out += [md_table(["Sample size N", "Std of peak-bin GF"], f4),
            "Smaller std ⇒ fewer samples needed for a trustworthy per-segment aim.",
            "",
            figs.save(fig, "F4-stability-vs-n.png", "F4 stability vs N curve"), ""]
    return "\n".join(out)


# --------------------------------------------------------------------------------------
# Section G — Baselines & benchmarks
# --------------------------------------------------------------------------------------

def compute_g1(ds: Dataset) -> list[dict]:
    """Structured G1 scoreboard rows shared by the markdown table and the JSON sidecar.

    Computing both outputs from this single source guarantees the sidecar's G1 numbers
    match the rendered table exactly."""
    rows: list[dict] = []
    for opp in ds.opponents():
        d = ds.dejavu_for_opponent(opp).dropna(subset=["our_break_gf"])
        n = len(d)
        hero_h, hero_m = hit_rate_analytic(d, d["our_fire_aim_gf"].to_numpy(dtype=float))
        head_h, head_m = hit_rate_analytic(d, 0.0)
        bg, br, _ = best_static_gf(d)
        qh, qm = hit_rate_analytic(d, quantize_gf(d["our_break_gf"].to_numpy(dtype=float)))
        hero_eng = float(d["our_break_hit"].mean())
        af = ds.autopilot_real[(ds.autopilot_real["opponent"] == opp)
                               & (~ds.autopilot_real["self_play"])] \
            if not ds.autopilot_real.empty else pd.DataFrame()
        if len(af):
            fl_h, fl_m = hit_rate_analytic(af, af["our_fire_aim_gf"].to_numpy(dtype=float))
            fl_eng = float(af["our_break_hit"].mean())
        else:
            fl_h, fl_m, fl_eng = 0, 0, float("nan")
        lo, hi = wilson_ci(hero_h, hero_m)
        hero_rate = hero_h / hero_m if hero_m else float("nan")
        head_rate = head_h / head_m if head_m else float("nan")
        for rate in (hero_h / hero_m if hero_m else 0, head_h / head_m if head_m else 0, br):
            require(0.0 <= rate <= 1.0, f"G1: hit rate out of [0,1] for {opp}: {rate}")
        rows.append({
            "opponent": opp, "n": n,
            "floor_analytic": fl_h / fl_m if fl_m else float("nan"),
            "floor_engine": fl_eng, "floor_n": fl_m,
            "hero_analytic": hero_rate, "hero_engine": hero_eng,
            "headon": head_rate, "best_static": br, "best_static_gf": bg,
            "quant_bound": qh / qm if qm else float("nan"),
            "hero_ci_lo": lo, "hero_ci_hi": hi,
        })
    return rows


def section_g(ds: Dataset, figs: Figures) -> str:
    out = [_h("G"), ""]
    if ds.dejavu.empty:
        return "\n".join(out + ["_dejavu-waves.csv unavailable — Section G cannot be computed._", ""])
    opps = ds.opponents()

    # G1 — scoreboard (rendered from the shared compute_g1 structured rows)
    g1 = []
    for r in compute_g1(ds):
        fl_m = r["floor_n"]
        floor_s = (f"{_pct(r['floor_analytic'], 2)} (eng {_pct(r['floor_engine'], 2)}; "
                   f"N={fl_m}{_flag(fl_m)})") if fl_m else "—"
        g1.append((
            f"`{r['opponent']}`", f"{r['n']:,}{_flag(r['n'])}", floor_s,
            f"{_pct(r['hero_analytic'], 2)} (eng {_pct(r['hero_engine'], 2)})",
            _pct(r["headon"], 2), f"{_pct(r['best_static'], 2)}",
            _pct(r["quant_bound"], 1), _ci_pct(r["hero_ci_lo"], r["hero_ci_hi"])))
    out += ["### G1 — Hit-rate scoreboard (canonical analytic; engine hit shown separately)", "",
            md_table(["Opponent", "N", "Floor (autopilot)", "Hero (in-game)", "Head-on",
                      "Best-static", "Quant. bound", "Hero 95% CI"], g1),
            "Floor is what ML must beat; head-on/best-static are dejavu-derived; the quantization "
            "bound is a sanity bound, not learnable. _Caveat: the floor's analytic hit recomputes "
            "from the shadow gun's `aim_gf`, but its engine-hit column reflects the **real** bullet "
            "(a different gun) — the two floor columns are not the same gun._", ""]

    # G2 — outcomes
    out += ["### G2 — Battle outcomes (robots facing this opponent)", ""]
    if ds.scores.empty:
        out += ["_scores.csv unavailable._", ""]
    else:
        g2 = []
        sc = ds.scores[~ds.scores["self_play"]]
        for opp in opps:
            so = sc[sc["opponent"] == opp]
            wins = int((so["result"] == 1).sum())
            losses = int((so["result"] == -1).sum())
            ties = int((so["result"] == 0).sum())
            g2.append((f"`{opp}`", f"{len(so)}", f"{wins}", f"{losses}", f"{ties}"))
        out += [md_table(
            ["Opponent", "Battles", "Faced-robot wins", "Losses", "Ties"], g2),
            "Win = the robot facing this opponent beat it; many losses ⇒ a strong opponent. "
            "Per-shot hit rates live in G1/H1 (computed canonically from waves); the producer's "
            "battle-end `round_hit_rate` field is not reliably populated, so it is omitted here.", ""]

    # G3 — virtual-gun hit-rate curves (sweep a fixed aim-GF; dejavu vs autopilot fan)
    out += ["### G3 — Virtual-gun hit-rate curves (fixed-aim sweep)", ""]
    dej_all = ds.dejavu[~ds.dejavu["self_play"]].dropna(subset=["our_break_gf"])
    aims, dej_rates = _gun_sweep(dej_all)
    bg, br, _ = best_static_gf(dej_all)
    fig, ax = _ax()
    ax.plot(aims, 100.0 * dej_rates, color="#4477aa", linewidth=1.4, label="dejavu (real)")
    ap_all = ds.autopilot_real[~ds.autopilot_real["self_play"]].dropna(subset=["our_break_gf"]) \
        if not ds.autopilot_real.empty else pd.DataFrame()
    if len(ap_all):
        _, ap_rates = _gun_sweep(ap_all)
        ax.plot(aims, 100.0 * ap_rates, color="#ee6677", linewidth=1.2, linestyle="--",
                label="autopilot fan (real)")
    ax.axvline(bg, color="#228833", linestyle=":", linewidth=1.0, label=f"best-static GF={bg:.2f}")
    ax.set_title("G3 — Hit rate vs fixed aim-GF")
    ax.set_xlabel("fixed aim GuessFactor")
    ax.set_ylabel("hit rate (%)")
    ax.set_xlim(-1, 1)
    ax.grid(True, alpha=0.25)
    ax.legend(fontsize=8)
    fig.tight_layout()
    out += [
        "Sweeping a single fixed aim-GF through the canonical hit rule: the peak marks the "
        f"best-static gun (GF={_f(bg, 3)}, {_pct(br, 2)}). The autopilot-fan overlay is the same "
        "sweep over the shadow gun's real rows (a different gun, shown for cross-check only).",
        "",
        figs.save(fig, "G3-virtual-gun-curves.png", "G3 virtual-gun curves"), ""]
    return "\n".join(out)


# --------------------------------------------------------------------------------------
# Section H — Per-opponent profiles
# --------------------------------------------------------------------------------------

def compute_h1(ds: Dataset) -> list[dict]:
    """Structured H1 fingerprint rows shared by the markdown table and JSON sidecar."""
    last_round = int(ds.dejavu["round"].max()) if not ds.dejavu.empty else 0
    rows: list[dict] = []
    for opp in ds.opponents():
        d = ds.dejavu_for_opponent(opp).dropna(subset=["our_break_gf"])
        g = d["our_break_gf"]
        n = len(d)
        p = gf_hist_laplace(g)
        hero_h, hero_m = hit_rate_analytic(d, d["our_fire_aim_gf"].to_numpy(dtype=float))
        tw = ds.their_for_opponent(opp)
        hu = float(tw["their_hit_us"].mean()) if len(tw) else float("nan")
        g0 = d[d["round"] == 0]["our_break_gf"].dropna().to_numpy(dtype=float)
        gl = d[d["round"] == last_round]["our_break_gf"].dropna().to_numpy(dtype=float)
        kl = (kl_adapt_ci(g0, gl)[0]
              if len(g0) >= 20 and len(gl) >= 20 else float("nan"))
        rows.append({
            "opponent": opp, "n": n,
            "mean_distance": float(d["our_fire_distance"].mean()),
            "gf_peak": peak_gf(g),
            "gf_entropy_bits": shannon_entropy_bits(p),
            "hero_hit": hero_h / hero_m if hero_m else float("nan"),
            "hit_us": hu, "kl_adapt": kl,
            "typ_power": float(d["our_fire_power"].mean()),
            "_gf_dist": p,
        })
    return rows


def section_h(ds: Dataset) -> str:
    out = [_h("H"), ""]
    if ds.dejavu.empty:
        return "\n".join(out + ["_dejavu-waves.csv unavailable — Section H cannot be computed._", ""])
    opps = ds.opponents()

    # H1 — fingerprints (rendered from the shared compute_h1 structured rows)
    h1_rows = compute_h1(ds)
    gf_dists = {r["opponent"]: r["_gf_dist"] for r in h1_rows}
    h1 = [(f"`{r['opponent']}`", f"{r['n']:,}{_flag(r['n'])}", _f(r["mean_distance"], 0),
           _f(r["gf_peak"], 3), _f(r["gf_entropy_bits"], 2),
           _pct(r["hero_hit"], 2), _pct(r["hit_us"], 2),
           _f(r["kl_adapt"], 3), _f(r["typ_power"], 2)) for r in h1_rows]
    out += ["### H1 — Per-opponent fingerprint", "",
            md_table(["Opponent", "N", "Mean dist", "GF peak", "GF entropy", "Our hit",
                      "Hit-us", "KL(adapt)", "Typ. power"], h1), ""]

    # H2 — similarity by GF-distribution distance (Jensen-Shannon)
    out += ["### H2 — Opponent similarity (Jensen-Shannon distance of GF distributions)", ""]
    valid = [o for o in opps if o in gf_dists]
    if len(valid) >= 2:
        def js(p, q):
            m = 0.5 * (p + q)
            return float(0.5 * kl_bits(p, m) + 0.5 * kl_bits(q, m))
        D = np.zeros((len(valid), len(valid)))
        for i in range(len(valid)):
            for j in range(len(valid)):
                D[i, j] = js(gf_dists[valid[i]], gf_dists[valid[j]])
        from sklearn.cluster import AgglomerativeClustering
        k = min(3, len(valid))
        labels = AgglomerativeClustering(n_clusters=k, metric="precomputed",
                                         linkage="average").fit_predict(D)
        hdr = ["Opponent"] + [f"`{o.split('.')[-1]}`" for o in valid] + ["Cluster"]
        rows = []
        for i, o in enumerate(valid):
            rows.append([f"`{o}`"] + [_f(D[i, j], 3) for j in range(len(valid))] + [str(int(labels[i]))])
        out += [md_table(hdr, rows),
                "Opponents in one cluster may share a model / warm-start prior.", ""]
    else:
        out += ["_Need ≥2 opponents._", ""]
    return "\n".join(out)


# --------------------------------------------------------------------------------------
# Section I — ML readiness & recommendations
# --------------------------------------------------------------------------------------

def compute_i1(ds: Dataset) -> list[dict]:
    """Structured per-task readiness flags for the JSON sidecar (CI regression-diffing)."""
    n_dej = len(ds.dejavu) if not ds.dejavu.empty else 0
    n_their = len(ds.their) if not ds.their.empty else 0
    return [
        {"task": "gf_regression", "label": "our_break_gf",
         "label_budget_waves": n_dej, "ready": bool(n_dej >= 1000)},
        {"task": "hit_no_hit", "label": "canonical_analytic_hit",
         "label_budget_waves": n_dej, "ready": bool(n_dej >= 1000)},
        {"task": "movement_surf", "label": "their_break_gf/their_hit_us",
         "label_budget_waves": n_their, "ready": bool(n_their >= 1000)},
    ]


def section_i(ds: Dataset) -> str:
    out = [_h("I"), ""]
    n_dej = len(ds.dejavu) if not ds.dejavu.empty else 0
    n_their = len(ds.their) if not ds.their.empty else 0
    ft = (ds.dejavu["our_break_tick"] - ds.dejavu["our_fire_tick"]).astype(float) if not ds.dejavu.empty else pd.Series(dtype=float)

    # I1 — readiness summary
    out += ["### I1 — Per-task readiness summary", "",
            md_table(
                ["Task", "Label", "Label budget", "Noise / base rate", "Per-wave latency", "Stationarity"],
                [("GF regression (targeting)", "`our_break_gf` (dejavu real)", f"{n_dej:,} waves",
                  "continuous, low NaN",
                  f"variable {_f(ft.mean(),0)} ticks mean" if len(ft) else "—",
                  "moderate (see F2 KL)"),
                 ("Hit / no-hit (fire policy)", "canonical analytic hit", f"{n_dej:,} waves",
                  "~8–9% positive (imbalanced)", "same as targeting", "moderate"),
                 ("Movement (surf)", "`their_break_gf` / `their_hit_us`", f"{n_their:,} waves",
                  "~30% hit-us base", "incoming flight time (D6)", "per-opponent gun-dependent")]),
            ""]

    # I2 — target formulation
    out += ["### I2 — Recommended target formulation", "",
            "Per C1–C5: GF distributions are peaked enough to be learnable for several opponents but "
            "best-static ≈ CV-model for most, so **segmentation (VCS-style) is the primary lever**. "
            "Recommend **GF regression** (or fine-bin classification) over raw hit/no-hit, with a "
            "separate hit/no-hit head only for fire-power/selection policy.", ""]

    # I3 — split recipe
    out += ["### I3 — Leakage-safe train/test split", "",
            "- Use **dejavu real waves** as labels (never the autopilot virtual fan).",
            "- If autopilot virtual rows are ever used, **group by** `(matchup, round, our_fire_tick)`.",
            "- Split by **battle/round**, not by row, so a round's waves never straddle the split.",
            "- Isolate **self-play** matchups (excluded here from all aggregates).", ""]

    # I4 — ranked traps
    out += ["### I4 — Ranked risks / traps", "",
            "1. **Autopilot is a counterfactual**, not the game — use dejavu for characterization.",
            "2. **Virtual-wave leakage** (autopilot only) — group by parent fire.",
            "3. **Variable per-wave label latency** (mean ≈28 ticks; scales with distance/power — B4).",
            "4. **Self-play double-counting** — excluded from aggregates.",
            "5. **Per-round adaptation** — opponents with non-zero F2 KL shift GF across "
            "rounds; recency-weight those (most are stationary after bias correction).",
            "6. **Round-end censoring** — unresolved waves (A4) are not missing at random.",
            "7. **Floor selection bias** — the floor↔hero gap is unpaired (C4).",
            "8. **Missing scans** — stale features when coverage drops (B6).", ""]
    return "\n".join(out)


def build_sections(ds: Dataset, max_model_rows: int, figs: Figures) -> str:
    return "\n".join([
        section_a(ds), "",
        section_b(ds, figs), "",
        section_c(ds, max_model_rows, figs), "",
        section_d(ds, figs), "",
        section_e(ds, max_model_rows, figs), "",
        section_f(ds, figs), "",
        section_g(ds, figs), "",
        section_h(ds), "",
        section_i(ds), "",
    ])


# --------------------------------------------------------------------------------------
# JSON sidecar (Phase 3) — machine-readable G1 / H1 / I1 for CI regression-diffing
# --------------------------------------------------------------------------------------

def _json_safe(v):
    """Convert numpy scalars to plain Python, NaN/inf to None, and round floats so the
    sidecar diffs cleanly across runs."""
    if isinstance(v, np.floating):
        v = float(v)
    if isinstance(v, np.integer):
        v = int(v)
    if isinstance(v, np.bool_):
        v = bool(v)
    if isinstance(v, float):
        return None if not np.isfinite(v) else round(v, 6)
    return v


def build_sidecar(ds: Dataset, run_dir: Path) -> dict:
    def clean(rows, drop=()):
        return [{k: _json_safe(val) for k, val in r.items() if k not in drop} for r in rows]

    return {
        "schema": "intuition-sidecar/1",
        "run_dir": _rel(run_dir),
        "seed": SEED,
        "opponents": list(ds.opponents()),
        "g1_scoreboard": clean(compute_g1(ds)),
        "h1_fingerprints": clean(compute_h1(ds), drop=("_gf_dist",)),
        "i1_readiness": clean(compute_i1(ds)),
    }


def write_sidecar(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


# --------------------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------------------

def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate the ML Intuition report from a battle-csv-producer run dir.",
    )
    parser.add_argument(
        "run_dir", nargs="?", default=None,
        help="producer run directory (default: newest under "
             "pipeline/build/battle-csv-producer/)",
    )
    parser.add_argument("--out", default="wiki/ML-intuition.md", help="report output path")
    parser.add_argument("--assets", default="wiki/ml-intuition", help="PNG/JSON asset dir")
    parser.add_argument(
        "--max-model-rows", type=int, default=50_000,
        help="cap rows fed to the C6/E3/E4 model fit + permutation importance "
             "(descriptive tables and figures are never subsampled)",
    )
    return parser.parse_args(argv)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)

    run_dir = find_run_dir(args.run_dir)
    perspectives = walk_perspectives(run_dir)
    perspectives = filter_excluded_robots(perspectives)

    out_path = (REPO_ROOT / args.out) if not Path(args.out).is_absolute() else Path(args.out)
    assets_dir = (REPO_ROOT / args.assets) if not Path(args.assets).is_absolute() else Path(args.assets)

    header = build_header(run_dir, perspectives, assets_dir)
    dataset = load_dataset(run_dir, perspectives)
    figs = Figures(assets_dir=assets_dir, report_dir=out_path.parent)
    body = build_sections(dataset, args.max_model_rows, figs)
    write_report(out_path, header, body)
    write_sidecar(assets_dir / "intuition.json", build_sidecar(dataset, run_dir))

    print(f"intuition.py: wrote {_rel(out_path)}")
    print(
        f"intuition.py: {len(figs.written)} figures + intuition.json under "
        f"{_rel(assets_dir)}"
    )
    print(
        f"intuition.py: {len(perspectives)} perspectives across "
        f"{len({p.matchup for p in perspectives})} matchups; "
        f"contract = {resolve_contract(perspectives)}"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except IntuitionError as exc:
        fail(str(exc))

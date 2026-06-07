#!/usr/bin/env python3
"""Granular GuessFactor histogram analysis — discreteness-artifact hypothesis test.

Hypothesis under test
---------------------
A very fine GuessFactor (GF) histogram (default 2000 bins over [-1, 1]) should expose
*comb artifacts* — narrow, sharply repeating spikes — caused by the Robocode engine's
discrete state: integer velocities/accelerations (-8..8, changing by +-1 / +-2 per tick)
and discrete bullet speeds (speed = 20 - 3*power, so MEA = asin(8/speed) is quantized by
the power the robot chose). Because GF = bearing_offset / MEA is built from these discrete
ingredients accumulated over an integer number of flight ticks, the realized GF values
should pile up at a repeating set of rational positions rather than forming a smooth curve.

We test this separately for:
  * the **gun** (offensive targeting) — `our_break_gf` from ``dejavu-waves.csv`` (real
    waves only): where the opponent ended up on *our* waves.
  * **movement** (defensive surfing) — `their_break_gf` from ``their-waves.csv``: where
    *we* ended up on *their* waves.

The script pools every perspective in a ``battle-csv-producer`` run directory, renders the
fine and a reference-coarse histogram, computes spikiness metrics that quantify the comb,
and writes ``wiki/detailed-gf.md`` plus PNGs under ``wiki/detailed-gf/``.

Usage:
  detailed_gf.py [RUN_DIR] [--bins 2000] [--out wiki/detailed-gf.md]
                 [--assets wiki/detailed-gf]
"""
from __future__ import annotations

import argparse
import datetime as _dt
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Optional, Sequence

import matplotlib

matplotlib.use("Agg")  # deterministic, non-interactive raster backend
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parent.parent
PRODUCER_ROOT = REPO_ROOT / "pipeline" / "build" / "battle-csv-producer"

#: Reference coarse grid (matches intuition.py's canonical 47-bin GF grid).
COARSE_BINS = 47

#: Robots whose perspective is too naive to be informative (skipped per-robot).
EXCLUDED_ROBOTS = {"cz.zamboch.Autopilot"}

#: Fixed, deterministic per-robot colour palette (Tableau-10 order).
ROBOT_COLORS = (
    "#4477aa", "#ee6677", "#228833", "#ccbb44",
    "#66ccee", "#aa3377", "#bbbbbb", "#ee8866",
)

#: PNG metadata stripped so two runs produce byte-identical assets.
PNG_METADATA = {"Software": None, "Creation Time": None}


# --------------------------------------------------------------------------------------
# Data discovery & loading
# --------------------------------------------------------------------------------------

def find_run_dir(override: Optional[str]) -> Path:
    if override:
        p = Path(override)
        if not p.is_absolute():
            p = (REPO_ROOT / override).resolve()
        if not p.is_dir():
            raise SystemExit(f"detailed_gf.py: run dir not found: {p}")
        return p
    if not PRODUCER_ROOT.is_dir():
        raise SystemExit(f"detailed_gf.py: no producer output under {PRODUCER_ROOT}")
    candidates = [d for d in PRODUCER_ROOT.iterdir() if d.is_dir()]
    if not candidates:
        raise SystemExit(f"detailed_gf.py: no run directories under {PRODUCER_ROOT}")
    return max(candidates, key=lambda d: d.stat().st_mtime)


def _is_self_play(matchup_dir: Path) -> bool:
    name = matchup_dir.name
    if "__vs__" not in name:
        return False
    a, b = name.split("__vs__", 1)
    return a == b


def _persp_robot(persp_dir: Path) -> str:
    """Robot whose perspective a ``PerspectiveN-<robot>`` directory captures."""
    name = persp_dir.name
    return name.split("-", 1)[1] if "-" in name else name


def _short(robot: str) -> str:
    """Human-friendly robot label: last dotted segment (``kc.mega.BeepBoop`` -> ``BeepBoop``)."""
    return robot.rsplit(".", 1)[-1]


def _collect_gf_by_robot(run_dir: Path, filename: str, gf_col: str,
                         real_only: bool) -> dict[str, np.ndarray]:
    """Pool one GF column per perspective robot across every (non-self-play) matchup.

    Self-play matchups and :data:`EXCLUDED_ROBOTS` are dropped. The result maps each
    robot's full package name to its pooled GF samples (the channel characterises that
    robot: ``our_break_gf`` = its gun, ``their_break_gf`` = its movement).
    """
    per_robot: dict[str, list[np.ndarray]] = {}
    for matchup_dir in sorted(p for p in run_dir.iterdir() if p.is_dir()):
        if _is_self_play(matchup_dir):
            continue  # self-play is excluded from the pooled distribution
        for persp_dir in sorted(p for p in matchup_dir.iterdir() if p.is_dir()):
            robot = _persp_robot(persp_dir)
            if robot in EXCLUDED_ROBOTS:
                continue
            f = persp_dir / filename
            if not f.is_file():
                continue
            cols = [gf_col] + (["our_fire_is_real"] if real_only else [])
            try:
                df = pd.read_csv(f, usecols=lambda c, _w=set(cols): c in _w)
            except (ValueError, pd.errors.EmptyDataError):
                continue
            if gf_col not in df.columns:
                continue
            if real_only and "our_fire_is_real" in df.columns:
                df = df[df["our_fire_is_real"] == 1]
            g = pd.to_numeric(df[gf_col], errors="coerce").to_numpy(dtype=np.float64)
            g = g[np.isfinite(g)]
            if g.size:
                per_robot.setdefault(robot, []).append(g)
    return {robot: np.concatenate(chunks)
            for robot, chunks in sorted(per_robot.items())}


def _pool(by_robot: dict[str, np.ndarray]) -> np.ndarray:
    """Concatenate all robots' samples into one pooled array."""
    if not by_robot:
        return np.empty(0, dtype=np.float64)
    return np.concatenate(list(by_robot.values()))


# --------------------------------------------------------------------------------------
# Spikiness / comb metrics
# --------------------------------------------------------------------------------------

@dataclass
class CombMetrics:
    n: int
    bins: int
    bin_width: float
    occupied_bins: int
    max_count: int
    max_gf: float
    max_mass_pct: float
    spikiness: float            # max bin count / median of occupied bins
    comb_spikes: int            # # local-max bins exceeding 5x their local-window median
    comb_mass_pct: float        # fraction of all mass living in those comb spikes
    interior_spikes: int        # comb spikes EXCLUDING the GF=0 / +-1 fixed points
    interior_mass_pct: float    # mass in interior (non-fixed-point) comb spikes
    mass_at_zero_pct: float     # mass within +-1 fine bin of GF=0
    mass_extreme_pct: float     # mass with |GF| > 0.98
    zero_spikiness: float       # GF=0 bin count / median occupied bin
    top_spikes: list            # [(gf_center, count, mass_pct), ...]


def _local_median(counts: np.ndarray, half: int) -> np.ndarray:
    """Median of a sliding window (length 2*half+1) over the histogram counts.

    Used as a smooth local baseline: a spike is a bin that towers over its neighbours.
    """
    n = counts.size
    out = np.empty(n, dtype=np.float64)
    for i in range(n):
        lo = max(0, i - half)
        hi = min(n, i + half + 1)
        out[i] = np.median(counts[lo:hi])
    return out


def comb_metrics(gf: np.ndarray, bins: int) -> CombMetrics:
    edges = np.linspace(-1.0, 1.0, bins + 1)
    counts, _ = np.histogram(gf, bins=edges)
    centers = 0.5 * (edges[:-1] + edges[1:])
    n = int(counts.sum())
    width = float(edges[1] - edges[0])
    occupied = counts[counts > 0]

    max_idx = int(np.argmax(counts))
    max_count = int(counts[max_idx])

    # Local baseline over a window ~ the coarse-bin width, so a "spike" means narrower
    # than the smooth shape would allow.
    half = max(3, bins // (COARSE_BINS * 2))
    base = _local_median(counts.astype(np.float64), half)
    base_safe = np.where(base <= 0, 1.0, base)
    is_local_max = np.r_[True, counts[1:] >= counts[:-1]] & np.r_[counts[:-1] >= counts[1:], True]
    spike_mask = is_local_max & (counts >= 5.0 * base_safe) & (counts >= 5)
    comb_spikes = int(spike_mask.sum())
    comb_mass = int(counts[spike_mask].sum())

    near_zero = np.abs(centers) <= width  # +-1 fine bin of exact 0
    extreme = np.abs(centers) > 0.98

    # Fixed points of GF = offset / MEA: GF=0 (zero net lateral offset) and GF=+-1 (MEA
    # saturation) survive any continuous variation in MEA. Spikes there are expected and
    # are NOT the "intermediate comb" the hypothesis predicts, so separate them out.
    fixed_point = (np.abs(centers) <= 0.02) | (np.abs(centers) >= 0.98)
    interior_mask = spike_mask & ~fixed_point
    interior_spikes = int(interior_mask.sum())
    interior_mass = int(counts[interior_mask].sum())

    median_occ = float(np.median(occupied)) if occupied.size else 1.0
    # The head-on spike can land in either fine bin straddling 0; take the tallest within
    # +-1 fine bin of exact zero rather than a single arbitrary side.
    zero_count = int(counts[near_zero].max()) if near_zero.any() else 0

    order = np.argsort(counts)[::-1][:10]
    top = [(round(float(centers[i]), 4), int(counts[i]),
            round(100.0 * counts[i] / n, 3)) for i in order if counts[i] > 0]

    return CombMetrics(
        n=n,
        bins=bins,
        bin_width=width,
        occupied_bins=int((counts > 0).sum()),
        max_count=max_count,
        max_gf=round(float(centers[max_idx]), 4),
        max_mass_pct=round(100.0 * max_count / n, 3),
        spikiness=round(max_count / median_occ, 2),
        comb_spikes=comb_spikes,
        comb_mass_pct=round(100.0 * comb_mass / n, 3),
        interior_spikes=interior_spikes,
        interior_mass_pct=round(100.0 * interior_mass / n, 3),
        mass_at_zero_pct=round(100.0 * int(counts[near_zero].sum()) / n, 3),
        mass_extreme_pct=round(100.0 * int(counts[extreme].sum()) / n, 3),
        zero_spikiness=round(zero_count / median_occ, 2),
        top_spikes=top,
    )


# --------------------------------------------------------------------------------------
# Figures
# --------------------------------------------------------------------------------------

def _save(fig, assets_dir: Path, name: str) -> str:
    assets_dir.mkdir(parents=True, exist_ok=True)
    fig.savefig(assets_dir / name, dpi=110, bbox_inches="tight", metadata=PNG_METADATA)
    plt.close(fig)
    return name


def plot_channel(by_robot: dict[str, np.ndarray], bins: int, title: str,
                 assets_dir: Path, stem: str) -> str:
    """One fine-histogram line per robot (full range + a zoom on [-0.5, 0.5]).

    Densities are normalised per robot so robots with different wave counts are directly
    comparable; a comb artifact shows as repeating spikes at the same GF for every line.
    """
    fine_edges = np.linspace(-1.0, 1.0, bins + 1)
    centers = 0.5 * (fine_edges[:-1] + fine_edges[1:])
    zmask = np.abs(centers) <= 0.5

    fig, (ax_full, ax_zoom) = plt.subplots(2, 1, figsize=(11, 8))
    for i, (robot, gf) in enumerate(by_robot.items()):
        color = ROBOT_COLORS[i % len(ROBOT_COLORS)]
        dens, _ = np.histogram(gf, bins=fine_edges, density=True)
        ax_full.plot(centers, dens, color=color, linewidth=0.7,
                     label=f"{_short(robot)} (n={gf.size:,})")
        ax_zoom.plot(centers[zmask], dens[zmask], color=color, linewidth=0.8)

    ax_full.set_title(f"{title}  —  {bins} bins (full range, per robot)")
    ax_full.set_xlabel("GuessFactor")
    ax_full.set_ylabel("density / fine bin")
    ax_full.set_xlim(-1, 1)
    ax_full.grid(True, axis="y", alpha=0.2)
    ax_full.legend(fontsize=8, loc="upper right", ncol=2)

    ax_zoom.set_title(f"{title}  —  zoom [-0.5, 0.5]")
    ax_zoom.set_xlabel("GuessFactor")
    ax_zoom.set_ylabel("density / fine bin")
    ax_zoom.set_xlim(-0.5, 0.5)
    ax_zoom.grid(True, axis="y", alpha=0.2)

    fig.tight_layout()
    return _save(fig, assets_dir, f"{stem}-{bins}bins.png")


def plot_compare(gun: np.ndarray, mov: np.ndarray, bins: int,
                 assets_dir: Path) -> str:
    """Overlay the two normalized fine histograms so gun vs movement combs are comparable."""
    edges = np.linspace(-1.0, 1.0, bins + 1)
    centers = 0.5 * (edges[:-1] + edges[1:])
    gc, _ = np.histogram(gun, bins=edges, density=True)
    mc, _ = np.histogram(mov, bins=edges, density=True)
    fig, ax = plt.subplots(figsize=(11, 4))
    ax.plot(centers, gc, color="#4477aa", linewidth=0.7, label="gun (our_break_gf)")
    ax.plot(centers, mc, color="#ee6677", linewidth=0.7, alpha=0.8,
            label="movement (their_break_gf)")
    ax.set_title(f"Gun vs movement — normalized {bins}-bin GF density")
    ax.set_xlabel("GuessFactor")
    ax.set_ylabel("density")
    ax.set_xlim(-1, 1)
    ax.grid(True, alpha=0.2)
    ax.legend(fontsize=9)
    fig.tight_layout()
    return _save(fig, assets_dir, f"gun-vs-movement-{bins}bins.png")


# --------------------------------------------------------------------------------------
# Report
# --------------------------------------------------------------------------------------

def _rel(p: Path) -> str:
    try:
        return p.resolve().relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return p.as_posix()


def _metrics_table(m: CombMetrics) -> str:
    rows = [
        ("Resolved waves (N)", f"{m.n:,}"),
        ("Fine bins", f"{m.bins:,} (width {m.bin_width:.4f} GF)"),
        ("Occupied bins", f"{m.occupied_bins:,} / {m.bins:,} "
                          f"({100.0 * m.occupied_bins / m.bins:.1f}%)"),
        ("Tallest bin", f"{m.max_count:,} waves at GF={m.max_gf:+.4f} "
                        f"({m.max_mass_pct:.2f}% of mass)"),
        ("Spikiness (tallest / median occupied)", f"{m.spikiness:.1f}x"),
        ("GF=0 spike height (/ median occupied)", f"{m.zero_spikiness:.1f}x"),
        ("Comb spikes (>=5x local baseline)", f"{m.comb_spikes:,}"),
        ("Mass inside comb spikes", f"{m.comb_mass_pct:.2f}%"),
        ("Interior comb spikes (excl. GF=0, +-1)", f"{m.interior_spikes:,}"),
        ("Mass in interior comb spikes", f"{m.interior_mass_pct:.2f}%"),
        ("Mass at GF=0 (+-1 bin)", f"{m.mass_at_zero_pct:.2f}%"),
        ("Mass at extremes (|GF|>0.98)", f"{m.mass_extreme_pct:.2f}%"),
    ]
    out = ["| Metric | Value |", "|---|---|"]
    out += [f"| {k} | {v} |" for k, v in rows]
    return "\n".join(out)


def _top_spikes_table(m: CombMetrics) -> str:
    out = ["| Rank | GF center | Waves | % of mass |", "|---|---|---|---|"]
    for i, (gf, c, pct) in enumerate(m.top_spikes, 1):
        out.append(f"| {i} | {gf:+.4f} | {c:,} | {pct:.3f}% |")
    return "\n".join(out)


def _per_robot_table(by_robot: dict[str, np.ndarray], bins: int) -> str:
    """One row per robot with its key comb metrics (Autopilot already excluded)."""
    out = ["| Robot | N | Spikiness | GF=0 spike | Interior comb spikes | "
           "Interior mass | Mass @\\|GF\\|>0.98 |",
           "|---|---|---|---|---|---|---|"]
    for robot, gf in by_robot.items():
        if gf.size == 0:
            continue
        m = comb_metrics(gf, bins)
        out.append(
            f"| {_short(robot)} | {m.n:,} | {m.spikiness:.1f}x | {m.zero_spikiness:.1f}x | "
            f"{m.interior_spikes:,} | {m.interior_mass_pct:.2f}% | {m.mass_extreme_pct:.2f}% |")
    return "\n".join(out)


def _verdict(gun: CombMetrics, mov: CombMetrics) -> str:
    """Three-way classification of each channel from the metrics.

    * COMB        — the literal hypothesis: many narrow spikes across the *interior*
                    of the range (not just the fixed points).
    * FIXED-POINT — discreteness is visible, but only at GF=0 and/or GF=+-1; the
                    interior is smooth. (offset/MEA smears any interior comb.)
    * NONE        — no spike structure beyond sampling noise.
    """
    def classify(m: CombMetrics) -> str:
        if m.interior_spikes >= 10 and m.interior_mass_pct >= 5.0 and m.spikiness >= 8.0:
            return "comb"
        if m.zero_spikiness >= 8.0 or m.mass_extreme_pct >= 1.5:
            return "fixed-point"
        return "none"

    label = {
        "comb": "COMB (intermediate repeating spikes)",
        "fixed-point": "FIXED-POINT only (GF=0 / +-1)",
        "none": "NONE (sampling noise only)",
    }
    g, mv = classify(gun), classify(mov)
    lines = [f"- **Gun (`our_break_gf`):** {label[g]}",
             f"- **Movement (`their_break_gf`):** {label[mv]}", ""]

    if g == "comb" or mv == "comb":
        lines.append("**Verdict: SUPPORTED.** At least one channel shows a genuine "
                     "intermediate comb of narrow spikes that the 47-bin smooth reference "
                     "hides.")
    elif g == "fixed-point" or mv == "fixed-point":
        lines.append(
            "**Verdict: PARTIALLY SUPPORTED — discreteness is real, but it is not an "
            "intermediate comb.** The only artifacts are sharp spikes at the *fixed points* "
            "GF=0 (head-on: zero net lateral offset) and GF=+-1 (MEA saturation / max escape "
            "angle). The interior of the distribution is smooth noise, **not** a repeating "
            "comb.")
        lines.append("")
        lines.append(
            "Mechanism: `GF = bearing_offset / MEA`. Even though `bearing_offset` is built "
            "from discrete integer velocities/accelerations, `MEA = asin(8 / bullet_speed)` "
            "varies continuously across waves (distance and chosen power differ shot to "
            "shot). Dividing a quasi-discrete numerator by a continuously-varying denominator "
            "*smears* any interior comb into a continuum. The two GF values that survive this "
            "smearing are exactly the ones independent of MEA: offset=0 -> GF=0, and "
            "saturation -> GF=+-1. So the engine's discreteness is visible, but only as these "
            "three fixed-point spikes, disproving the *comb* form of the hypothesis.")
    else:
        lines.append("**Verdict: NOT SUPPORTED.** Neither channel shows spike structure "
                     "beyond sampling noise at this binning.")

    lines.append("")
    lines.append(
        "Decision rule: an *interior comb* requires >=10 spikes (excluding GF=0 and "
        "|GF|>=0.98) at >=5x their local smooth baseline, holding >=5% of all mass, with "
        "the tallest bin >=8x the median occupied bin. A *fixed-point* result is recorded "
        "when the GF=0 bin alone is >=8x the median occupied bin or the extremes hold "
        ">=1.5% of mass.")
    return "\n".join(lines)


def build_report(run_dir: Path, bins: int,
                 gun_by: dict[str, np.ndarray], mov_by: dict[str, np.ndarray],
                 gun_m: CombMetrics, mov_m: CombMetrics,
                 figs: dict[str, str], assets_rel: str) -> str:
    now = _dt.datetime.now(_dt.timezone.utc).strftime("%Y-%m-%d %H:%M:%SZ")
    robots = sorted({_short(r) for r in (*gun_by, *mov_by)})
    excluded = ", ".join(sorted(_short(r) for r in EXCLUDED_ROBOTS))
    out = [
        "# Detailed GuessFactor Histogram — discreteness-artifact analysis",
        "",
        f"- **Generated (UTC):** {now}",
        f"- **Run directory:** `{_rel(run_dir)}`",
        f"- **Fine binning:** {bins} bins over [-1, 1] (reference grid: {COARSE_BINS} bins)",
        "- **Self-play matchups excluded** from the pooled distributions.",
        f"- **Per robot:** {', '.join(robots)} (one line per robot; "
        f"excluded as too naive: {excluded}).",
        "",
        "## Hypothesis",
        "",
        "> A very granular GF histogram should expose *comb artifacts* — narrow, repeating "
        "spikes — produced by the engine's discrete state (integer velocities/accelerations "
        "-8..8, discrete bullet speeds `20 - 3*power` driving a quantized MEA). Because "
        "`GF = bearing_offset / MEA` is assembled from these discrete ingredients over an "
        "integer number of flight ticks, realized GF values should pile up at repeating "
        "rational positions instead of forming a smooth curve. Tested separately for the "
        "**gun** (`our_break_gf`, dejavu real waves) and **movement** (`their_break_gf`, "
        "their-waves).",
        "",
        "## Verdict",
        "",
        _verdict(gun_m, mov_m),
        "",
        "## Gun vs movement overlay",
        "",
        f"![gun vs movement]({assets_rel}/{figs['compare']})",
        "",
        "## Gun channel — `our_break_gf` (dejavu real waves)",
        "",
        f"![gun fine histogram]({assets_rel}/{figs['gun']})",
        "",
        "Per-robot comb metrics (each robot's own gun):",
        "",
        _per_robot_table(gun_by, bins),
        "",
        "Pooled across all robots:",
        "",
        _metrics_table(gun_m),
        "",
        "Top spike bins (engine-favored GF positions):",
        "",
        _top_spikes_table(gun_m),
        "",
        "## Movement channel — `their_break_gf` (their-waves)",
        "",
        f"![movement fine histogram]({assets_rel}/{figs['mov']})",
        "",
        "Per-robot comb metrics (each robot's own movement):",
        "",
        _per_robot_table(mov_by, bins),
        "",
        "Pooled across all robots:",
        "",
        _metrics_table(mov_m),
        "",
        "Top spike bins (engine-favored GF positions):",
        "",
        _top_spikes_table(mov_m),
        "",
        "## How to read this",
        "",
        "- Each per-channel figure draws **one normalized-density line per robot** "
        "(Autopilot excluded). A genuine comb shows as repeating spikes at the *same* GF "
        "for every line; a per-robot quirk shows on a single line only.",
        "- A spike exactly at `GF=0` is head-on accumulation (zero net lateral drift); "
        "spikes at `|GF|~=1` are max-escape-angle saturation. Intermediate repeating spikes "
        "come from integer lateral-velocity sequences over discrete flight ticks.",
        "- `Spikiness` and `Comb spikes` quantify the effect; the smooth 47-bin view used "
        "elsewhere (e.g. `ML-intuition.md` C1) averages the comb away, which is why it "
        "looks continuous there.",
        "",
    ]
    return "\n".join(out)


# --------------------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------------------

def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("run_dir", nargs="?", default=None,
                   help="producer run directory (default: newest under "
                        "pipeline/build/battle-csv-producer/)")
    p.add_argument("--bins", type=int, default=2000, help="fine bin count (default 2000)")
    p.add_argument("--out", default="wiki/detailed-gf.md", help="report output path")
    p.add_argument("--assets", default="wiki/detailed-gf", help="PNG asset directory")
    return p.parse_args(argv)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    if args.bins < COARSE_BINS * 2:
        raise SystemExit(f"detailed_gf.py: --bins must be >= {COARSE_BINS * 2}")

    run_dir = find_run_dir(args.run_dir)
    out_path = (REPO_ROOT / args.out) if not Path(args.out).is_absolute() else Path(args.out)
    assets_dir = (REPO_ROOT / args.assets) if not Path(args.assets).is_absolute() \
        else Path(args.assets)
    assets_rel = os.path.relpath(assets_dir, out_path.parent).replace(os.sep, "/")

    print(f"detailed_gf.py: run dir = {_rel(run_dir)}")
    gun_by = _collect_gf_by_robot(run_dir, "dejavu-waves.csv", "our_break_gf",
                                  real_only=True)
    mov_by = _collect_gf_by_robot(run_dir, "their-waves.csv", "their_break_gf",
                                  real_only=False)
    gun = _pool(gun_by)
    mov = _pool(mov_by)
    if gun.size == 0 and mov.size == 0:
        raise SystemExit("detailed_gf.py: no GF data found in the run directory")
    print(f"detailed_gf.py: robots = {len(set(gun_by) | set(mov_by))} "
          f"(excluded: {', '.join(sorted(_short(r) for r in EXCLUDED_ROBOTS))})")
    print(f"detailed_gf.py: gun waves = {gun.size:,} | movement waves = {mov.size:,}")

    gun_m = comb_metrics(gun, args.bins)
    mov_m = comb_metrics(mov, args.bins)

    figs = {
        "gun": plot_channel(gun_by, args.bins, "Gun — our_break_gf (dejavu real)",
                            assets_dir, "gun-gf"),
        "mov": plot_channel(mov_by, args.bins, "Movement — their_break_gf",
                            assets_dir, "movement-gf"),
        "compare": plot_compare(gun, mov, args.bins, assets_dir),
    }

    report = build_report(run_dir, args.bins, gun_by, mov_by, gun_m, mov_m, figs,
                          assets_rel)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(report + "\n", encoding="utf-8")
    print(f"detailed_gf.py: wrote {_rel(out_path)}")
    print(f"detailed_gf.py: 3 figures under {_rel(assets_dir)}")
    print(f"detailed_gf.py: gun comb spikes = {gun_m.comb_spikes} "
          f"({gun_m.comb_mass_pct:.1f}% mass) | "
          f"movement comb spikes = {mov_m.comb_spikes} ({mov_m.comb_mass_pct:.1f}% mass)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

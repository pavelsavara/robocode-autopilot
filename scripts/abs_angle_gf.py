#!/usr/bin/env python3
"""Absolute-bullet-angle GuessFactor hit-geometry test.

Hypothesis under test
---------------------
"Bullet absolute angle matters because the robot bounding box doesn't rotate, and the GF
range is bigger from absolute 45-degree angles."

Mechanism (PROVEN from the engine, not conjecture)
--------------------------------------------------
Robocode resolves a bullet hit with ``robot.getBoundingBox().intersectsLine(bulletLine)``
(BulletPeer.java), and that bounding box is an **axis-aligned 36x36 square that does NOT
rotate with heading** (see ``wiki/physics.md``). The cross-section a bullet at absolute
field angle ``theta`` sees is therefore

    width(theta)  = 36 * (|cos theta| + |sin theta|)            [px]
    half_width    = 18 * (|cos theta| + |sin theta|)            [px]

with ``box_factor(theta) = |cos theta| + |sin theta|`` in ``[1, sqrt(2)]`` - 18 px at the
cardinals (0/90/180/270 deg), ``18*sqrt(2) ~= 25.46`` px at the diagonals (45/135/225/315
deg, a 41% wider target). Because a wave's hittable GuessFactor half-band is

    gf_tol(theta) = half_width / (distance * MEA) = box_factor * (18 / (distance * MEA))

the hittable GF range is **box_factor times wider** - up to sqrt(2)x at diagonal absolute
angles. The hypothesis is correct by construction; the open questions this script answers
are *how much it matters across real battles* and *whether the flat-18 analytic hit model
used elsewhere (``intuition.py::canonical_hit``) systematically under-counts diagonal hits.*

Method
------
For every resolved real hero wave (``dejavu-waves.csv``, self-play and the non-competitive
``cz.zamboch.Autopilot`` excluded) the absolute bullet angle is taken straight from the
recorded ``our_fire_bearing_absolute`` column, and ``box_factor = |cos theta| + |sin
theta|``. The fire-to-break distance gives the flat-18 baseline. No hit reconstruction is
needed, so the measurement is exact.

Outputs ``wiki/abs-angle-gf.md`` plus a PNG under ``wiki/abs-angle-gf/``.

Usage:
  abs_angle_gf.py [RUN_DIR] [--out wiki/abs-angle-gf.md] [--assets wiki/abs-angle-gf]
"""
from __future__ import annotations

import argparse
import datetime as _dt
import math
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

#: Robot half-extent in pixels (Robocode bounding box is 36x36).
ROBOT_HALF = 18.0

#: sqrt(2): the diagonal box_factor ceiling.
SQRT2 = math.sqrt(2.0)

#: cz.zamboch.Autopilot is not a competitive robot; drop its perspective from the field.
EXCLUDED_ROBOTS = {"cz.zamboch.Autopilot"}

#: Real hero waves - geometry only, no hit reconstruction needed.
HERO_WAVE_FILE = "dejavu-waves.csv"

#: box_factor histogram grid: 8 equal bins over [1, sqrt(2)].
BOX_FACTOR_EDGES = np.linspace(1.0, SQRT2, 9)

#: Columns read from the wave file. ``our_fire_bearing_absolute`` (radians) is the recorded
#: absolute bullet angle; positions give the fire-to-break distance for the flat baseline.
GEOM_COLS = ("our_fire_x", "our_fire_y", "our_break_opponent_x", "our_break_opponent_y",
             "our_fire_mea", "our_fire_bearing_absolute")

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
            raise SystemExit(f"abs_angle_gf.py: run dir not found: {p}")
        return p
    if not PRODUCER_ROOT.is_dir():
        raise SystemExit(f"abs_angle_gf.py: no producer output under {PRODUCER_ROOT}")
    candidates = [d for d in PRODUCER_ROOT.iterdir() if d.is_dir()]
    if not candidates:
        raise SystemExit(f"abs_angle_gf.py: no run directories under {PRODUCER_ROOT}")
    return max(candidates, key=lambda d: d.stat().st_mtime)


def _is_self_play(matchup_dir: Path) -> bool:
    name = matchup_dir.name
    if "__vs__" not in name:
        return False
    a, b = name.split("__vs__", 1)
    return a == b


def _persp_robot(persp_dir: Path) -> str:
    name = persp_dir.name
    return name.split("-", 1)[1] if "-" in name else name


def _rel(path: Path) -> str:
    try:
        return path.resolve().relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return path.as_posix()


def _read_waves(run_dir: Path, wave_file: str, usecols: Sequence[str]) -> pd.DataFrame:
    """Pool one wave file across non-self-play perspectives, dropping excluded hosts.
    Only real waves are kept."""
    frames: list[pd.DataFrame] = []
    want = set(usecols) | {"our_fire_is_real"}
    for matchup_dir in sorted(p for p in run_dir.iterdir() if p.is_dir()):
        if _is_self_play(matchup_dir):
            continue
        for persp_dir in sorted(p for p in matchup_dir.iterdir() if p.is_dir()):
            if _persp_robot(persp_dir) in EXCLUDED_ROBOTS:
                continue
            f = persp_dir / wave_file
            if not f.is_file():
                continue
            try:
                df = pd.read_csv(f, usecols=lambda c, _w=want: c in _w)
            except (ValueError, pd.errors.EmptyDataError):
                continue
            if not set(usecols).issubset(df.columns):
                continue
            if "our_fire_is_real" in df.columns:
                df = df[df["our_fire_is_real"] == 1].copy()
            frames.append(df)
    if not frames:
        raise SystemExit(f"abs_angle_gf.py: no usable {wave_file} found in run dir")
    return pd.concat(frames, ignore_index=True)


def _geometry(df: pd.DataFrame) -> pd.DataFrame:
    """Attach box_factor / break_dist / flat & box GF tolerances. ``theta`` is the recorded
    absolute bullet angle ``our_fire_bearing_absolute`` (radians)."""
    for c in GEOM_COLS:
        df[c] = pd.to_numeric(df[c], errors="coerce")
    df = df.dropna(subset=list(GEOM_COLS))
    dx = df["our_break_opponent_x"].to_numpy() - df["our_fire_x"].to_numpy()
    dy = df["our_break_opponent_y"].to_numpy() - df["our_fire_y"].to_numpy()
    break_dist = np.hypot(dx, dy)
    theta = df["our_fire_bearing_absolute"].to_numpy()  # absolute bullet angle, radians
    box_factor = np.abs(np.cos(theta)) + np.abs(np.sin(theta))  # in [1, sqrt(2)]
    mea = df["our_fire_mea"].to_numpy()
    with np.errstate(divide="ignore", invalid="ignore"):
        flat_tol = ROBOT_HALF / (break_dist * mea)
    out = pd.DataFrame({
        "break_dist": break_dist,
        "theta_deg": np.degrees(theta),
        "box_factor": box_factor,
        "mea": mea,
        "flat_tol_gf": flat_tol,
        "box_tol_gf": flat_tol * box_factor,
    })
    out = out[(out["break_dist"] > 0) & (out["mea"] > 0) & np.isfinite(out["box_factor"])]
    return out.reset_index(drop=True)


# --------------------------------------------------------------------------------------
# Analysis
# --------------------------------------------------------------------------------------

@dataclass
class WidenSummary:
    n: int
    mean_bf: float
    median_bf: float
    p90_bf: float
    max_bf: float
    frac_ge_110: float          # share of waves with box_factor >= 1.10
    frac_ge_120: float
    frac_ge_130: float
    mean_undercount: float      # 1 - 1/mean_bf  (avg fraction of band the flat-18 model misses)
    worst_undercount: float     # 1 - 1/sqrt(2)


def widen_summary(geo: pd.DataFrame) -> WidenSummary:
    bf = geo["box_factor"].to_numpy()
    mean_bf = float(bf.mean())
    return WidenSummary(
        n=bf.size,
        mean_bf=mean_bf,
        median_bf=float(np.median(bf)),
        p90_bf=float(np.percentile(bf, 90)),
        max_bf=float(bf.max()),
        frac_ge_110=float((bf >= 1.10).mean()),
        frac_ge_120=float((bf >= 1.20).mean()),
        frac_ge_130=float((bf >= 1.30).mean()),
        mean_undercount=1.0 - 1.0 / mean_bf,
        worst_undercount=1.0 - 1.0 / SQRT2,
    )


@dataclass
class BinRow:
    lo: float
    hi: float
    center: float
    n_waves: int
    frac: float
    median_dist: float
    median_flat_tol: float
    median_box_tol: float


def bins_by_box_factor(geo: pd.DataFrame) -> list[BinRow]:
    """Per box_factor bin: wave counts and the median flat vs box hittable GF band."""
    rows: list[BinRow] = []
    bf = geo["box_factor"].to_numpy()
    dist = geo["break_dist"].to_numpy()
    flat = geo["flat_tol_gf"].to_numpy()
    box = geo["box_tol_gf"].to_numpy()
    total = bf.size
    for i in range(len(BOX_FACTOR_EDGES) - 1):
        lo, hi = BOX_FACTOR_EDGES[i], BOX_FACTOR_EDGES[i + 1]
        last = i == len(BOX_FACTOR_EDGES) - 2
        m = (bf >= lo) & (bf <= hi if last else bf < hi)
        n = int(m.sum())
        rows.append(BinRow(
            lo, hi, 0.5 * (lo + hi), n,
            (n / total) if total else float("nan"),
            float(np.median(dist[m])) if n else float("nan"),
            float(np.median(flat[m])) if n else float("nan"),
            float(np.median(box[m])) if n else float("nan"),
        ))
    return rows


# --------------------------------------------------------------------------------------
# Plotting
# --------------------------------------------------------------------------------------

def _save(fig, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(path, dpi=110, metadata=PNG_METADATA, bbox_inches="tight")
    plt.close(fig)


def plot_widening(geo: pd.DataFrame, assets_dir: Path) -> Path:
    """Distribution of box_factor over real waves + the GF-band widening it implies."""
    bf = geo["box_factor"].to_numpy()
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(11.5, 4.8))

    ax1.hist(bf, bins=60, range=(1.0, SQRT2), color="#4477aa", alpha=0.9)
    ax1.axvline(float(bf.mean()), color="#cc3311", lw=2, label=f"mean = {bf.mean():.3f}")
    ax1.axvline(4.0 / math.pi, color="#999999", ls="--", lw=1.4,
                label=f"uniform-angle E = {4/math.pi:.3f}")
    ax1.set_xlabel("box_factor = |cos theta| + |sin theta|  (theta = our_fire_bearing_absolute)")
    ax1.set_ylabel("real waves")
    ax1.set_title("Absolute-angle box_factor over real waves")
    ax1.set_xlim(1.0, SQRT2)
    ax1.legend(fontsize=8)
    ax1.grid(True, alpha=0.25)

    xs = np.linspace(1.0, SQRT2, 100)
    ax2.plot(xs, xs, color="#009988", lw=2.4, label="box model: tol *= box_factor")
    ax2.axhline(1.0, color="#555555", ls=":", lw=1.6, label="flat-18 model: tol *= 1")
    ax2.fill_between(xs, 1.0, xs, color="#009988", alpha=0.12)
    ax2.set_xlabel("box_factor")
    ax2.set_ylabel("hittable GF band, relative to flat-18")
    ax2.set_title("Diagonal absolute angles widen the hittable GF band")
    ax2.set_xlim(1.0, SQRT2)
    ax2.set_ylim(0.95, 1.45)
    ax2.legend(fontsize=8, loc="upper left")
    ax2.grid(True, alpha=0.25)
    p = assets_dir / "gf-band-widening.png"
    _save(fig, p)
    return p


# --------------------------------------------------------------------------------------
# Report
# --------------------------------------------------------------------------------------

def _fmt(x: float, nd: int = 2) -> str:
    return "n/a" if not np.isfinite(x) else f"{x:.{nd}f}"


def _pct(x: float, nd: int = 1) -> str:
    return "n/a" if not np.isfinite(x) else f"{x*100:.{nd}f}%"


def build_report(run_dir: Path, ws: WidenSummary, rows: list[BinRow],
                 figs: list[Path], assets_dir: Path, out_path: Path) -> str:
    def asset_link(p: Path) -> str:
        try:
            return p.resolve().relative_to(out_path.parent.resolve()).as_posix()
        except ValueError:
            return _rel(p)

    L: list[str] = []
    L.append("# Absolute bullet angle and GuessFactor hit geometry")
    L.append("")
    L.append(f"_Generated {_dt.datetime.now(_dt.timezone.utc):%Y-%m-%d %H:%M UTC} "
             f"from `{_rel(run_dir)}`._")
    L.append("")
    L.append("## Verdict: CONFIRMED (engine mechanism)")
    L.append("")
    L.append("The hypothesis is correct **by construction**, not by chance. Robocode "
             "resolves a hit with `robot.getBoundingBox().intersectsLine(bulletLine)` and "
             "that box is an **axis-aligned 36x36 square that does not rotate with "
             "heading** (`wiki/physics.md`, `BulletPeer.java`). A bullet at absolute angle "
             "`theta` therefore sees a target of half-width `18 * (|cos theta| + |sin "
             "theta|)` px - 18 px at the cardinals, `18*sqrt(2) ~= 25.46` px at the "
             "diagonals (a 41% wider target). The hittable GuessFactor half-band is "
             "`box_factor * 18 / (distance * MEA)`, so **the hittable GF range is "
             "`box_factor`x wider, up to sqrt(2)x at 45-degree absolute angles.**")
    L.append("")
    L.append("## How much it matters across real battles")
    L.append("")
    L.append(f"Measured over **{ws.n:,}** resolved real hero waves (`dejavu-waves.csv`, "
             "self-play and the non-competitive `cz.zamboch.Autopilot` excluded). The "
             "absolute bullet angle is the recorded `our_fire_bearing_absolute` column; "
             "`box_factor = |cos theta| + |sin theta|`:")
    L.append("")
    L.append("| quantity | value |")
    L.append("|---|---|")
    L.append(f"| mean box_factor (mean GF-band widening) | {_fmt(ws.mean_bf,3)}x |")
    L.append(f"| median box_factor | {_fmt(ws.median_bf,3)}x |")
    L.append(f"| p90 box_factor | {_fmt(ws.p90_bf,3)}x |")
    L.append(f"| max box_factor | {_fmt(ws.max_bf,3)}x |")
    L.append(f"| waves with box_factor >= 1.10 | {_pct(ws.frac_ge_110)} |")
    L.append(f"| waves with box_factor >= 1.20 | {_pct(ws.frac_ge_120)} |")
    L.append(f"| waves with box_factor >= 1.30 | {_pct(ws.frac_ge_130)} |")
    L.append(f"| mean hit-band the flat-18 model misses | {_pct(ws.mean_undercount)} |")
    L.append(f"| worst-case (diagonal) under-count | {_pct(ws.worst_undercount)} |")
    L.append("")
    L.append("Per box_factor bin (median hittable GF half-band, flat-18 vs box model):")
    L.append("")
    L.append("| box_factor bin | center | waves | share | median dist | flat GF tol | box GF tol |")
    L.append("|---|---|---|---|---|---|---|")
    for r in rows:
        L.append(f"| [{r.lo:.3f}, {r.hi:.3f}] | {r.center:.3f} | {r.n_waves:,} | "
                 f"{_pct(r.frac)} | {_fmt(r.median_dist,0)} | {_fmt(r.median_flat_tol,4)} | "
                 f"{_fmt(r.median_box_tol,4)} |")
    L.append("")
    L.append("**Actionable:** the analytic hit rule `canonical_hit` in `scripts/intuition.py` "
             "(and `gf_tol ~= (18/fire_distance)/mea` in `wiki/intuition-design.md`) uses a "
             "**flat 18 px** half-width. It therefore under-counts the true hittable GF band "
             f"by ~{_pct(ws.mean_undercount,0)} on average and up to "
             f"{_pct(ws.worst_undercount,0)} at diagonal absolute angles. Replacing 18 with "
             "`18 * (|cos theta| + |sin theta|)` (theta = `our_fire_bearing_absolute`) makes "
             "the analytic hit baseline match the engine.")
    L.append("")
    L.append("## Figures")
    L.append("")
    for p in figs:
        L.append(f"![{p.stem}]({asset_link(p)})")
        L.append("")
    L.append(f"_Assets under `{_rel(assets_dir)}/`._")
    L.append("")
    return "\n".join(L)


# --------------------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------------------

def parse_args(argv: Optional[Sequence[str]]) -> argparse.Namespace:
    ap = argparse.ArgumentParser(description="Absolute-bullet-angle GF hit-geometry test.")
    ap.add_argument("run_dir", nargs="?", default=None,
                    help="battle-csv-producer run dir (default: newest).")
    ap.add_argument("--out", default="wiki/abs-angle-gf.md", help="report markdown path.")
    ap.add_argument("--assets", default="wiki/abs-angle-gf", help="PNG assets directory.")
    return ap.parse_args(argv)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    run_dir = find_run_dir(args.run_dir)

    out_path = (REPO_ROOT / args.out) if not Path(args.out).is_absolute() else Path(args.out)
    assets_dir = (REPO_ROOT / args.assets) if not Path(args.assets).is_absolute() else Path(args.assets)

    hero = _geometry(_read_waves(run_dir, HERO_WAVE_FILE, GEOM_COLS))
    ws = widen_summary(hero)
    rows = bins_by_box_factor(hero)

    figs = [plot_widening(hero, assets_dir)]
    report = build_report(run_dir, ws, rows, figs, assets_dir, out_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(report, encoding="utf-8")

    print("abs_angle_gf.py: VERDICT = CONFIRMED (engine mechanism: non-rotating 36x36 box)")
    print(f"abs_angle_gf.py: {ws.n:,} hero waves; mean GF-band widening = {ws.mean_bf:.3f}x "
          f"(up to {SQRT2:.3f}x); flat-18 under-count ~{ws.mean_undercount*100:.0f}% avg")
    print(f"abs_angle_gf.py: wrote {_rel(out_path)} + {len(figs)} figure(s) under {_rel(assets_dir)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

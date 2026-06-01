#!/usr/bin/env python3
"""Generate <Display>-fired-bullets.md (Layer 3) and <Display>-energy-events.md
(Layer 2) for a single opponent from the aggregated trace CSVs.

Usage:
  gen-drift-reports.py <their-fires.csv> <damage-events.csv> <opponentName> \
                       <Display> <seed> <outDir>

Pairing key for Layer 3 = (round, fireTick), perspective 0 (Autopilot side).
Each drift instance is categorized with a probable root cause.
"""
import csv
import math
import sys
from collections import defaultdict
from pathlib import Path

if len(sys.argv) < 7:
    print(__doc__, file=sys.stderr)
    sys.exit(1)

fires_csv = Path(sys.argv[1])
dmg_csv = Path(sys.argv[2])
opponent = sys.argv[3]
display = sys.argv[4]
seed = sys.argv[5]
out_dir = Path(sys.argv[6])


def fnum(s, d=4):
    if s is None or s == "":
        return "—"
    try:
        return f"{float(s):.{d}f}"
    except ValueError:
        return s


def norm(h):
    while h > math.pi:
        h -= 2 * math.pi
    while h < -math.pi:
        h += 2 * math.pi
    return h


# ---------------------------------------------------------------------------
# Load damage-events (Layer 2) — also used to explain Layer 3 phantoms.
# ---------------------------------------------------------------------------
dmg_rows = []
with dmg_csv.open() as f:
    for row in csv.DictReader(f):
        if row["opponent"] == opponent:
            dmg_rows.append(row)

# Wall-hit ticks (per round) where the opponent hit a wall but we could not
# observe it — these are the prime suspects for Layer 3 phantoms.
wall_ticks = defaultdict(set)   # round -> {tick}
# Per-channel god-view event ticks (any non-zero gv), used to attribute a
# phantom to a concurrent energy event the fire-detector mis-booked as a shot.
gain_ticks = defaultdict(dict)   # round -> {tick: gvBonus}  (OPP_BULLET_GAIN)
ourdmg_ticks = defaultdict(set)  # round -> {tick}           (OUR_BULLET_DMG)
ram_ticks = defaultdict(set)     # round -> {tick}           (RAM_DMG)
for r in dmg_rows:
    rnd = int(r["round"])
    tick = int(r["tick"])
    ch = r["channel"]
    gv = float(r["gv"] or 0)
    if ch == "OPP_WALL_DMG" and abs(float(r["drift"] or 0)) > 1e-6:
        wall_ticks[rnd].add(tick)
    if ch == "OPP_BULLET_GAIN" and gv > 1e-6:
        gain_ticks[rnd][tick] = gv
    if ch == "OUR_BULLET_DMG" and gv > 1e-6:
        ourdmg_ticks[rnd].add(tick)
    if ch == "RAM_DMG" and gv > 1e-6:
        ram_ticks[rnd].add(tick)

# ---------------------------------------------------------------------------
# Load their-fires (Layer 3), bucket by (round, tick).
# ---------------------------------------------------------------------------
buckets = defaultdict(dict)
gv_ticks = defaultdict(set)    # round -> {tick} where a GV fire exists
gv_tick_list = defaultdict(list)  # round -> [tick,...] raw (keeps duplicates)
with fires_csv.open() as f:
    for row in csv.DictReader(f):
        if row["opponent"] != opponent:
            continue
        if int(row["perspective"]) != 0:
            continue
        key = (int(row["round"]), int(row["tick"]))
        buckets[key][row["kind"]] = row
        if row["kind"] == "GV":
            gv_ticks[int(row["round"])].add(int(row["tick"]))
            gv_tick_list[int(row["round"])].append(int(row["tick"]))

# A god-view fire is reconstructed by the opponent's god-view wave-resolver, not
# read from the engine's bullet list. When the opponent fires in a steady gun-heat
# cadence the resolver occasionally double-books one fire onto an earlier tick and
# drops the next on-cadence fire — so per round, #duplicate-GV-ticks == #phantoms.
# The autopilot (RS) detector, working off scan-resolution energy drops, still
# places those real fires correctly, which is why they surface as "phantoms".
gv_dups = {}   # round -> count of duplicate GV fire records (resolver double-books)
for rnd, lst in gv_tick_list.items():
    gv_dups[rnd] = len(lst) - len(set(lst))

# Typical opponent gun-heat interval: gunHeat = 1 + power/5 cooling at 0.1/tick,
# i.e. ~14-15 ticks between shots. A phantom whose previous GV fire sits one such
# interval earlier is a real fire the god-view resolver failed to record.
CADENCE_MIN, CADENCE_MAX = 12, 17


def _prev_gv_gap(rnd, tick):
    """Ticks since the nearest GV fire strictly before `tick` (same round)."""
    best = None
    for t in gv_ticks.get(rnd, ()):
        if t < tick and (best is None or t > best):
            best = t
    return (tick - best) if best is not None else None


def _next_gv_gap(rnd, tick):
    """Ticks until the nearest GV fire strictly after `tick` (same round)."""
    best = None
    for t in gv_ticks.get(rnd, ()):
        if t > tick and (best is None or t < best):
            best = t
    return (best - tick) if best is not None else None


def _on_cadence(rnd, tick):
    """True if the phantom lands one gun-heat interval from an adjacent GV fire."""
    pg = _prev_gv_gap(rnd, tick)
    ng = _next_gv_gap(rnd, tick)
    return (pg is not None and CADENCE_MIN <= pg <= CADENCE_MAX) or \
           (ng is not None and CADENCE_MIN <= ng <= CADENCE_MAX)

matched, missed, phantom = [], [], []
for key in sorted(buckets):
    b = buckets[key]
    if "GV" in b and "RS" in b:
        matched.append((key, b["GV"], b["RS"]))
    elif "GV" in b:
        missed.append((key, b["GV"]))
    else:
        phantom.append((key, b["RS"]))

total_gv = len(matched) + len(missed)
total_rs = len(matched) + len(phantom)


def near_gv(rnd, tick, window=2):
    """True if a GV fire exists within +/-window ticks in the same round."""
    for t in gv_ticks.get(rnd, ()):  # small sets
        if t != tick and abs(t - tick) <= window:
            return t
    return None


def _nearest(rnd, tick, ticks, window):
    """Nearest tick in `ticks` (set/dict) within +/-window of tick, or None."""
    best, bestd = None, window + 1
    for t in ticks:
        d = abs(t - tick)
        if d <= window and d < bestd:
            best, bestd = t, d
    return best


def _drop(rs):
    """Scan-to-scan opponent energy drop the fire-detector attributed to a shot."""
    try:
        return float(rs["prevScanEnergy"]) - float(rs["oppEnergy"])
    except (TypeError, ValueError):
        return float("nan")


def categorize_phantom(key, rs):
    rnd, tick = key
    drop = _drop(rs)
    power = rs.get("power")
    try:
        power = float(power)
    except (TypeError, ValueError):
        power = float("nan")
    try:
        gap = int(rs.get("scanGap") or 0)
    except ValueError:
        gap = 0

    # 1) Opponent hit a wall this tick (energy dropped, no HitWallEvent for us).
    if tick in wall_ticks.get(rnd, ()):
        return "wall-hit energy drop (opponent HIT_WALL; no observable HitWallEvent)"
    if (rs.get("oppState") or "") == "HIT_WALL":
        return "wall-hit energy drop (opponent HIT_WALL; no observable HitWallEvent)"

    # 2) Ram collision changed energy.
    if float(rs.get("ramDmg") or 0) > 1e-6 or (rs.get("oppState") or "") == "HIT_ROBOT" \
            or (rs.get("selfState") or "") == "HIT_ROBOT":
        return "ram-collision energy change misread as fire"

    # 3) Real opponent fire the god-view resolver failed to record. The GV "fire"
    #    log is itself a reconstruction; on a steady gun-heat cadence the resolver
    #    double-books one shot and drops the next, or a simultaneous bullet-hit
    #    energy gain masks the fire's drop so its net-energy gate skips it. The RS
    #    detector (scan-resolution energy drop) still catches the real shot.
    if _on_cadence(rnd, tick):
        pg = _prev_gv_gap(rnd, tick)
        ng = _next_gv_gap(rnd, tick)
        cad = f"+{pg}" if (pg is not None and CADENCE_MIN <= pg <= CADENCE_MAX) \
            else f"-{ng}"
        tg = _nearest(rnd, tick, gain_ticks.get(rnd, {}), 3)
        if tg is not None:
            bonus = gain_ticks[rnd][tg]
            mech = (f"its drop was masked by a simultaneous bullet-hit gain "
                    f"(+{bonus:.2f} at t{tg}), so the god-view net-energy gate "
                    f"skipped it")
        elif gv_dups.get(rnd, 0) > 0:
            mech = (f"the god-view resolver double-booked an adjacent fire "
                    f"(round has {gv_dups[rnd]} duplicate GV tick(s)) and dropped "
                    f"this one")
        else:
            mech = "the god-view resolver did not register it"
        return (f"real opponent fire missed by god-view ground truth — on the "
                f"gun-heat firing cadence ({cad} ticks from the adjacent GV fire), "
                f"power~{drop:.2f}; {mech}. RS correctly detected the energy drop")

    # 4) Bullet-hit energy-gain bookkeeping residual. When the opponent's bullet
    #    strikes us it is credited +3x its power; the scan-to-scan reconciliation
    #    of that gain can land a tick off, leaving a power-sized phantom drop.
    tg = _nearest(rnd, tick, gain_ticks.get(rnd, {}), 3)
    if tg is not None:
        bonus = gain_ticks[rnd][tg]
        impliedPow = bonus / 3.0
        return (f"bullet-hit energy-gain bookkeeping residual — opponent's bullet "
                f"struck us at t{tg} (+{bonus:.2f} = 3x{impliedPow:.2f} power); the "
                f"gain reconciled one scan off, leaving a power~{drop:.2f} phantom")

    # 5) Our bullet hit the opponent but the robot-side damage credit slipped a
    #    tick, so the residual energy drop reads as an opponent shot.
    td = _nearest(rnd, tick, ourdmg_ticks.get(rnd, ()), 2)
    if td is not None:
        return (f"our-bullet-hit bookkeeping residual — our bullet damaged the "
                f"opponent at t{td}; the credit reconciled a tick off, leaving a "
                f"power~{drop:.2f} phantom drop")

    # 6) Multi-tick energy change aliased across a radar gap and read as one shot.
    if gap >= 2:
        return (f"scan-gap aliasing — {gap}-tick energy change (Δ={drop:.2f}) "
                f"observed across a radar gap and misread as a single shot")

    # 7) Split/duplicate detection adjacent to a real fire.
    t = near_gv(rnd, tick)
    if t is not None:
        return f"duplicate/split detection adjacent to real GV fire at t{t} (scan gap {rs.get('scanGap')})"

    # 8) Clean fire-power-sized drop with no attributable damage channel and no
    #    god-view bullet nearby — an opponent energy change the GV fire log does
    #    not cover (e.g. sub-tick bullet bookkeeping, or an opponent self-energy
    #    adjustment the engine applied off a detection tick).
    nearest_gv = None
    bestd = 999
    for t in gv_ticks.get(rnd, ()):
        d = abs(t - tick)
        if d < bestd:
            nearest_gv, bestd = t, d
    gv_note = f"nearest GV fire {bestd} ticks away" if nearest_gv is not None \
        else "no GV fires this round"
    return (f"fire-power-sized energy drop (Δenergy~{drop:.2f}, power~{power:.2f}) "
            f"with no bullet/ram/wall damage event and {gv_note} — opponent energy "
            f"change outside the god-view fire log")


def phantom_category(key, rs):
    """Coarse bucket label for the summary table (groups the per-instance
    detailed reasons from categorize_phantom)."""
    rnd, tick = key
    if tick in wall_ticks.get(rnd, ()) or (rs.get("oppState") or "") == "HIT_WALL":
        return "opponent wall hit (no observable HitWallEvent)"
    if float(rs.get("ramDmg") or 0) > 1e-6 or (rs.get("oppState") or "") == "HIT_ROBOT" \
            or (rs.get("selfState") or "") == "HIT_ROBOT":
        return "ram-collision energy change"
    if _on_cadence(rnd, tick):
        return "real opponent fire missed by god-view ground truth (on firing cadence)"
    if _nearest(rnd, tick, gain_ticks.get(rnd, {}), 3) is not None:
        return "bullet-hit energy-gain bookkeeping residual (opponent bullet struck us)"
    if _nearest(rnd, tick, ourdmg_ticks.get(rnd, ()), 2) is not None:
        return "our-bullet-hit bookkeeping residual"
    try:
        gap = int(rs.get("scanGap") or 0)
    except ValueError:
        gap = 0
    if gap >= 2:
        return "scan-gap aliasing (multi-tick energy change over a radar gap)"
    if near_gv(rnd, tick) is not None:
        return "duplicate/split detection adjacent to a real GV fire"
    return "fire-power-sized energy drop with no attributable damage channel"


def categorize_missed(key, gv):
    rnd, tick = key
    t = near_gv(rnd, tick)
    # Detected one tick off would land in phantom; here check if RS exists nearby.
    return "no robot-side detection (energy drop masked by simultaneous damage or radar gap)"


# ---------------------------------------------------------------------------
# fired-bullets.md
# ---------------------------------------------------------------------------
L = []
A = L.append
A(f"# {display} — Fired Bullets Detection Trace (Layer 3)")
A("")
A(f"Source: `{fires_csv.as_posix()}` (10-round battle vs `{opponent}`, "
  f"autopilot perspective, seed `{seed}`).")
A("")
A("Perspective 0 (Autopilot side). Pairing key = `(round, fireTick)`. "
  "`GV` = god-view (opponent actually shot); `RS` = robot-side (we inferred a shot).")
A("")
A("## Summary")
A("")
A(f"- God-view fires (opponent actually shot): **{total_gv}**")
A(f"- Robot-side detections (we inferred a shot): **{total_rs}**")
A(f"- Distinct `(round, tick)` keys: **{len(buckets)}**")
A(f"- **MATCHED**: {len(matched)}")
A(f"- **MISSED** (GV without RS): {len(missed)}")
A(f"- **PHANTOM** (RS without GV): {len(phantom)}")
det = (total_rs / total_gv) if total_gv else float("nan")
mr = (len(matched) / total_gv) if total_gv else float("nan")
A(f"- Detection rate (count-based): {det:.3f}")
A(f"- Exact-tick match rate: {mr:.3f}")
A("")

if matched:
    pos = [math.hypot(float(g['x']) - float(r['x']), float(g['y']) - float(r['y']))
           for _, g, r in matched]
    pw = [abs(float(g['power']) - float(r['power'])) for _, g, r in matched]
    hd = [abs(norm(float(g['heading']) - float(r['heading']))) for _, g, r in matched]
    A("## Paired error summary (MATCHED)")
    A("")
    A(f"- positionMAE = {sum(pos)/len(pos):.4f} (max {max(pos):.4f})")
    A(f"- powerMAE    = {sum(pw)/len(pw):.4f} (max {max(pw):.4f})")
    A(f"- angleMAE    = {sum(hd)/len(hd):.4f} rad (max {max(hd):.4f} rad)")
    A("")

# --- Drift categorization for PHANTOM / MISSED ---
A("## Drift categorization")
A("")
phantom_cat = defaultdict(list)
for key, rs in phantom:
    phantom_cat[phantom_category(key, rs)].append(key)
missed_cat = defaultdict(list)
for key, gv in missed:
    missed_cat[categorize_missed(key, gv)].append(key)

if not phantom and not missed:
    A("_No MISSED or PHANTOM drift instances — every god-view fire matched a "
      "robot-side detection at the exact tick._")
    A("")
else:
    if phantom:
        A(f"### PHANTOM causes ({len(phantom)})")
        A("")
        A("| reason | count | (round,tick) instances |")
        A("|--------|------:|------------------------|")
        for reason, keys in sorted(phantom_cat.items(), key=lambda kv: -len(kv[1])):
            ks = ", ".join(f"r{r}t{t}" for r, t in keys[:12])
            if len(keys) > 12:
                ks += f", …(+{len(keys)-12})"
            A(f"| {reason} | {len(keys)} | {ks} |")
        A("")
    if missed:
        A(f"### MISSED causes ({len(missed)})")
        A("")
        A("| reason | count | (round,tick) instances |")
        A("|--------|------:|------------------------|")
        for reason, keys in sorted(missed_cat.items(), key=lambda kv: -len(kv[1])):
            ks = ", ".join(f"r{r}t{t}" for r, t in keys[:12])
            if len(keys) > 12:
                ks += f", …(+{len(keys)-12})"
            A(f"| {reason} | {len(keys)} | {ks} |")
        A("")


def pair_line(key, gv, rs):
    rnd, tick = key
    dpos = math.hypot(float(gv['x']) - float(rs['x']), float(gv['y']) - float(rs['y']))
    dpow = float(gv['power']) - float(rs['power'])
    dh = norm(float(gv['heading']) - float(rs['heading']))
    return (f"- r{rnd} t{tick}: power gv={fnum(gv['power'])} rs={fnum(rs['power'])} "
            f"(Δ={dpow:+.4f}); pos Δ={dpos:.4f}; heading gv={fnum(gv['heading'])} "
            f"rs={fnum(rs['heading'])} (Δ={dh:+.4f} rad); scanGap={rs['scanGap']}")


def row_line(prefix, key, row, reason=None):
    rnd, tick = key
    s = (f"- **{prefix}** r{rnd} t{tick}: power={fnum(row['power'])}, "
         f"pos=({fnum(row['x'],2)},{fnum(row['y'],2)}), heading={fnum(row['heading'])}, "
         f"oppE={fnum(row['oppEnergy'],2)} (prev={fnum(row['prevScanEnergy'],2)}), "
         f"scanGap={row['scanGap']}, ramDmg={fnum(row['ramDmg'],3)}, "
         f"wallDmg={fnum(row['wallDmg'],3)}, oppState={row.get('oppState') or '—'}")
    if reason:
        s += f"  → _{reason}_"
    return s


A(f"## MATCHED ({len(matched)})")
A("")
for key, gv, rs in matched:
    A(pair_line(key, gv, rs))
A("")
A(f"## MISSED ({len(missed)})")
A("")
if not missed:
    A("_(none)_")
for key, gv in missed:
    A(row_line("GV", key, gv, categorize_missed(key, gv)))
A("")
A(f"## PHANTOM ({len(phantom)})")
A("")
if not phantom:
    A("_(none)_")
for key, rs in phantom:
    A(row_line("RS", key, rs, categorize_phantom(key, rs)))
A("")

fired_path = out_dir / f"{display}-fired-bullets.md"
fired_path.write_text("\n".join(L), encoding="utf-8")
print(f"wrote {fired_path} (matched={len(matched)} missed={len(missed)} phantom={len(phantom)})")

# ---------------------------------------------------------------------------
# energy-events.md  (Layer 2)
# ---------------------------------------------------------------------------
CHANNELS = ["OUR_BULLET_DMG", "OPP_BULLET_GAIN", "RAM_DMG", "OPP_WALL_DMG"]
CH_LABEL = {
    "OUR_BULLET_DMG": "`OUR_BULLET_DMG`",
    "OPP_BULLET_GAIN": "`OPP_BULLET_GAIN`",
    "RAM_DMG": "`RAM_DMG`",
    "OPP_WALL_DMG": "`OPP_WALL_DMG`",
}

by_ch = defaultdict(list)
for r in dmg_rows:
    by_ch[r["channel"]].append(r)

rounds = sorted({int(r["round"]) for r in dmg_rows})

E = []
B = E.append
B(f"# {display} — Damage Observation Drift (Layer 2)")
B("")
B(f"Source: `{dmg_csv.as_posix()}` (10-round battle vs `{opponent}`, "
  f"autopilot perspective, seed `{seed}`).")
B("")
B(f"Companion to [{display}-fired-bullets.md]({display}-fired-bullets.md). "
  "Layer 2 measures *damage-bookkeeping* quality: for every legal source of "
  "opponent-energy change that `FireFeatures.process` subtracts from the "
  "scan-to-scan delta, does the autopilot's running tally match god-view truth? "
  "`drift = obs − gv`. One row per `(round, scanTick, channel)` where either "
  "`gv` or `obs` is non-zero.")
B("")
B("| channel | god-view source | wb feature |")
B("|---------|-----------------|------------|")
B("| `OUR_BULLET_DMG` | our bullet enters `HIT_VICTIM` on opponent | `OUR_BULLET_DAMAGE_TO_OPPONENT` |")
B("| `OPP_BULLET_GAIN` | opponent bullet `HIT_VICTIM` on us → opponent earns 3·power | `OPPONENT_BULLET_ENERGY_GAIN` |")
B("| `RAM_DMG` | either robot in `HIT_ROBOT` this tick | `RAM_DAMAGE_TO_OPPONENT` |")
B("| `OPP_WALL_DMG` | opponent transitions into `HIT_WALL`; charge `wallDamage(prevV)` | `OPPONENT_WALL_HIT_DAMAGE` |")
B("")

total_events = len(dmg_rows)
total_abs_drift = sum(abs(float(r["drift"] or 0)) for r in dmg_rows)
B(f"## Summary ({len(rounds)} rounds, {total_events} events)")
B("")
B("| channel | events | gv total | obs total | absDrift | mismatch rows |")
B("|---------|-------:|---------:|----------:|---------:|--------------:|")
for ch in CHANNELS:
    rows = by_ch.get(ch, [])
    gv_t = sum(float(r["gv"] or 0) for r in rows)
    obs_t = sum(float(r["obs"] or 0) for r in rows)
    abs_d = sum(abs(float(r["drift"] or 0)) for r in rows)
    mm = sum(1 for r in rows if abs(float(r["drift"] or 0)) > 1e-6)
    B(f"| {CH_LABEL[ch]} | {len(rows)} | {gv_t:.4f} | {obs_t:.4f} | {abs_d:.4f} | {mm} |")
B("")
B(f"`totalAbsDrift = {total_abs_drift:.4f}` across {total_events} events.")
B("")

# Per-round breakdown
B("## Per-round breakdown")
B("")
B("| round | opp shots that hit us | opp bullet gain | our hits on opp | our dmg dealt | wall hits (gv) | wall dmg charged (gv) | wall dmg observed |")
B("|------:|----------------------:|----------------:|----------------:|--------------:|---------------:|----------------------:|------------------:|")
for rnd in rounds:
    rr = [r for r in dmg_rows if int(r["round"]) == rnd]
    ob = [r for r in rr if r["channel"] == "OPP_BULLET_GAIN"]
    ub = [r for r in rr if r["channel"] == "OUR_BULLET_DMG"]
    wl = [r for r in rr if r["channel"] == "OPP_WALL_DMG"]
    ob_sum = sum(float(r["gv"] or 0) for r in ob)
    ub_sum = sum(float(r["gv"] or 0) for r in ub)
    wl_gv = sum(float(r["gv"] or 0) for r in wl)
    wl_obs = sum(float(r["obs"] or 0) for r in wl)
    B(f"| {rnd} | {len(ob)} | {ob_sum:.2f} | {len(ub)} | {ub_sum:.2f} | "
      f"{len(wl)} | {wl_gv:.2f} | {wl_obs:.2f} |")
B("")

# Drift categorization (Layer 2)
B("## Drift categorization")
B("")
drift_rows = [r for r in dmg_rows if abs(float(r["drift"] or 0)) > 1e-6]
if not drift_rows:
    B("_No drift — every channel matched god-view exactly across all events._")
    B("")
else:
    cat = defaultdict(lambda: [0, 0.0])  # reason -> [count, absDrift]

    def cat_l2(r):
        ch = r["channel"]
        opp_state = r.get("oppState") or ""
        our_state = r.get("ourState") or ""
        if ch == "OPP_WALL_DMG":
            return ("opponent wall hit — intra-tick impact velocity unobservable; "
                    "autopilot has no opponent HitWallEvent (irreducible)")
        if ch == "RAM_DMG" and (opp_state == "DEAD" or our_state == "DEAD"):
            return "ram on death tick — simultaneous-collision residual"
        if ch == "OUR_BULLET_DMG":
            return "our-bullet damage bookkeeping mismatch (timing of HIT_VICTIM transition)"
        if ch == "OPP_BULLET_GAIN":
            return "opponent-bullet gain bookkeeping mismatch (timing of HIT_VICTIM transition)"
        if ch == "RAM_DMG":
            return "ram damage bookkeeping mismatch"
        return "other"

    for r in drift_rows:
        reason = cat_l2(r)
        cat[reason][0] += 1
        cat[reason][1] += abs(float(r["drift"] or 0))
    B("| reason | drift rows | Σ|drift| |")
    B("|--------|-----------:|---------:|")
    for reason, (c, d) in sorted(cat.items(), key=lambda kv: -kv[1][1]):
        B(f"| {reason} | {c} | {d:.4f} |")
    B("")

# Detailed mismatch rows per channel
for ch in CHANNELS:
    rows = by_ch.get(ch, [])
    mm = [r for r in rows if abs(float(r["drift"] or 0)) > 1e-6]
    if not mm:
        continue
    B(f"## {CH_LABEL[ch]} mismatch rows ({len(mm)})")
    B("")
    B("| round | scanTick | gv | obs | drift | ourEnergy | oppEnergy | oppState |")
    B("|------:|---------:|---:|----:|------:|----------:|----------:|----------|")
    for r in mm:
        B(f"| {r['round']} | {r['tick']} | {fnum(r['gv'])} | {fnum(r['obs'])} | "
          f"{float(r['drift']):+.4f} | {fnum(r['ourEnergy'],3)} | "
          f"{fnum(r['oppEnergy'],3)} | {r.get('oppState') or '—'} |")
    B("")

energy_path = out_dir / f"{display}-energy-events.md"
energy_path.write_text("\n".join(E), encoding="utf-8")
print(f"wrote {energy_path} (events={total_events} totalAbsDrift={total_abs_drift:.4f})")

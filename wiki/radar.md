# Radar Lock — Physics, Worst-Case Analysis, and Design

*Reference: verified against Robocode 1.10.x source (`RobotPeer.java`, `Rules.java`)
and the autopilot's own `OvershootLockRadar` / `Autopilot` wiring. Angles in
**degrees** unless noted. "Tick" = one game turn.*

---

## 1. Radar / Body / Gun Rules (engine ground truth)

### 1.1 Per-tick update order

`RobotPeer.performLoadCommands()` updates headings in this fixed order, then scans:

```
updateHeading();        // body   (RobotPeer.java:1389)
updateGunHeading();     // gun    (RobotPeer.java:1347)
updateRadarHeading();   // radar  (RobotPeer.java:1462)
... move, collide ...
scan(lastRadarHeading)  // arc = radarHeading - lastRadarHeading  (RobotPeer.java:1594)
```

`lastRadarHeading` is captured **before** all three updates (`RobotPeer.java:974`),
so the scanned arc is the **total** angular change of the radar across the whole
tick — body rotation + gun rotation + the radar's own rotation.

### 1.2 The radar rides the gun and the body

The radar is mechanically bolted on top of the gun, which is bolted on top of the
body. Every degree the body turns, the gun and radar turn with it; every degree the
gun turns, the radar turns with it:

```java
// updateGunHeading() — RobotPeer.java:1359
gunHeading   += Rules.GUN_TURN_RATE_RADIANS;
radarHeading += Rules.GUN_TURN_RATE_RADIANS;   // radar dragged by the gun
if (currentCommands.isAdjustRadarForGunTurn()) {
    setRadarTurnRemaining(radarTurnRemaining - Rules.GUN_TURN_RATE_RADIANS);
}
```

The `setAdjustRadarForGunTurn(true)` / `setAdjustRadarForBodyTurn(true)` flags
(both set in `Autopilot`) do **not** decouple the radar — they only **subtract the
dragged motion from the *remaining* radar command** so that, *as long as the
command is not saturated*, the radar's final heading equals what you asked for.

### 1.3 The clamp acts on the *leftover*, not on your command

`updateRadarHeading()` applies the radar's own ±45°/tick limit to whatever is left
of `radarTurnRemaining` **after** the body/gun subtraction:

```java
// updateRadarHeading() — RobotPeer.java:1462
if (radarTurnRemaining >  RADAR_TURN_RATE) radarHeading += RADAR_TURN_RATE; // +45
else if (radarTurnRemaining < -RADAR_TURN_RATE) radarHeading -= RADAR_TURN_RATE;
else radarHeading += radarTurnRemaining;
```

### 1.4 Net swept arc — the master equation

Let, for this tick (all signed, in the radar's intended turn direction):

- `R` = commanded radar turn (what `getRadarTurn()` returns),
- `b` = actual body rotation this tick, `|b| ≤ 10 − 0.75·|v|` (10 at v=0, 4 at v=8),
- `g` = actual gun rotation this tick, `|g| ≤ 20`.

Then the **net radar sweep** the engine produces is:

```
sweep = b + g + clamp( R − b − g , ±45° )
```

- **Unsaturated** (`|R − b − g| ≤ 45`): `sweep = R`. The adjust flags worked; you got
  exactly what you asked for.
- **Saturated** (`|R − b − g| > 45`): `sweep = b + g ± 45`. The radar spent its whole
  45° budget, and the only "extra" reach you get is whatever the body+gun happened
  to contribute.

### 1.5 The usable single-tick budget `B`

Define `B` as the maximum sweep achievable in the radar's intended direction:

```
B = 45° + (b + g)        when body & gun rotate WITH the radar  → up to  75°
B = 45°                  when body & gun are neutral            →        45°
B = 45° − |b + g|        when body & gun rotate AGAINST it      → down to 15°
```

With `g ≤ 20°` and `b ≤ 10°`, **B ∈ [15°, 75°]**. This is the single most important
number for the rest of this document: *the radar does not always have 45°.*

### 1.6 Scan detection is point-based

`scan()` fires a `ScannedRobotEvent` iff the opponent's **center point** `(x, y)` lies
inside the swept PIE arc (`RADAR_SCAN_RADIUS = 1200`, `RobotPeer.java:1616`). The
36×36 body does **not** help — covering "part of the robot" is not enough; the arc
must contain the center. So all margins below are computed against a point target.

### 1.7 Movement constants (for worst-case opponent motion)

- Max velocity `Vmax = 8` px/tick. Acceleration `+1`/tick, deceleration `−2`/tick.
- Next-tick displacement uses the **post-acceleration** velocity (move after turn).
- Worst-case reachable speed next tick: `min(8, v + 1)` accelerating,
  `max(−8, v − 2)` decelerating.

---

## 2. Worst-Case Combinations (opponent × us)

### 2.1 The quantity that matters: bearing rate `δ`

The radar must keep the opponent's **absolute bearing** inside the swept arc. Only
the component of relative velocity **perpendicular to the line of sight (LOS)**
moves the bearing. With `Δt = 1`:

```
δ  =  atan( v⊥,rel / d )      [degrees]
v⊥,rel = | v⊥,opp − v⊥,us |   (tangential components of each robot's velocity)
```

- **Relative heading = tangential** (velocity ⟂ LOS): full contribution. Worst case.
- **Relative heading = radial / head-on** (velocity ∥ LOS): `v⊥ = 0 ⇒ δ = 0`. Easiest.
- **Relative heading = 45°**: factor `sin 45° = 0.707`.

Each robot contributes up to `8` to `v⊥,rel`, so the **counter-orbit extreme is
`v⊥,rel = 16`** (both tangential, opposite directions). A single mover gives `8`.
Worst-case potential adds the `+1` accel headroom (capped at 8) — relevant only when
a robot is below top speed; at the `8 + 8 = 16` extreme both are already capped.

### 2.2 The two-sided overshoot constraint

The oscillating lock ends each tick at `target ± O` (O = overshoot) and must, next
tick, swing **back across** the target and out to the other side. Two requirements:

1. **Don't miss next tick** (lower bound): the overshoot must cover how far the
   target can move, or it escapes the arc → `O ≥ δ`.
2. **Don't under-scan the tick after** (upper bound): the recross turn `2O + δ` must
   fit the usable budget → `2O + δ ≤ B`.

Combined:

```
δ  ≤  O  ≤  (B − δ) / 2          ⇒  feasible  iff  3δ ≤ B   (i.e. δ ≤ B/3)
minimal overshoot  O* = δ        required recross = 3δ
```

**Solvability thresholds:** `δ ≤ 15°` (neutral B=45), `δ ≤ 5°` (opposing B=15),
`δ ≤ 25°` (aligned B=75).

### 2.3 Reference table — `δ = atan(v⊥,rel / d)`

| distance d | v⊥,rel = 16 (counter-orbit) | v⊥,rel = 8 (single mover) |
|-----------:|:---------------------------:|:-------------------------:|
| 36 (touching) | **23.96°** | 12.53° |
| 120 (close)   | 7.60°  | 3.81° |
| 400 (mid)     | 2.29°  | 1.15° |
| 1000 (far)    | 0.92°  | 0.46° |

Now the worst-case sub-combinations, each worked out:

---

**A. Touching · counter-orbit · neutral budget** — `d=36, v⊥=16, B=45`

`δ = atan(16/36) = 23.96°`. Threshold `B/3 = 15°`. Since `23.96 > 15`,
`3δ = 71.9° > 45°`. **UNSOLVABLE in one tick.** No overshoot value exists: covering
the motion needs `O ≥ 24°`, but the recross `2·24 + 24 = 72°` exceeds the 45° clamp.
A miss is physically unavoidable here with a neutral gun/body.

**B. Touching · counter-orbit · gun+body aligned** — `d=36, v⊥=16, B=75`

Same `δ = 23.96°`. Threshold `B/3 = 25°`. Now `23.96 ≤ 25`, so
`3δ = 71.9° ≤ 75°`. **SOLVABLE — but only by borrowing the gun+body rotation.**
`O* = 23.96°`, recross `= 71.9°`, which the engine delivers as `30 + 45 = 75°`
when body (10°) and gun (20°) both turn the radar's way. This is the single case
that flips from impossible to possible purely by *aligning* our own rotation.

**C. Touching · counter-orbit · gun+body opposing** — `d=36, v⊥=16, B=15`

`δ = 23.96°`, threshold `5°`. Wildly unsolvable (`71.9° ≫ 15°`). If our gun is
slewing the *wrong* way at point-blank range, the radar has essentially no chance.

**D. Close · counter-orbit · neutral** — `d=120, v⊥=16, B=45`

`δ = 7.60° ≤ 15°`. `3δ = 22.8° ≤ 45°`. **SOLVABLE.** `O* = 7.60°`, recross 22.8°,
with 22° of clamp headroom to spare. Today's fixed 22.5° overshoot actually
*over*-shoots here: `2·22.5 + 7.6 = 52.6° > 45°` → it **misses** despite being
geometrically solvable. (This is exactly the failure the Layer-0 data showed:
misses at radar-to-target ≈ ±22.5° with near-zero opponent motion.)

**E. Close · counter-orbit · gun+body opposing** — `d=120, v⊥=16, B=15`

`δ = 7.60°`, threshold `5°`. `7.60 > 5` → `3δ = 22.8° > 15°`. **UNSOLVABLE while the
gun opposes.** A merely *close* counter-orbiting opponent becomes unhittable for the
radar if our gun happens to track the other way — motivating a direction-aware algo.

**F. Mid · counter-orbit · gun+body opposing** — `d=400, v⊥=16, B=15`

`δ = 2.29° ≤ 5°`. `3δ = 6.9° ≤ 15°`. **SOLVABLE** even against an opposing gun.
`O* = 2.29°`. Beyond ~300 px essentially everything is solvable regardless of budget.

**G. Any distance · radial / head-on** — `v⊥ = 0`

`δ = 0` ⇒ trivially solvable at any budget. Here overshoot serves only re-acquire
margin, so `O` can stay near the far-field default without risk.

**H. Touching · single mover · neutral** — `d=36, v⊥=8, B=45`

`δ = 12.53° ≤ 15°`. `3δ = 37.6° ≤ 45°`. **SOLVABLE.** `O* = 12.53°`, recross 37.6°.
So even at touching range, if only *one* robot moves tangentially, a correctly-sized
(smaller) overshoot succeeds — the fixed 22.5° (`2·22.5 + 12.5 = 57.6°`) fails it.

### 2.4 Summary of chapter 2

- The **only genuinely unsolvable** neutral-budget case is **A**: touching range
  (`d ≲ 60`) with both robots counter-orbiting at full speed (`δ > 15°`).
- Many *solvable* cases are missed **today** purely because the fixed 22.5° overshoot
  exceeds the clamp budget (cases D and H). Sizing `O = δ` fixes those immediately.
- An **opposing** gun/body shrinks the solvable envelope dramatically (E: even close
  range fails). An **aligned** gun/body expands it enough to solve the touching
  worst case (B).

---

## 3. Opponent Near Walls / Corners

Walls **reduce** the worst cases of chapter 2 and give us room to overshoot *away*
from the wall — turning some otherwise-unsolvable touching cases into solvable ones.

### 3.1 Why a wall shrinks `δ`

A pinned robot's center is confined to `[18, W−18] × [18, H−18]`. Against a wall, the
velocity component **into** the wall is forced to ~0 (it bounces, losing speed). So
the opponent's reachable next positions form a **half-plane**, not a full disc.

The benefit depends on LOS orientation relative to the wall:

- **LOS ⟂ to the wall** (we look straight at a robot pinned on the far wall): the
  bearing-changing (tangential) direction runs *along* the wall, which is still
  fully free (`vx = ±8` along a top wall). **No reduction** — worst case unchanged.
- **LOS ∥ to the wall** (we are beside the opponent, both near the same wall): the
  tangential direction is now the *into/out-of* wall axis, half of which is blocked.
  The opponent can only swing the bearing toward open space. **`δ` toward the wall
  side ≈ 0; only the open side moves.**

### 3.2 Asymmetric overshoot near a wall

When the wall blocks one side, the recross no longer needs to be symmetric. Instead
of `2O + δ`, we need only:

```
sweep_needed ≈ O_open + δ_open      (wall side contributes ~0)
```

So we can put the full overshoot on the **open** side and almost none on the wall
side. For case **A** (touching, `δ = 23.96°`) pinned with LOS ∥ wall, the required
sweep drops from `71.9°` toward `~24–36°`, which **fits inside the neutral 45°
budget** — i.e. the wall converts the one unsolvable case into a solvable one,
*provided* we bias the overshoot away from the wall.

### 3.3 Corners

A corner pins **two** axes simultaneously → reachable positions become a
quarter-plane. For any LOS into the corner, at least one of the two
bearing-changing directions is blocked, so the corner case is **never harder** than
the open-field case and usually the easiest of all: maximal asymmetry, minimal
required sweep. The opponent simply has the fewest escape directions.

### 3.4 Wall caveat — reversals

The one thing walls *add* is the chance of a sharp **reversal** (the opponent decel
−2 then accel the other way along the wall). But `δ` is bounded by reachable speed,
not by sign, and our worst-case `v⊥ = 16` already assumes the opponent may be moving
either way. Reversals therefore do **not** raise `δ`; they are covered by the
existing worst case. The wall is a net positive for the radar.

---

## 4. Discussion of the Proposed Ideas

### 4.1 "Process the radar last, after body & gun decisions"

**Today** `Autopilot.act()` calls `setTurnRadarRightRadians(radar.getRadarTurn())`
**first** (`Autopilot.java:434`), *before* movement and gun. So `getRadarTurn()`
cannot see this tick's `b` and `g`, and must assume the neutral `B = 45°`.

By §1.4–1.5 the true budget is `B = 45 + (b + g)` (signed). Moving the radar call to
**after** the movement and gun commands lets `getRadarTurn()` read the committed
body-turn and gun-turn for this tick and compute the **exact** `B`. This is a
prerequisite for every other idea below — without it, cases B, C, E are
indistinguishable to the radar. **Strongly recommended; low risk** (pure reorder,
the radar command is still latched in the same `execute()`).

### 4.2 "Different algorithm when moving with vs. against the gun"

This is precisely the `B = 75` vs `B = 15` split (cases B vs C, and D vs E). With the
sign of `(b + g)` known (§4.1):

- **Aligned** (`b + g` same sign as the needed recross): budget up to 75° → use the
  larger feasible overshoot; the touching worst case becomes solvable (B).
- **Opposing**: budget as low as 15° → must *shrink* overshoot aggressively, and
  even then close-range counter-orbit (E) is unsolvable; switch to a damage-control
  mode (accept a 1-tick gap, keep the lead clean — see §5.5).

The algorithm can go further and **choose** the oscillation direction
(`lastTurnDirection`) to align with the gun's turn when that flips a case from
unsolvable to solvable.

### 4.3 "Consider opponent velocity and acceleration/deceleration potential"

Chapter 2 already encodes this: `δ` uses `v⊥,rel`, and the worst-case next-tick speed
is `min(8, v+1)`. Deriving the opponent's current `v⊥` from `OPPONENT_LATERAL_VELOCITY`
(and our own tangential contribution from `OUR_VELOCITY`/`OUR_HEADING`) and adding the
`+1` accel headroom gives a tight `δ_pred` instead of a fixed margin. Near a wall the
`+1` headroom into the wall is unavailable — §3 lets us drop it.

### 4.4 "As much overscan as needed to not miss next tick"

Lower bound `O ≥ δ` (§2.2). Anything less and the opponent's own motion carries its
center out of next tick's arc.

### 4.5 "As little overscan as needed to not under-scan the tick after"

Upper bound `2O + δ ≤ B` ⇒ `O ≤ (B − δ)/2` (§2.2). Over-shooting wastes budget and,
once `2O + δ > B`, the recross saturates and the radar falls short — the exact
mechanism behind the fixed-22.5° misses in cases D and H.

**Unified rule (the heart of the fix):**

```
δ ≤ O ≤ (B − δ)/2          minimal O* = δ          feasible iff 3δ ≤ B
```

---

## 5. Proposed Designs

Listed roughly in increasing effort; (1) and (2) capture the bulk of the gain.

### 5.1 Reorder: radar last

Move the `setTurnRadarRightRadians(...)` call to after movement + gun in
`Autopilot.act()`, and expose the committed `b` (body-turn-remaining clamped) and
`g` (gun-turn-remaining clamped) to the radar strategy. Enables exact `B`.

### 5.2 Adaptive overshoot `O = clamp(δ_pred, [δ_pred, (B − δ_pred)/2])`

Replace the constant `OVERSHOOT = 22.5°` with:

1. `δ_pred = atan( v⊥,rel,worst / d )`, `d` from `DISTANCE`, `v⊥,rel` from
   `OPPONENT_LATERAL_VELOCITY` + our tangential component, plus `+1` accel headroom.
2. `B = 45° + signed(b + g)` from §5.1.
3. If `3·δ_pred ≤ B`: set `O = δ_pred` (minimal, guaranteed recross). Optionally grow
   toward `(B − δ_pred)/2` for re-acquire margin when far/slow.
4. If `3·δ_pred > B`: unsolvable this tick (case A/C/E) → §5.5.

This alone fixes cases D and H (the bulk of measured interior misses) and keeps the
far-field behavior (≈22.5°) where `δ_pred → 0`.

### 5.3 Direction-aware alignment

When `3·δ_pred` fits only the *aligned* budget (case B), bias `lastTurnDirection`
and/or accept the gun's turn direction so `(b + g)` adds to the radar. This converts
the touching counter-orbit worst case from impossible to possible without any new
information — just by choosing which way to oscillate.

### 5.4 Wall-aware asymmetric overshoot

Detect the opponent pinned against a wall/corner (position near the 18 px inset) and,
when the LOS is roughly parallel to that wall, place the overshoot on the **open**
side only (`O_open = δ`, `O_wall ≈ 0`). Per §3.2 this makes the otherwise-unsolvable
touching case (A) fit the neutral 45° budget.

### 5.5 Damage control for the truly unsolvable cases (A/C/E)

When `3·δ_pred > B` even after alignment and wall-asymmetry (point-blank
counter-orbit with an opposing gun), a 1-tick miss is physically forced. The goal
shifts to **recovering in exactly one tick** instead of cascading:

- Keep the ring-walk lead **rate-correct** — divide the bearing delta by the actual
  tick gap between the two ring samples, so a stale `previousBearing` (2–3 ticks old)
  doesn't produce a 2–3× over-lead that turns a 1-tick gap into the 7-tick gaps the
  Layer-0 data showed.
- On the forced-miss tick, aim the next sweep at the **predicted** bearing (lead +
  worst-case `δ`) rather than spinning, so re-acquire happens on the very next tick.

### 5.6 Keep the fixed default only as a fallback

Retain `22.5°` purely for the `δ_pred → 0` / no-track re-acquire path, where the
two-sided constraint is slack and a wide symmetric sweep is the safest re-lock.

---

## 6. Quick Reference

```
sweep      = b + g + clamp(R − b − g, ±45°)
budget B   = 45° + signed(b + g)            ∈ [15°, 75°]   (g≤20, b≤10−0.75|v|)
δ          = atan(v⊥,rel / d)               v⊥,rel ≤ 16 (counter-orbit)
overshoot  δ ≤ O ≤ (B − δ)/2                O* = δ
feasible   3δ ≤ B   →  neutral δ≤15°, opposing δ≤5°, aligned δ≤25°
unsolvable d≲60 counter-orbit @ neutral/opposing budget (mitigate via §5.3–5.5)
```

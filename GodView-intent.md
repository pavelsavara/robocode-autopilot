# God-View Quality — Intent

> This document is self-contained. It defines **what** the god-view quality
> layers measure and **what** "good" means for each. It is the companion to
> `IDebugProperties-intent.md`: that document defines **Layer 0** (the pipeline
> correctness contract); this one defines **Layers 1–4** (the estimation-quality
> measurements that ride on top of a proven-faithful pipeline).

## 1. One-sentence intent

Because the in-game robot is **blindfolded by design** (it never has god-view),
the god-view quality layers measure **how accurately the robot's
partial-information estimates approximate engine ground truth**, and **where that
perception breaks down** — without ever mutating the state the robot is
validated against.

## 2. Two different questions, two validators

It is easy to conflate the two validators. They answer fundamentally different
questions:

| | Layer 0 (`Layer0DebugFidelityValidator`) | Layers 1–4 (`GodViewQualityValidator`) |
|---|---|---|
| **What vs what** | in-game robot vs observer shadow | god-view (engine truth) vs robot-side estimate |
| **Question** | "did the replay pipeline deliver the robot's exact experience?" | "how close is the robot's blindfolded guess to reality?" |
| **Required result** | **zero** mismatches (exact) | a **measured gap** — non-zero is expected |
| **Nature** | pipeline correctness check | estimation-quality measurement |

Layer 0 proves the observer **is** a faithful shadow. Layers 1–4 then use that
faithful shadow to characterize **how good the blindfolded robot's perception
actually is**. The quality numbers are only trustworthy because Layer 0 is zero.

## 3. The god-view whiteboard is read-only with respect to the robot

`GodViewQualityValidator` reads a **separate god-view whiteboard**, seeded from
the observer's robot-side whiteboard each tick and then overlaid by
`GodViewWaveResolver`. It **never mutates the observer's robot-side state**.

This is a hard rule inherited from `IDebugProperties-intent.md` §4: god-view must
never touch the whiteboard the observer is validated against. The god-view layers
are **diagnostic only** — they do not improve the robot directly. Their value is:

1. **Improving the observer/replay pipeline** — an unexpected divergence localizes
   a defect (event reconstruction, snapshot timing, or rules-math drift).
2. **Characterizing the robot's perception ceiling** — quantifying how good the
   blindfolded estimates can ever be, so a poor battle result can be attributed to
   *strategy* (accurate senses, wrong decisions) vs *perception* (the senses
   themselves are off).

## 4. The four layers

For every layer the comparison is **god-view value vs robot-side value**, on the
perspective where the live `Autopilot` actually fought.

### Layer 1 — Spatial & Kinematic Fidelity

- **What vs what:** the engine's true robot geometry (god-view) vs the robot's
  whiteboard estimate of the same spatial/kinematic features.
- **When:** self and static features are checked **every tick**;
  opponent-dependent features are checked **only on scan ticks** (the robot can
  only refresh its enemy estimate when radar paints it — the whiteboard is stale
  otherwise).
- **Why:** spatial state is the foundation everything downstream builds on. If the
  robot's notion of *where things are* drifts between scans, aim and waves inherit
  that error.
- **Reported as:** per-feature mismatch count + MAE. Pass target: **0 mismatches**.

**Features covered (20):** `OUR_X`, `OUR_Y`, `OUR_HEADING`, `OUR_VELOCITY`,
`OUR_ENERGY`, `GUN_HEADING`, `RADAR_HEADING`, `GUN_HEAT`,
`TICK`, `OPPONENT_X`, `OPPONENT_Y`, `OPPONENT_HEADING`,
`OPPONENT_VELOCITY`, `OPPONENT_ENERGY`, `DISTANCE`, `BEARING_RADIANS`,
`OPPONENT_LATERAL_VELOCITY`, `OPPONENT_ADVANCING_VELOCITY`, `LAST_SCAN_TICK`,
`TICKS_SINCE_SCAN`.

### Layer 2 — Damage-Observation Drift (autopilot perspective)

- **What vs what:** the **autopilot's running tally** of opponent-damage events
  this tick — exactly the values `FireFeatures.process` subtracts from the
  scan-to-scan opponent-energy drop before classifying it as fire — vs the
  **god-view ground truth** of the same four channels.
- **Why autopilot-only:** L2 measures *observation quality*, not engine model
  correctness. The opponent's perspective has nothing to observe with (it has
  no autopilot of ours); only the autopilot has a `FireFeatures`-style ledger
  that feeds Layer 3.
- **The four damage channels** (each compared independently):
  1. `OUR_BULLET_DMG → opp` — our bullets transitioning to `HIT_VICTIM` on the
     opponent (god-view value `4p + max(0, 2(p-1))`). Observed via
     `BulletHitEvent` on the autopilot.
  2. `OPP_BULLET_GAIN` — opponent's hit bonus `3p` when their bullet hits us
     (god-view: bullet transition to `HIT_VICTIM` on us). Observed via
     `HitByBulletEvent` and credited to the opponent.
  3. `RAM_DMG (on opp)` — `0.6` per tick of contact (god-view: either robot in
     `HIT_ROBOT`). Observed via `HitRobotEvent`.
  4. `OPP_WALL_DMG` — `max(|v|/2 - 1, 0)` on the opponent's transition into
     `HIT_WALL`, using the **pre-impact** velocity (god-view uses the previous
     tick's velocity, since the engine zeros velocity on impact). Observed via
     `WallHitEstimator` from a scanned `oppState == HIT_WALL`.
- **Why this matters:** Layer 3 phantoms (non-fires misclassified as fires) are
  exactly the energy drops L2's residual cannot subtract. If all four drifts
  collapse to zero on every tick, L3 phantoms must also collapse to zero — L2
  is the upper bound on L3's false-positive material.
- **Expected non-zero residual:** channel 4 has an **irreducible** drift —
  intra-tick impact velocity is not observable, the autopilot can only use the
  previously scanned velocity, and the opponent's wall hit may happen between
  scans. Channels 1–3 should drift to zero with correct event plumbing; any
  non-zero is a real observation bug.
- **Reported as:** per-channel `gv`/`obs` totals, event counts, absolute drift,
  and the number of ticks each channel diverged. Also prints `mismatchTicks`
  (any-channel-divergent ticks) and `totalAbsDrift`.
- **Engine-rule self-test (footer):** the previous L2 ledger (predicted vs
  actual engine energy via static rule helpers) is retained as an internal
  sanity check that *our* implementation of the engine's rules is correct. It
  reports `checks` and `discrepancies` per perspective. Discrepancies here mean
  the validator's engine model is wrong; they say nothing about autopilot
  observation quality.

### Layer 3 — Incoming-Fire Detection Fidelity

- **What vs what:** the **autopilot's** inference "the opponent fired at tick T
  with power P from position (x, y)" vs the engine ground truth from the
  opponent's outgoing bullet. Measured **only for the autopilot perspective** —
  the other live robot may be any third-party bot, so this layer is silent for it.
- **Single perspective by design:** OUR outgoing fire is trivially exact (the
  autopilot owns the bullet and gets the id back from `setFireBullet`, so there
  is nothing left to infer or measure). All aiming-decision attribution belongs
  to whatever targeting model decides — not to this fidelity layer. Layer 3 is
  scoped tightly to the one thing the blindfolded autopilot must *reconstruct*:
  the enemy's shot.
- **Pairing:** by **fire tick**. The autopilot never sees the incoming bullet,
  so it has no id; the fire tick is the only shared key. Pairing is buffered so
  arrival order between god-view and autopilot streams does not matter.
- **Timing model (engine-validated):** an enemy shot fired at tick `T` only
  becomes visible one tick later — the bullet is created at `T+1` from the body
  position at the **end of `T`**, and the energy drop is likewise observed at
  `T+1`. So the true fire tick is `T` and the true muzzle is the body position at
  end of `T`. God-view uses `detectionTick − 1`; the autopilot uses
  `energyDropTick − 1` with the **previous-tick** enemy position.
- **Result:** **timing is exact** (`latency = 0`) for every opponent. Origin and
  power are exact **only in the limit of perfect per-tick tracking** — proven
  against a cleanly-tracked stationary shooter (`sample.Fire`:
  `positionMAE = 0`, `powerMAE = 0`). Against evasive opponents they degrade
  (e.g. `positionMAE` 2–60 px, `powerMAE` 0–0.08), because the autopilot must
  reconstruct the enemy muzzle from its **last radar scan** (stale when the
  radar isn't locked every tick) and infer power from the **energy drop** (which
  wall/ram damage can mis-attribute, also inflating the detection count). These
  are tracking/attribution limits, not the wave model.
- **The one irreducible unknown:** even with perfect tracking the blindfolded
  autopilot cannot recover the **muzzle angle** of an incoming shot — it must
  assume a head-on bearing, whereas the real bullet carries the enemy's lead.
  `angleMAE` (radians) isolates exactly this gap; it is the single fire-time
  fact no amount of energy/position inference can reveal.
- **Metrics (autopilot perspective only):**
  - `fireDetectionRate = robotSideFires / godViewFires` — did it catch every
    shot? (Can exceed 1.0 when non-fire energy drops are mis-attributed as
    enemy fire.)
  - `positionMAE` — muzzle-origin error (px); 0 under clean tracking.
  - `powerMAE` — inferred bullet-power error; 0 under clean tracking.
  - detection **latency** — ticks late; 0.
  - `angleMAE` — the irreducible muzzle-angle unknown (head-on assumption vs
    the bullet's true flight heading). Always non-zero by design.
- **Why:** wave dodging depends on knowing *where*, *when* and *with what
  power* the enemy fired. Timing is exact and, for a well-tracked target, so
  are origin and power — so the residual modelling uncertainty collapses to
  the muzzle angle, with radar staleness and energy mis-attribution as the
  only other (non-model) error sources.

### Layer 4 — Wave Resolution & GuessFactor Precision

- **What vs what:** god-view wave outcomes (the geometrically-correct break tick
  and GuessFactor once the wave truly resolves) vs the robot's robot-side
  GuessFactor/wave resolution.
- **Metrics:** `waveMatchRate = robotSideResolutions / godViewResolutions`, GF
  error (mean + max), and **break-tick** MAE.
- **Why:** GuessFactor is the heart of statistical targeting/dodging. This layer
  quantifies how much the robot's blindfolded GF prediction diverges from the
  correct answer.

## 5. Feature coverage — what Layers 1–4 do NOT cover

Layer 0 covers **all** published features with **zero exclusions**. Layers 1–4
are a curated god-view subset — they only touch features where a god-view
comparison is meaningful. The following features are validated **only by Layer 0**
(no direct Layer 1–4 comparison):

| Category | Features |
|----------|----------|
| Gun aim / decision | `GUN_AIM_POWER`, `GUN_AIM_ANGLE`, `GUN_AIM_GF` |
| Derived spatial | `OPPONENT_BEARING_ABSOLUTE` |
| Energy accumulators (inputs to L2 logic, not validated as features) | `OUR_BULLET_DAMAGE_TO_OPPONENT`, `OPPONENT_BULLET_ENERGY_GAIN`, `RAM_DAMAGE_TO_OPPONENT`, `OPPONENT_WALL_HIT_DAMAGE`, `PREV_SCAN_OPPONENT_ENERGY` |
| Their-wave detail (L3 pairs power + position only) | `THEIR_FIRE_BEARING`, `THEIR_FIRE_DISTANCE`, `THEIR_FIRE_OUR_X`, `THEIR_FIRE_OUR_Y`, `THEIR_BULLET_SPEED`, `THEIR_BREAK_OUR_X`, `THEIR_BREAK_OUR_Y`, `THEIR_BREAK_BEARING_OFFSET`, `THEIR_HIT_US` |
| Our-wave fire-time (L4 compares GF + break tick only) | all 15 `OUR_FIRE_*` |
| Our-wave break-time | `OUR_BREAK_BEARING_OFFSET`, `OUR_BREAK_OPPONENT_X`, `OUR_BREAK_OPPONENT_Y`, `OUR_BREAK_HIT` |
| Identity | `OPPONENT_ID`, `OPPONENT_ID_HASH` |
| Round result | `ROUND_HIT_RATE`, `ROUND_RESULT` |

So Layers 1–4 form a ~30-feature god-view slice; the remaining ~40 features are
guarded exclusively by Layer 0's zero-mismatch contract.

## 6. What "good" means per layer

The layers are two different *kinds* of check:

- **Exact-match layers (0 and 1):** the only acceptable result is **0 mismatches**
  on every covered feature, every tick, every round. Any non-zero is a defect.
- **Estimation-quality layers (2, 3, 4):** the metric is an **error magnitude**,
  not pass/fail. A non-zero gap is **expected and informative** — it measures the
  blindfolded robot's perception, which by the rules of the game cannot be perfect.

The only features that are legitimately "not 100%" are the god-view estimation
metrics in Layers 2–4 — by design, because the robot cannot have god-view.

## 7. Acceptable residuals (not defects)

These gaps are inherent observability limits, documented so they are **not chased**:

- **Layer 2 wall impact:** the intra-tick velocity at the instant of wall contact
  is unobservable from snapshots. `prevVelocity` is the neutral zero-information
  prior; a heuristic that "corrects" it was tried and reverted because it fixed
  accelerating impacts but broke constant-velocity/braking impacts.
- **Layer 2 death-tick collisions:** simultaneous collisions on an opponent's
  death tick can leave a single-tick energy residual.
- **Layer 3/4 boundary waves:** the last wave of a round can be off-by-one between
  god-view and robot-side resolution timing.

## 8. Reference results (seed 123456789, 10 rounds/opponent, perspective 0)

| Opponent | L1 Spatial | L2 Energy | L3 Fire rate / powerMAE / posMAE | L4 GF MAE / max |
|----------|-----------|-----------|-----------------------------------|-----------------|
| test.SittingDuck | 0 mismatches | 100.0% | 1.000 / 0.05 / 41 px | 0.000 / 0.000 |
| test.Aggressive | 0 mismatches | 100.0% | 1.000 / 0.21 / 117 px | — |
| sample.Fire | 0 mismatches | 100.0% | 1.000 / 0.07 / 30 px | — |
| sample.Walls | 0 mismatches | 100.0% | 1.000 / 0.27 / 35 px | 0.27 / 1.62 |
| sample.Crazy | 0 mismatches | 99.8% | 1.000 / 0.30 / 47 px | 0.83 / 1.87 |
| kc.mega.BeepBoop | 0 mismatches | 99.7% | 1.008 / 0.02 / 73 px | 0.56 / 1.98 |

Estimation quality degrades predictably with target unpredictability: a
stationary target (SittingDuck) yields perfect GF; erratic movers (Crazy,
BeepBoop) yield the largest — but still bounded (< 2.0) — GF error. Layers 0 and 1
are exact for every opponent.

## 9. Acceptance criteria

The god-view quality layers meet this intent when, for the perspective where the
`Autopilot` fought:

1. **Layer 1: zero mismatches** across all spatial/kinematic features, on every
   checked tick (every tick for self/static; every scan tick for opponent).
2. **Layers 2–4: estimation error is reported, bounded, and stable** — fire
   detection rate ≈ 1.000, GF max error bounded, energy ≥ 99.7% — with any
   remaining gap attributable to the inherent observability limits in §7, not to a
   pipeline or rules-math defect.
3. The god-view whiteboard **never mutates** the robot-side state validated by
   Layer 0.

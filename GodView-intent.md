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

**Features covered (22):** `OUR_X`, `OUR_Y`, `OUR_HEADING`, `OUR_VELOCITY`,
`OUR_ENERGY`, `GUN_HEADING`, `RADAR_HEADING`, `GUN_HEAT`, `BATTLEFIELD_WIDTH`,
`BATTLEFIELD_HEIGHT`, `TICK`, `OPPONENT_X`, `OPPONENT_Y`, `OPPONENT_HEADING`,
`OPPONENT_VELOCITY`, `OPPONENT_ENERGY`, `DISTANCE`, `BEARING_RADIANS`,
`OPPONENT_LATERAL_VELOCITY`, `OPPONENT_ADVANCING_VELOCITY`, `LAST_SCAN_TICK`,
`TICKS_SINCE_SCAN`.

### Layer 2 — Fire Detection Fidelity

- **What vs what:** the fire events the engine actually produced (god-view, from
  `IBulletSnapshot` FIRED state) vs the fire events the robot **inferred** from an
  enemy energy drop. The robot cannot see bullets spawn — it deduces "enemy fired"
  from a sudden energy decrease.
- **Pairing:** robot-side detections are paired FIFO with the oldest unmatched
  god-view fire; errors are computed at pairing time.
- **Metrics:**
  - `fireDetectionRate = robotSideFires / godViewFires` — did it catch every shot?
  - `positionMAE` — how far off the assumed muzzle position was (px).
  - `powerMAE` — how wrong the inferred bullet power was.
  - detection **latency** — how many ticks late the detection arrived.
- **Why:** wave targeting/dodging depends on knowing *when* and *with what power*
  the enemy fired. A missed or mis-powered detection corrupts every wave spawned
  from it.

### Layer 3 — Wave Resolution & GuessFactor Precision

- **What vs what:** god-view wave outcomes (the geometrically-correct break tick
  and GuessFactor once the wave truly resolves) vs the robot's robot-side
  GuessFactor/wave resolution.
- **Metrics:** `waveMatchRate = robotSideResolutions / godViewResolutions`, GF
  error (mean + max), and **break-tick** MAE.
- **Why:** GuessFactor is the heart of statistical targeting/dodging. This layer
  quantifies how much the robot's blindfolded GF prediction diverges from the
  correct answer.

### Layer 4 — Energy Accounting

- **What vs what:** the robot's **predicted** energy after applying Robocode's
  energy rules, tick by tick, vs the engine's **actual** reported energy.
- **Rules applied** (static helpers in `GodViewQualityValidator`):
  - fire cost charged once per bullet id, on first observation in a pre-impact
    state (`FIRED` or `MOVING`);
  - hit bonus `3 * power` credited once per bullet id on `HIT_VICTIM`;
  - bullet damage taken `4*power + max(0, 2*(power-1))` debited once per id;
  - wall damage `max(|v|/2 - 1, 0)` on the `HIT_WALL` transition;
  - ram damage `0.6` per tick of contact, bilateral.
- **Bullet-id lifecycle:** snapshot states **linger** for several ticks (explosion
  animation, pinned-to-wall), so each energy event is applied **exactly once per
  bullet id**; ids are per-round sequential and cleared in `resetRound()`.
- **Why:** energy is the ground-truth ledger that **Layer 2 fire detection** is
  measured against — the robot infers enemy fire by attributing energy drops, so a
  wrong ledger mis-attributes wall/ram/bullet hits as "enemy fired."
- **Reported as:** energy checks vs discrepancies. Residuals are inherent
  observability limits (intra-tick wall-impact speed is unobservable; `prevVelocity`
  is the neutral zero-information prior — see §7).

## 5. Feature coverage — what Layers 1–4 do NOT cover

Layer 0 covers **all** published features with **zero exclusions**. Layers 1–4
are a curated god-view subset — they only touch features where a god-view
comparison is meaningful. The following features are validated **only by Layer 0**
(no direct Layer 1–4 comparison):

| Category | Features |
|----------|----------|
| Gun aim / decision | `GUN_AIM_POWER`, `GUN_AIM_ANGLE`, `GUN_AIM_GF` |
| Derived spatial | `OPPONENT_BEARING_ABSOLUTE` |
| Energy accumulators (inputs to L4 logic, not validated as features) | `OUR_BULLET_DAMAGE_TO_OPPONENT`, `OPPONENT_BULLET_ENERGY_GAIN`, `RAM_DAMAGE_TO_OPPONENT`, `OPPONENT_RAM_ENERGY_GAIN`, `OPPONENT_WALL_HIT_DAMAGE`, `PREV_SCAN_OPPONENT_ENERGY` |
| Their-wave detail (L2 pairs power + position only) | `THEIR_FIRE_BEARING`, `THEIR_FIRE_DISTANCE`, `THEIR_FIRE_OUR_X`, `THEIR_FIRE_OUR_Y`, `THEIR_BULLET_SPEED`, `THEIR_BREAK_OUR_X`, `THEIR_BREAK_OUR_Y`, `THEIR_BREAK_BEARING_OFFSET`, `THEIR_HIT_US` |
| Our-wave fire-time (L3 compares GF + break tick only) | all 15 `OUR_FIRE_*` |
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

- **Layer 4 wall impact:** the intra-tick velocity at the instant of wall contact
  is unobservable from snapshots. `prevVelocity` is the neutral zero-information
  prior; a heuristic that "corrects" it was tried and reverted because it fixed
  accelerating impacts but broke constant-velocity/braking impacts.
- **Layer 4 death-tick collisions:** simultaneous collisions on an opponent's
  death tick can leave a single-tick energy residual.
- **Layer 2/3 boundary waves:** the last wave of a round can be off-by-one between
  god-view and robot-side resolution timing.

## 8. Reference results (seed 123456789, 10 rounds/opponent, perspective 0)

| Opponent | L1 Spatial | L2 Fire rate / powerMAE / posMAE | L3 GF MAE / max | L4 Energy |
|----------|-----------|-----------------------------------|-----------------|-----------|
| test.SittingDuck | 0 mismatches | 1.000 / 0.05 / 41 px | 0.000 / 0.000 | 100.0% |
| test.Aggressive | 0 mismatches | 1.000 / 0.21 / 117 px | — | 100.0% |
| sample.Fire | 0 mismatches | 1.000 / 0.07 / 30 px | — | 100.0% |
| sample.Walls | 0 mismatches | 1.000 / 0.27 / 35 px | 0.27 / 1.62 | 100.0% |
| sample.Crazy | 0 mismatches | 1.000 / 0.30 / 47 px | 0.83 / 1.87 | 99.8% |
| kc.mega.BeepBoop | 0 mismatches | 1.008 / 0.02 / 73 px | 0.56 / 1.98 | 99.7% |

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

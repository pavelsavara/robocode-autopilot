# Robot Architecture — Autopilot Decision System

*2026-05-03 — Phase 1: Smoke-Test Skeleton with Trivial Predictors*

## Goal

Wire the competition robot's decision-making layer: predictors → strategy
→ gun/movement/radar commands. Phase 1 uses trivial (random / constant)
predictor implementations behind real interfaces so the architecture can
be validated before distilled ML models are plugged in.

---

## 1. Predictor Inventory

Six predictors from Step 3, each with a defined output type and natural
confidence signal.

| ID | Predictor | Output | Confidence signal | Cadence |
|---|---|---|---|---|
| P1 | **Fire Power** | `float` ∈ [0.1, 3.0] | Leaf variance across trees | On scan (when `opponent_fired`) |
| P2 | **Round Outcome** | `float` ∈ [0, 1] (P(winning)) | Inherent — distance from 0.5 | Every N ticks (window-based) |
| P3 | **Bot Fingerprint** | `int` class + `float[]` probabilities | Max probability / entropy | After N opponent fires |
| P4 | **GF Targeting** | `float[61]` GF distribution | Distribution peakedness (1 − normalized entropy) | On scan |
| P5 | **Movement** | `float` predicted lat-vel at t+N | Leaf variance or |prediction − current| | On scan (window-based) |
| P6 | **Fire Timing** | `float` ∈ [0, 1] (P(fires in 3 ticks)) | Inherent — distance from 0.5 | On scan (window-based) |

### Phase 1 trivial implementations

Every predictor interface gets a concrete implementation that returns
random or constant values:

| Predictor | Phase 1 output | Confidence |
|---|---|---|
| P1 Fire Power | `1.0 + random.nextDouble() * 2.0` | 0.5 (constant) |
| P2 Round Outcome | 0.5 (always "uncertain") | 0.0 |
| P3 Fingerprint | class 0, uniform probabilities | 1/44 |
| P4 GF Targeting | uniform distribution (1/61 each) | 0.0 |
| P5 Movement | current `opponent_lateral_velocity` (persistence) | 0.5 |
| P6 Fire Timing | 0.07 (base rate from data) | 0.0 |

---

## 2. Predictor Interface — Hybrid Design

Two interfaces matching two output shapes:

### 2a. Scalar predictors → Whiteboard-native (Feature enum)

Scalar predictors (`float` output) register as `IInGameFeatures` and
write to `Feature` enum slots. They participate in the existing
Transformer dependency chain. The consumer reads via
`wb.getFeature(Feature.PREDICTED_*)`.

```java
// Fits the existing IInGameFeatures contract
public final class FirePowerPredictor implements IInGameFeatures {
    @Override
    public Feature[] getOutputFeatures() {
        return new Feature[]{
            Feature.PREDICTED_FIRE_POWER,
            Feature.PREDICTED_FIRE_POWER_CONFIDENCE
        };
    }
    @Override
    public Feature[] getDependencies() {
        return new Feature[]{Feature.OPPONENT_ENERGY, Feature.ENERGY_RATIO, ...};
    }
    @Override
    public void process(Whiteboard wb) {
        // Phase 1: trivial
        double power = 1.0 + Math.random() * 2.0;
        wb.setFeature(Feature.PREDICTED_FIRE_POWER, power);
        wb.setFeature(Feature.PREDICTED_FIRE_POWER_CONFIDENCE, 0.5);
    }
}
```

**Applies to:** P1 (fire power), P2 (round outcome), P5 (movement), P6
(fire timing). Each writes a prediction + confidence scalar to the Feature
array.

**New Feature enum entries needed:**
- `PREDICTED_FIRE_POWER`, `PREDICTED_FIRE_POWER_CONFIDENCE`
- `PREDICTED_WIN_PROBABILITY`, `PREDICTED_WIN_PROBABILITY_CONFIDENCE`
- `PREDICTED_LAT_VEL_5`, `PREDICTED_LAT_VEL_5_CONFIDENCE`
- `PREDICTED_OPPONENT_FIRES_3`, `PREDICTED_OPPONENT_FIRES_3_CONFIDENCE`

### 2b. Distribution predictors → IPredictor\<T\>

Complex outputs (arrays, class distributions) use a separate interface
stored in a `PredictorRegistry` on the Whiteboard.

```java
public interface IPredictor<T> {
    /** Compute and return prediction. Lazy — only called when consumed. */
    T predict(Whiteboard wb);

    /** Confidence in [0, 1]. 0 = "I have no idea", 1 = "certain". */
    double confidence(Whiteboard wb);

    /** Human-readable name for logging / virtual-gun tracking. */
    String name();
}
```

```java
public final class PredictorRegistry {
    private final Map<Class<?>, IPredictor<?>> predictors = new HashMap<>();

    public <T> void register(Class<? extends IPredictor<T>> key, IPredictor<T> impl) { ... }
    public <T> IPredictor<T> get(Class<? extends IPredictor<T>> key) { ... }
}
```

**Applies to:** P3 (fingerprint → `FingerprintResult`), P4 (GF targeting
→ `double[61]`).

```java
// Example: GF targeting predictor
public interface IGfTargetingPredictor extends IPredictor<double[]> {}

public final class TrivialGfTargeting implements IGfTargetingPredictor {
    @Override
    public double[] predict(Whiteboard wb) {
        double[] uniform = new double[61];
        Arrays.fill(uniform, 1.0 / 61);
        return uniform;
    }
    @Override
    public double confidence(Whiteboard wb) { return 0.0; }
    @Override
    public String name() { return "trivial-uniform"; }
}
```

### 2c. Reactive / pull semantics

Predictors are lazy by default. The Transformer does NOT call them
automatically. Instead:

1. **Feature-based predictors** (2a) run inside the Transformer chain on
   scan ticks — same as existing features. They are "reactive" in the
   sense that they only fire when their dependencies are freshly computed.

2. **Distribution predictors** (2b) are called on-demand by the consumer
   (e.g., the VirtualGunManager calls `gfPredictor.predict(wb)` when it
   needs to aim). If the consumer doesn't call, the predictor doesn't run.

3. **Staleness**: Predictions written to the Feature array persist until
   overwritten on the next scan. Consumers check
   `wb.getFeature(Feature.TICKS_SINCE_SCAN)` to know if data is fresh.
   Distribution predictors can cache internally and invalidate on
   `wb.getTick()` change.

---

## 3. Three Decision Architectures (Explored)

### 3a. Layered Priority (DrussGT-style)

```
Layer 3 (highest): Emergency wall avoidance
Layer 2: Wave-surf dodge (triggered by fire-timing predictor)
Layer 1: Orbital movement at preferred distance
Layer 0 (lowest): Default forward
```

Each layer either produces a movement command or defers to the layer below.
Simple, debuggable, but rigid — adding a new concern means inserting a new
layer and getting the priority order right.

**Pros:** Battle-tested in DrussGT (top-5 all-time). Easy to reason about.
Phase 1 is trivial to implement.

**Cons:** No smooth blending between concerns. Difficult to express "I want
to dodge AND maintain distance simultaneously." Priority ordering is
fragile — the wrong order silently degrades performance.

### 3b. Danger-Weighted Evaluation (Diamond-style)

```
Generate ~20 candidate positions (orbit left/right/ahead/behind at 3 radii).
For each candidate, compute:
  danger = w₁·bullet_proximity + w₂·wall_risk + w₃·distance_penalty
           + w₄·fire_timing_danger + w₅·energy_exposure
Pick candidate with lowest total danger.
```

**Pros:** Naturally handles multiple concerns simultaneously. Weights are
tunable (eventually learnable). Diamond-class performance.

**Cons:** Candidate generation is design-critical — if the best position
isn't in the candidate set, it can't be chosen. Weight tuning is a
hyper-parameter search problem. Harder to debug ("why did it pick *that*
position?").

### 3c. Blackboard + Arbiter

```
Predictors write to shared state (Whiteboard):
  - FireTimingPredictor: "dodge urgency = 0.8"
  - MovementPredictor: "opponent will be at lat-vel +4.2 in 5 ticks"
  - RoundOutcome: "win probability = 0.3 → aggressive mode"

Arbiter reads all signals, resolves conflicts:
  if dodge_urgency > 0.6:
      execute dodge maneuver
  else:
      execute strategic movement at preferred distance
```

**Pros:** Most modular — predictors and consumers are fully decoupled.
Easy to add/remove predictors without touching other components. Natural
fit for the Whiteboard architecture already in place.

**Cons:** The arbiter's conflict-resolution rules ARE the decision logic —
they can become as complex as the layered system. Risk of "arbiter god
object."

### Recommendation: 3c for the skeleton, moving toward 3b

Phase 1 uses 3c (Blackboard + Arbiter) because it matches the existing
Whiteboard pattern and is the most modular for swapping trivial → real
predictors. The arbiter starts simple (if/else priority rules).

As the system matures, the arbiter evolves toward 3b (danger evaluation)
for movement — generating candidate positions and scoring them with
predictor outputs. The gun already has its own selection mechanism
(virtual guns, §4).

---

## 4. Gun Subsystem — Virtual Guns with Confidence

### Architecture

```
┌────────────────────────────────────────────┐
│              VirtualGunManager             │
│                                            │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐      │
│  │ HeadOn  │ │ Linear  │ │RandomGF │ ...   │  ← IGunStrategy impls
│  └────┬────┘ └────┬────┘ └────┬────┘       │
│       │           │           │             │
│  Virtual bullets tracked per strategy       │
│  Hit/miss tracked per strategy              │
│                                            │
│  Selection: best hit-rate × confidence      │
│  Confidence breaks ties, hit-rate dominates │
└────────────────────────────────────────────┘
```

### IGunStrategy interface

```java
public interface IGunStrategy {
    /**
     * Compute the absolute gun angle to fire at.
     * Called every scan tick for every registered strategy.
     */
    double getFireAngle(Whiteboard wb);

    /**
     * Confidence in this tick's prediction, [0, 1].
     * Used as tiebreaker when hit rates are close.
     */
    double getConfidence(Whiteboard wb);

    /** Human-readable name for logging. */
    String getName();
}
```

### IFirePlan — multi-shot sequencing

Extends single-shot `IGunStrategy` to support wave-stacking: fire a
slow bullet followed by a fast bullet so they arrive near-simultaneously.

```java
/**
 * A fire plan is a sequence of one or more shots, each with a power
 * and aiming strategy. The VirtualGunManager asks for the next shot
 * when the gun is cool; the plan tracks its own state.
 */
public interface IFirePlan {
    /** Is this plan still active (has more shots to fire)? */
    boolean hasNextShot();

    /** Power for the next shot. */
    double getNextPower();

    /** Absolute gun angle for the next shot (targets may differ per shot). */
    double getNextAngle(Whiteboard wb);

    /** Called after each shot fires to advance internal state. */
    void onShotFired();

    /** Reset for a new firing cycle. */
    void reset();

    /** Human-readable name. */
    String getName();
}
```

**Phase 1 plans:**

| Plan | Shots | Behavior |
|---|---|---|
| **SingleShot** | 1 | Wraps any `IGunStrategy`. Power from `StrategyParams`. |
| **WaveStack** | 2 | Shot 1: power 3.0 (speed 11). Shot 2: power 0.1 (speed 19.7). Each aimed by the current best `IGunStrategy` at its respective fire tick. |
| **TripleStack** | 3 | Shot 1: power 3.0 (speed 11). Shot 2: power ~1.85 (speed ~14.45). Shot 3: power 0.1 (speed 19.7). Bullets 1+2 converge at ~400–500 px; bullet 3 arrives ~8 ticks later as a chaser. |

The `SingleShot` plan is a trivial adapter: `hasNextShot()` returns true
once, delegates angle to the wrapped `IGunStrategy`, uses
`StrategyParams.firePowerBudget` for power. This preserves backward
compatibility — existing gun strategies work unchanged.

The `WaveStack` plan fires shot 1 at power 3.0, waits 16 ticks for gun
cool, then fires shot 2 at power 0.1. The two bullets converge at
~400 px. Both shots are aimed independently by the current best gun
strategy at their respective fire ticks.

The `TripleStack` plan fires three shots: heavy (3.0), medium (~1.85),
light (0.1). Heavy+medium converge at ~400–500 px (the "2+1 pattern");
the light chaser arrives ~8 ticks later — before the opponent finishes
dodging the pair. Each shot aimed independently at its fire tick. Full
3-bullet exact convergence requires ~740 px (near battlefield diagonal)
and is only attempted at long range.

### VirtualGunManager

Responsibilities:
1. **Every scan tick**: Call `getFireAngle()` on all strategies. Record a
   virtual bullet per strategy (start position, angle, speed).
2. **Every tick**: Advance all virtual bullets. Check if any passed through
   the opponent's bounding box (36×36 px). Record hit/miss.
3. **Fire decision**: When gun heat = 0 and gun is aimed:
   - Select the strategy with the highest rolling hit rate (last 100 waves).
   - If hit rates are within ε (0.02), prefer the strategy with higher
     `getConfidence()`.
   - Fire at the selected strategy's angle.
   - When `StrategyParams.useWaveStacking` is true, delegate to the active
     `IFirePlan` instead of firing single shots. The plan controls power
     and may skip a fire opportunity if waiting for the right moment.
4. **Fire power**: Determined by the strategic layer (§6) for single shots,
   or by the `IFirePlan` for wave-stacking sequences.
5. **Wave-stack tracking**: Track paired virtual bullets separately.
   Score hit rate for "paired shots" vs "single shots" to detect whether
   the opponent is weak against wave stacking.

### Phase 1 strategies

| Strategy | `getFireAngle()` | `getConfidence()` |
|---|---|---|
| **HeadOn** | `wb.getFeature(BEARING_TO_OPPONENT_ABS)` | 1.0 (always certain) |
| **Linear** | `wb.getFeature(LINEAR_TARGET_ANGLE)` | 0.7 |
| **Circular** | `wb.getFeature(CIRCULAR_TARGET_ANGLE)` | 0.8 |
| **RandomGF** | Random GF × MEA + bearing | 0.1 |

**Later (distilled):**

| Strategy | `getFireAngle()` | `getConfidence()` |
|---|---|---|
| **MLP-GF** | Peak of 61-bin distribution × MEA + bearing | `1 - normalizedEntropy(dist)` |

### Why strategies don't vote or average

Each gun strategy represents a different *model of opponent movement*.
Head-on assumes no movement. Linear assumes constant velocity. MLP-GF
assumes learned patterns. These models are contradictory — you can't
meaningfully average "the opponent is standing still" with "the opponent
is moving left at 8 px/tick." Instead, we let reality decide: whichever
model's predictions most often match where the opponent actually was, that
model aims the gun.

This is the [virtual guns](https://robowiki.net/wiki/Virtual_Guns) pattern
from competitive Robocode, used by DrussGT, Diamond, and most top-10 bots.

---

## 5. Movement Subsystem — Competing Strategies

### Architecture

Parallel to virtual guns: multiple movement strategies compete, each
evaluated on a fitness metric.

```
┌────────────────────────────────────────────┐
│          MovementStrategyManager           │
│                                            │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐   │
│  │ Orbital  │ │ WaveSurf │ │ RandomDodge│  │  ← IMovementStrategy impls
│  └────┬─────┘ └────┬─────┘ └────┬──────┘  │
│       │            │            │          │
│  Shadow-execution: simulate each strategy  │
│  Fitness tracked per strategy              │
│                                            │
│  Active strategy = best fitness            │
└────────────────────────────────────────────┘
```

### IMovementStrategy interface

```java
public interface IMovementStrategy {
    /**
     * Compute the movement command for this tick.
     * The manager calls all strategies but only executes the active one.
     */
    MovementCommand getCommand(Whiteboard wb, StrategyParams params);

    /** Human-readable name. */
    String getName();
}

/** Value object — what the robot should do this tick. */
public final class MovementCommand {
    public final double ahead;       // pixels forward (negative = reverse)
    public final double turnRight;   // radians to turn body

    public MovementCommand(double ahead, double turnRight) {
        this.ahead = ahead;
        this.turnRight = turnRight;
    }
}

/** Strategic parameters from the mode layer (§6). */
public final class StrategyParams {
    public final double preferredDistance;  // pixels
    public final double aggression;        // [0, 1]: 0=defensive, 1=aggressive
    public final double firePowerBudget;   // [0.1, 3.0]
    public final boolean useWaveStacking;  // fire slow+fast pairs
}
```

### Fitness metric — discussion

The fitness metric for competing movement strategies is harder than for
guns. For guns, we can simulate virtual bullets and check hits. For
movement, the question is: "how well did this strategy avoid damage?"

**Candidate metrics:**

| Metric | Measurement | Pros | Cons |
|---|---|---|---|
| **Wave-surf danger** | For each opponent wave, compute closest approach distance if we had followed this strategy | Gold standard for dodge quality. Used by Diamond. | Requires full wave simulation per strategy per tick — expensive |
| **Damage taken per tick** | Rolling average of `damage_received / ticks_alive` | Simple, direct | Slow feedback (50+ ticks to converge), punishes unlucky hits |
| **Bullet proximity** | Average distance of opponent bullets at closest approach | Measures dodge quality without binary hit/miss | Requires tracking all opponent bullets |
| **Energy efficiency** | `(damage_dealt − damage_received) / ticks` | Captures both offense and defense | Conflates movement with gun quality |
| **Survival** | Ticks alive this round | Ultimate metric | Only available at round end |

**Practical concern:** Unlike virtual guns where we fire a virtual bullet
and check it later, we can't truly "shadow-run" a movement strategy we're
not executing — the opponent would react differently. So the fitness of
non-active strategies is necessarily hypothetical.

**Three practical approaches:**

1. **Wave-pass danger scoring** (Diamond-style): When an opponent wave
   passes our position, score how close we were to the projected impact
   angle. The active strategy gets this score. Non-active strategies get
   nothing — we switch based on periodic re-evaluation (every round or
   every N ticks) rather than continuous tracking.

2. **Round-level switching**: Each round, pick a strategy. At round end,
   compare damage taken. Switch to the best-performing strategy for the
   next round. Simple but very slow learning (only ~10-35 rounds per
   battle).

3. **Hybrid**: Use wave-pass danger for intra-round micro-switching,
   round-level survival for macro-switching. The active strategy is scored
   per-wave; when its cumulative score degrades below a threshold, switch
   to the next candidate. At round boundaries, re-evaluate with full
   survival data.

**Phase 1 recommendation:** Start with **round-level switching** (approach
2) because it requires zero wave simulation infrastructure. Three trivial
strategies (orbital, random-dodge, stop-and-go) rotate each round, and
the manager picks the one that took the least damage in its most recent
round. This validates the interface without needing wave tracking.

### Phase 1 strategies

| Strategy | Behavior | Params used |
|---|---|---|
| **Orbital** | Circle opponent at `preferredDistance`, reverse on wall | `preferredDistance` |
| **RandomDodge** | Move forward/reverse randomly, change every 20–40 ticks | `aggression` (controls change frequency) |
| **StopAndGo** | Stop when opponent fires (fire-timing predictor), move between fires | `PREDICTED_OPPONENT_FIRES_3` |

---

## 6. Strategic Layer — 4-Axis Mode System

The strategic layer operates on a slower cadence than per-tick decisions.
It reads predictor outputs and sets `StrategyParams` that the gun and
movement managers consume.

### Four axes

```
┌───────────────────────────────────────────────────────────┐
│                    Strategic Layer                        │
│                                                          │
│  Axis 1: AGGRESSION         [0.0 ─── 0.5 ─── 1.0]       │
│           ← defensive    neutral    aggressive →         │
│           Source: PREDICTED_WIN_PROBABILITY + ENERGY_RATIO│
│           Controls: fire power, risk tolerance            │
│                                                          │
│  Axis 2: RANGE              [close ── mid ── far]        │
│           Source: DISTANCE + wall geometry + energy       │
│           Controls: preferredDistance for movement        │
│                                                          │
│  Axis 3: COUNTER-STRATEGY   [enum: 0..K]                │
│           Source: Fingerprint classifier (P3)             │
│           Controls: which movement/gun biases to use     │
│                                                          │
│  Axis 4: PHASE              [explore ── exploit]         │
│           Source: round number / numRounds + data conf.   │
│           Controls: how much to rely on learned models   │
│                     vs. default/safe behaviors            │
└───────────────────────────────────────────────────────────┘
```

### How axes map to StrategyParams

```java
public final class StrategyComputer {
    /**
     * Recompute strategy params. Called every N ticks (not every tick).
     */
    public StrategyParams compute(Whiteboard wb) {
        // Axis 1: Aggression
        double winProb = wb.getFeature(Feature.PREDICTED_WIN_PROBABILITY);
        double energyRatio = wb.getFeature(Feature.ENERGY_RATIO);
        double aggression = 0.4 * winProb + 0.6 * energyRatio;

        // Axis 2: Range
        double preferredDistance;
        if (aggression > 0.7) {
            preferredDistance = 200;  // close — maximize hit rate
        } else if (aggression < 0.3) {
            preferredDistance = 500;  // far — minimize incoming damage
        } else {
            preferredDistance = 350;  // mid — balanced
        }

        // Axis 3: Counter-strategy (not used in Phase 1)
        // int opponentClass = fingerprintPredictor.predict(wb).classId;

        // Axis 4: Phase — blend toward exploitation as rounds progress
        double phase = (double) wb.getRound() / wb.getNumRounds();
        // In early rounds, dampen aggression toward 0.5 (cautious)
        if (phase < 0.2) {
            aggression = 0.5 + (aggression - 0.5) * 0.3;
        }

        // Fire power from aggression
        double firePower;
        if (aggression > 0.7) firePower = 3.0;
        else if (aggression > 0.4) firePower = 1.5 + aggression;
        else firePower = 0.5 + aggression;

        return new StrategyParams(preferredDistance, aggression, firePower);
    }
}
```

### Phase 1 trivial implementation

```java
public final class TrivialStrategyComputer extends StrategyComputer {
    @Override
    public StrategyParams compute(Whiteboard wb) {
        // Random mode switch every round
        double aggression = (wb.getRound() % 3 == 0) ? 0.8
                          : (wb.getRound() % 3 == 1) ? 0.2
                          : 0.5;
        return new StrategyParams(
            300 + Math.random() * 200,  // random preferred distance
            aggression,
            1.0 + aggression             // power tracks aggression
        );
    }
}
```

---

## 7. Radar Subsystem

Simplest subsystem. In 1v1 Robocode, the optimal radar is a narrow
oscillating lock on the opponent.

```java
public interface IRadarStrategy {
    double getRadarTurn(Whiteboard wb);
    String getName();
}
```

### Phase 1: Narrow lock (already functional)

```java
public final class NarrowLockRadar implements IRadarStrategy {
    @Override
    public double getRadarTurn(Whiteboard wb) {
        double radarHeading = wb.getRadarHeading();
        double bearing = wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS);
        double turn = RoboMath.normalRelativeAngle(bearing - radarHeading);
        // Overshoot slightly to maintain lock
        return turn + Math.signum(turn) * Math.toRadians(2);
    }
    @Override
    public String getName() { return "narrow-lock"; }
}
```

The current infinite-sweep radar works but wastes ticks scanning empty
space. The narrow lock keeps the opponent scanned every tick (or every
other tick), which is critical for fire detection and feature freshness.

---

## 8. Event Flow — Split Scan / Tick

```
┌──────────────────────────────────────────────────────────────────┐
│ EVERY TICK (onStatus → run loop)                                 │
│                                                                  │
│  1. wb.advanceTick()                                             │
│  2. wb.setOurState(...)                                          │
│  3. Execute active movement strategy: robot.setAhead/setTurn     │
│  4. Execute radar: robot.setTurnRadarRight                       │
│  5. If gun is aimed + cool: fire at virtual-gun-selected angle   │
│  6. robot.execute()                                              │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│ ON SCAN (onScannedRobot)                                         │
│                                                                  │
│  1. wb.setOpponentScan(...)                                      │
│  2. transformer.process(wb)     ← features + scalar predictors   │
│  3. virtualGunManager.update(wb)  ← all strategies compute angle │
│  4. Advance & check all virtual bullets for hits                 │
│  5. Re-aim gun toward selected strategy's angle                  │
│  6. If strategic refresh due: strategyComputer.compute(wb)       │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│ ON ROUND START                                                   │
│                                                                  │
│  1. wb.resetRound() (or onRoundStart)                            │
│  2. movementManager.evaluateAndSwitch()  ← round-level fitness  │
│  3. strategyComputer.compute(wb)  ← fresh params for new round   │
└──────────────────────────────────────────────────────────────────┘
```

### Key: decisions happen every tick, features update on scan

Between scans, the robot still moves and fires using the most recent
predictions. The movement strategy runs every tick with the latest
`Whiteboard` state (our position is always fresh even if opponent position
is 1–2 ticks stale). The gun continues turning toward the last-computed
aim angle.

---

## 9. Class Structure (Phase 1)

```
core/src/main/java/cz/zamboch/autopilot/core/
├── Feature.java                    ← add PREDICTED_* entries
├── Whiteboard.java                 ← add PredictorRegistry field
├── Transformer.java                ← unchanged
├── IInGameFeatures.java            ← unchanged
├── predictors/
│   ├── IPredictor.java             ← interface for complex outputs
│   ├── PredictorRegistry.java      ← type-safe map of predictors
│   ├── IGfTargetingPredictor.java  ← extends IPredictor<double[]>
│   └── IFingerprintPredictor.java  ← extends IPredictor<FingerprintResult>
├── strategy/
│   ├── StrategyParams.java         ← value object (distance, aggression, power)
│   ├── StrategyComputer.java       ← abstract: axes → params
│   ├── IGunStrategy.java           ← angle + confidence
│   ├── IMovementStrategy.java      ← MovementCommand + getName
│   ├── IRadarStrategy.java         ← radar turn angle
│   ├── MovementCommand.java        ← value object (ahead, turnRight)
│   └── VirtualBullet.java          ← tracking state for virtual gun hits
├── gun/
│   ├── VirtualGunManager.java      ← tracks all guns, selects best
│   ├── IFirePlan.java              ← multi-shot sequencing interface
│   ├── SingleShotPlan.java         ← wraps IGunStrategy for single fire
│   ├── WaveStackPlan.java          ← slow+fast paired fire sequence
│   ├── TripleStackPlan.java        ← heavy+medium+light 3-shot sequence
│   └── Wave.java                   ← incoming bullet wave tracking
├── movement/
│   ├── MovementStrategyManager.java ← tracks all strategies, round-level switch
│   ├── ICandidatePruner.java      ← fast pre-selection of top-K candidates
│   └── KeepAllPruner.java         ← Phase 1–2 default: returns all indices
└── features/                        ← existing feature classes (unchanged)

robot/src/main/java/cz/zamboch/
├── Autopilot.java                  ← wired to managers
└── trivial/
    ├── TrivialFirePowerPredictor.java
    ├── TrivialRoundOutcomePredictor.java
    ├── TrivialMovementPredictor.java
    ├── TrivialFireTimingPredictor.java
    ├── TrivialGfTargeting.java
    ├── TrivialFingerprint.java
    ├── TrivialStrategyComputer.java
    ├── HeadOnGun.java
    ├── LinearGun.java
    ├── CircularGun.java
    ├── RandomGfGun.java
    ├── OrbitalMovement.java
    ├── RandomDodgeMovement.java
    ├── StopAndGoMovement.java
    └── NarrowLockRadar.java
```

### Module boundaries respected

- **core** — All interfaces, managers, and strategy infrastructure. No I/O.
  This is the decision framework that the competition robot ships.
- **robot** — Concrete trivial implementations + `Autopilot` wiring. Phase 1
  only. Later: distilled ML implementations replace trivial ones.
- **pipeline** — Untouched. Offline CSV processing only.

---

## 10. Wiring in Autopilot.java

```java
public final class Autopilot extends AdvancedRobot {
    private Whiteboard whiteboard;
    private Transformer transformer;
    private VirtualGunManager gunManager;
    private MovementStrategyManager moveManager;
    private IRadarStrategy radarStrategy;
    private StrategyComputer strategyComputer;
    private StrategyParams currentParams;

    @Override
    public void run() {
        whiteboard = new Whiteboard();
        transformer = createTransformer();  // features + scalar predictors
        gunManager = createGunManager();
        moveManager = createMoveManager();
        radarStrategy = new NarrowLockRadar();
        strategyComputer = new TrivialStrategyComputer();

        whiteboard.onRoundStart(getRoundNum(), ...);
        currentParams = strategyComputer.compute(whiteboard);

        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true);

        while (true) {
            // Movement — every tick
            MovementCommand cmd = moveManager.getActiveCommand(whiteboard, currentParams);
            setAhead(cmd.ahead);
            setTurnRightRadians(cmd.turnRight);

            // Radar — every tick
            setTurnRadarRightRadians(radarStrategy.getRadarTurn(whiteboard));

            // Gun — fire when ready
            gunManager.tryFire(this, whiteboard, currentParams.firePowerBudget);

            execute();
        }
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        // Update opponent state
        whiteboard.setOpponentScan(...);

        // Features + scalar predictors
        transformer.process(whiteboard);

        // Virtual gun tracking
        gunManager.onScan(whiteboard);

        // Periodic strategy refresh (every 50 ticks)
        if (whiteboard.getTick() % 50 == 0) {
            currentParams = strategyComputer.compute(whiteboard);
        }
    }

    @Override
    public void onRoundEnded(RoundEndedEvent e) {
        moveManager.onRoundEnd(whiteboard);  // evaluate and maybe switch
    }
}
```

---

## 11. Phase 1 Success Criteria

| Criterion | Test |
|---|---|
| **Compiles and runs** | Robot loads in Robocode without crashes |
| **Features compute** | All 63 features + 8 prediction features produce values |
| **Virtual guns track** | 4 strategies produce different hit rates against a sample bot |
| **Movement strategies run** | 3 strategies produce visible different movement patterns |
| **Strategy switching works** | Movement strategy changes between rounds |
| **Radar locks** | Narrow lock achieves ≥90% scan rate (scans per tick) |
| **Robot is competitive-ish** | Beats `sample.SittingDuck` and `sample.Walls` |

### What Phase 1 does NOT need to do

- Beat any competitive bot (top-50 caliber)
- Produce meaningful predictions (random is fine)
- Optimize for tick budget
- Handle multi-bot (1v1 only)
- Persist state across battles

---

## 12. Evolution Path

| Phase | Change | Goal |
|---|---|---|
| **1 (this)** | Trivial predictors, full wiring | Validate architecture |
| **2** | Distill GBM fire-power + fire-timing | Real energy management |
| **3** | Distill MLP-GF targeting | Real aiming |
| **4** | Distill movement GBM-window | Real dodge |
| **5** | Distill round-outcome + fingerprint | Real strategy |
| **6** | Wave-surf movement (replaces round-level switching) | Competition-grade dodge |
| **7** | Online learning / adaptation | Counter adaptive opponents |

Each phase replaces one trivial predictor with a distilled one and
validates that the robot improves against the rumble pool.

---

## 13. Path Planning Infrastructure (Future — Step 5)

The movement subsystem (§5) starts with simple strategy competition in
Phase 1. Phases 2+ evolve toward trajectory-based movement planning where
candidates are sampled from pre-computed reachable envelopes and scored by
wave danger + position danger. This section defines the interfaces and
data structures that Phase 1 must be forward-compatible with.

### Core additions for path planning

```
core/src/main/java/cz/zamboch/autopilot/core/
├── physics/
│   ├── RobotPhysics.java          ← Robocode movement rules (static)
│   ├── RobotState.java            ← immutable (x, y, heading, velocity)
│   ├── ReachableEnvelope.java     ← pre-computed reachable positions at t+N
│   └── PrecisePredictor.java      ← tick-by-tick forward simulation
├── movement/
│   ├── IPositionDanger.java       ← score (x, y) → danger ∈ [0, 1]
│   ├── IWaveDanger.java           ← score trajectory vs incoming wave
│   ├── ICandidatePruner.java      ← fast pre-selection of top-K candidates
│   ├── CandidatePosition.java     ← (x, y, gf, reachTick, danger)
│   └── PathPlanner.java           ← envelope lookup + prune + score + select
```

### RobotPhysics — movement rules as pure functions

```java
public final class RobotPhysics {
    public static final double MAX_VELOCITY = 8.0;
    public static final double ACCELERATION = 1.0;
    public static final double DECELERATION = 2.0;
    public static final int ROBOT_HALF_SIZE = 18;

    /** Max body turn rate in radians at given speed. */
    public static double maxTurnRate(double velocity) {
        return Math.toRadians(10.0 - 0.75 * Math.abs(velocity));
    }

    /** Advance a RobotState by one tick given (accel, turnRate). */
    public static RobotState step(RobotState s, double accel, double turnRate,
                                  int bfW, int bfH) { ... }
}
```

### RobotState — immutable snapshot

```java
public final class RobotState {
    public final double x, y, heading, velocity;

    public RobotState(double x, double y, double heading, double velocity) {
        this.x = x; this.y = y;
        this.heading = heading; this.velocity = velocity;
    }
}
```

### ReachableEnvelope — pre-computed lookup

Pre-computed offline for each `|initialVelocity|` ∈ {0, 1, ..., 8} and
each horizon `t` ∈ {1, 2, ..., MAX_HORIZON}. Stores relative (dx, dy)
offsets from the starting position (heading = 0). At runtime: rotate by
current heading, translate to current position, clamp to walls.

```java
public final class ReachableEnvelope {
    /** envelopes[absVelocity][horizon] = array of (dx, dy) candidates. */
    private static final double[][][] ENVELOPES = ...;  // loaded from resource

    /**
     * Get candidate positions reachable from the given state at tick+horizon.
     * Rotates pre-computed envelope by heading, translates, clamps to walls.
     */
    public static CandidatePosition[] getCandidates(
            RobotState current, int horizon, int bfW, int bfH) { ... }
}
```

### IPositionDanger — pluggable absolute-position scoring

```java
public interface IPositionDanger {
    /**
     * Danger of being at (x, y) right now, independent of waves.
     * Considers: wall proximity, corner proximity, distance to enemy,
     * battlefield control (center bias).
     * Returns [0, 1]: 0 = safe, 1 = maximum danger.
     */
    double danger(double x, double y, Whiteboard wb);
}
```

Phase 1: hand-tuned (wall distance + corner penalty).
Phase 4: ML-trained from `ticks.csv` position features vs damage outcomes.

### IWaveDanger — pluggable wave-based scoring

```java
public interface IWaveDanger {
    /**
     * Danger of a trajectory that reaches (x, y) at tick t, given an
     * incoming opponent wave. Computes the GF at intercept and looks up
     * the opponent's historical GF danger profile.
     * Returns [0, 1]: 0 = safe, 1 = maximum danger.
     */
    double danger(CandidatePosition candidate, Wave wave, Whiteboard wb);

    /**
     * Multi-wave danger: combined danger of a candidate against all
     * active incoming waves. Each wave's contribution is weighted by
     * bullet damage (Rules.getBulletDamage(power)). A power-3.0 wave
     * contributes 40× the weight of a power-0.1 wave. This makes the
     * surfer immune to wave-stacking distractions from cheap bullets.
     */
    double danger(CandidatePosition candidate, List<Wave> waves, Whiteboard wb);
}
```

### Wave — incoming bullet tracking

```java
public final class Wave {
    public final double originX, originY;  // opponent position at fire
    public final double bulletSpeed;       // 20 - 3 * power
    public final double bulletPower;       // inferred from energy drop
    public final int fireTick;             // tick when fired
    public final double fireAngle;         // bearing to us at fire time

    /** Damage this bullet would deal on hit. */
    public double damage() {
        return (bulletPower <= 1.0)
            ? 4.0 * bulletPower
            : 6.0 * bulletPower - 2.0;
    }

    /** Current radius of the expanding wave. */
    public double radius(int currentTick) {
        return (currentTick - fireTick) * bulletSpeed;
    }

    /** Has this wave passed beyond our position? */
    public boolean hasPassed(double ourX, double ourY, int currentTick) {
        double dist = Math.hypot(ourX - originX, ourY - originY);
        return radius(currentTick) > dist + 18;  // 18 = robot half-size
    }
}
```

Phase 1: uniform wave danger (all GFs equally dangerous).
Phase 3+: GF-profile-based danger from the opponent's targeting stats.

**Multi-wave defense**: When multiple `Wave` objects are active, the
movement strategy scores each candidate position against ALL waves
simultaneously, **weighted by bullet damage**. A power-3.0 bullet
(16 damage) contributes 40× the weight of a power-0.1 bullet (0.4
damage). This damage-weighted scoring makes our surfer immune to
wave-stacking distractions — cheap bullets are effectively ignored.

The combined multi-wave danger for a candidate is:

    danger = Σ gfDanger(candidate, wave_i) × wave_i.damage() / Σ wave_j.damage()

DrussGT uses this same principle: *"I scale the danger based of the
damage the wave would cause."* Bots that weight waves equally are
vulnerable to wave stacking; damage-weighted surfers are not.

### ICandidatePruner — fast pre-selection

```java
public interface ICandidatePruner {
    /**
     * Score candidates cheaply and return indices of the top-K.
     * Phase 2: KeepAllPruner returns all indices (VCS is cheap enough).
     * Phase 3+: MlpCandidatePruner uses a small distilled MLP.
     */
    int[] prune(CandidatePosition[] candidates, int k, Whiteboard wb);
}
```

Phase 2: `KeepAllPruner` — returns all indices. VCS danger scores all
280 candidates in 0.2 ms.

Phase 3+: `MlpCandidatePruner` — small MLP [12→32→1] scores 280
candidates in 0.2 ms, returns top-20. Trained by distillation from the
full MLP danger scorer (see path-planning.md §7 for training details).

### Integration with IMovementStrategy

The path planner is a *tool* that movement strategies can use, not a
strategy itself. A `WaveSurfStrategy implements IMovementStrategy` would
call `PathPlanner.bestCandidate(wb, params)` each tick and return the
corresponding `MovementCommand`. Simpler strategies (orbital, random-dodge)
don't need the path planner at all.

```java
public final class PathPlanner {
    private final IPositionDanger posDanger;
    private final IWaveDanger waveDanger;
    private final ICandidatePruner pruner;
    private final int bfW, bfH;

    /**
     * Dual-horizon planning:
     *   t+10: dodge current wave(s) — 10 candidates, full MLP scoring
     *   t+20: post-dodge positioning — 10 candidates, position danger only
     * Returns the best first-tick action from the winning destination cluster.
     */
    public CandidatePosition bestCandidate(Whiteboard wb, StrategyParams params) {
        RobotState current = RobotState.fromWhiteboard(wb);

        // --- Horizon 1: t+10, wave dodge ---
        int h1 = estimateHorizon(wb);  // bullet flight time
        CandidatePosition[] all1 =
            ReachableEnvelope.getCandidates(current, h1, bfW, bfH);
        int[] kept1 = pruner.prune(all1, 10, wb);

        double bestDanger = Double.MAX_VALUE;
        CandidatePosition best = null;
        for (int idx : kept1) {
            CandidatePosition c = all1[idx];
            double d = waveDanger.danger(c, activeWaves, wb)
                     + posDanger.danger(c.x, c.y, wb);
            if (d < bestDanger) { bestDanger = d; best = c; }
        }

        // --- Horizon 2: t+20, positioning (optional) ---
        if (best != null) {
            RobotState after = RobotState.at(best.x, best.y, /* heading, vel */);
            CandidatePosition[] all2 =
                ReachableEnvelope.getCandidates(after, 10, bfW, bfH);
            int[] kept2 = pruner.prune(all2, 10, wb);
            // Score by position danger only (no wave — too uncertain at t+20)
            for (int idx : kept2) {
                double d2 = posDanger.danger(all2[idx].x, all2[idx].y, wb);
                // Blend with h1 danger for the combined score
            }
        }

        return best;
    }
}
```

### Evolution path for movement

| Phase | Movement | Path planning role |
|---|---|---|
| **1** | Competing strategies (orbital/random/stop-and-go) | Not used |
| **2** | `WaveSurfStrategy` + VCS danger | Envelope → 280 candidates → `KeepAllPruner` → VCS scores all |
| **3** | MLP danger + pruner + dual horizon | Envelope → 280 → `MlpCandidatePruner` → 10+10 → MLP danger |
| **4** | ML position danger | `IPositionDanger` from distilled model |
| **5** | Bayesian prior + online update | Blend offline MLP with online VCS per wave |

---

## Appendix A: Robocode Execution Model Constraints

- `execute()` must be called each tick or the robot skips.
- `setAhead()` / `setTurnRight()` are non-blocking — they set the target
  for the next tick's physics step. Can be called multiple times; last
  value wins.
- `setFire()` is instant if gun heat = 0. Returns a `Bullet` object.
- Gun and body turn simultaneously but at different max rates.
- Radar turns independently (unlimited speed with `setAdjustRadar*`).
- Events (onScannedRobot, onBulletHit, etc.) fire between ticks, before
  `execute()` returns.

## Appendix B: Why Not One Decision-Maker?

A single monolithic decision function that reads all features and outputs
(ahead, turn, gunTurn, fire, radarTurn) would be the "simplest" design.
The problem is credit assignment: when the robot loses, was it because the
gun aimed wrong, the movement was poor, or the strategy was wrong? With
separate subsystems, each can be evaluated and replaced independently.

The virtual-gun pattern proves this works in practice: DrussGT runs 4
independent gun strategies and auto-selects the best one per opponent.
Extending this to movement is the natural next step. The strategic layer
on top provides the slow-changing parameters that all subsystems consume.

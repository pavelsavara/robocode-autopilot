# Robot Architecture

*Phase 1 implemented (trivial predictors, full wiring). See
[archive/2026-05-03-robot-architecture-plan.md](../archive/2026-05-03-robot-architecture-plan.md)
for the design document.*

## System Diagram

```
┌──────────────────────────────────────────────────────────┐
│                    Autopilot.java                         │
│                                                          │
│  onStatus → Whiteboard.advanceTick()                     │
│  onScannedRobot → Whiteboard.setOpponentState()          │
│                   Transformer.process()                  │
│                   VirtualGunManager.onScan()             │
│                   StrategyComputer.compute() [every 50t] │
│                                                          │
│  run() loop:                                             │
│    MovementStrategyManager → setAhead/setTurn            │
│    IRadarStrategy → setTurnRadar                         │
│    VirtualGunManager → setTurnGun                        │
│    shouldFire() → setFire()                              │
└──────────────────────────────────────────────────────────┘
```

## Subsystems

### 1. Feature Pipeline (Whiteboard + Transformer)

Same Whiteboard/Transformer used in offline pipeline and robot.
Stateless feature processors read from Whiteboard, compute, and write
back via `wb.setFeature()`. Dependency resolution via topological sort.

### 2. Predictors (6 slots)

Two interface types:

**Scalar predictors** — implement `IInGameFeatures`, write to `Feature` enum:
| Predictor | Output feature | Phase 1 impl |
|---|---|---|
| Fire Power | `PREDICTED_FIRE_POWER` | Random [1,3] |
| Round Outcome | `PREDICTED_WIN_PROBABILITY` | Always 0.5 |
| Movement | `PREDICTED_LAT_VEL_5` | Persist current lat-vel |
| Fire Timing | `PREDICTED_OPPONENT_FIRES_3` | Always 0.07 |

**Distribution predictors** — implement `IPredictor<T>`, stored in `PredictorRegistry`:
| Predictor | Output type | Phase 1 impl |
|---|---|---|
| GF Targeting | `double[61]` GF distribution | Uniform (1/61) |
| Fingerprint | `FingerprintResult` (classId + probs) | Class 0, uniform |

### 3. Virtual Gun Manager

Classic virtual-gun pattern. Multiple `IGunStrategy` implementations fire
**virtual bullets** every tick. Tracks hit/miss over a 100-bullet sliding
window and selects the best-performing strategy.

**Phase 1 strategies:**
| Strategy | Aiming method | Confidence |
|---|---|---|
| `HeadOnGun` | Direct bearing to opponent | 1.0 |
| `LinearGun` | Linear extrapolation | 0.7 |
| `CircularGun` | Constant turn-rate extrapolation | 0.8 |
| `RandomGfGun` | Random GF offset | 0.1 |

**Selection:** Best hit-rate wins. Ties broken by `getConfidence()`.

### 4. Fire Plans

`IFirePlan` interface for multi-shot sequencing:
| Plan | Shots | Behavior |
|---|---|---|
| `SingleShotPlan` | 1 | Wraps any `IGunStrategy` |
| `DoubleStackPlan` | 2 | Power 3.0 (speed 11) + power 0.1 (speed 19.7) |
| `TripleStackPlan` | 3 | Power 3.0 + power ~1.85 + power 0.1 |

Wave stacking is a niche tactic (see [ml-results.md](ml-results.md)).
Not active in Phase 1 (`useWaveStacking=false`).

### 5. Movement Strategy Manager

Round-level strategy competition:
| Strategy | Behavior |
|---|---|
| `OrbitalMovement` | Circle opponent at preferred distance, reverse on wall |
| `RandomDodgeMovement` | Forward/reverse randomly every 20–40 ticks |
| `StopAndGoMovement` | Stop when opponent fires, move between fires |

**Selection:** First N rounds: rotate through all. Then: pick lowest
damage-taken strategy and stick with it.

**Phase 2 target:** Replace with per-tick path planning (reachable envelopes).

### 6. Radar Strategy

`NarrowLockRadar` — oscillates tightly on opponent for near-100% scan rate.
Overshoots by 2° to maintain lock.

### 7. Strategy Layer (4-Axis Mode System)

`StrategyComputer` produces `StrategyParams` every 50 ticks:

| Axis | Source | Controls |
|---|---|---|
| **Aggression** | win probability + energy ratio | Fire power budget, risk |
| **Range** | distance + wall geometry | Preferred engagement distance |
| **Counter-strategy** | Fingerprint classifier | Per-family GF priors |
| **Phase** | round / numRounds | Explore (early) → exploit (late) |

Phase 1: `TrivialStrategyComputer` cycles aggression per round.

---

## Module Boundaries

```
core/src/main/java/cz/zamboch/autopilot/core/
├── Feature.java, Whiteboard.java, Transformer.java
├── gun/           VirtualGunManager, IFirePlan, Wave, *StackPlan
├── movement/      MovementStrategyManager, ICandidatePruner, PathPlanner
├── physics/       RobotPhysics, RobotState, PrecisePredictor, ReachableEnvelope
├── predictors/    IPredictor<T>, PredictorRegistry, IGfTargetingPredictor
├── strategy/      IGunStrategy, IMovementStrategy, IRadarStrategy, StrategyComputer
└── features/      All IInGameFeatures implementations

robot/src/main/java/cz/zamboch/
├── Autopilot.java              Wiring + event handling
└── trivial/                    Phase 1 trivial implementations
    ├── HeadOnGun, LinearGun, CircularGun, RandomGfGun
    ├── OrbitalMovement, RandomDodgeMovement, StopAndGoMovement
    ├── NarrowLockRadar
    ├── TrivialStrategyComputer
    └── Trivial*Predictor (6 predictors)

pipeline/                       Offline CSV processing only (untouched)
```

**Rules:**
- **core** — all interfaces and managers. No I/O. Ships with robot.
- **robot** — concrete implementations + `Autopilot` wiring. Depends on core only.
- **pipeline** — offline processing. Depends on core. Not shipped with robot.

---

## Evolution Path

| Phase | State | Description |
|---|---|---|
| 1 | **Done** | Trivial predictors, full wiring, smoke test |
| 2 | Next | Multi-wave features, retrain models |
| 3 | Planned | Distill GBM/MLP models to Java, replace trivial predictors |
| 4 | Planned | Reachable envelope path planning replaces round-level movement |
| 5 | Planned | Online learning (VCS + Bayesian prior blending) |
| 6 | Planned | Per-family counter-strategies from fingerprint |
| 7 | Future | Adaptation detection + mid-battle strategy switching |

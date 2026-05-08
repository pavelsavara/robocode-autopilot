# Robot Architecture

*Phase 8 (ML distillation) complete. All major subsystems wired.*

See [archive/2026-05-03-robot-architecture-plan.md](../archive/2026-05-03-robot-architecture-plan.md)
for the original design document.

## System Diagram

```
┌──────────────────────────────────────────────────────────┐
│                    Autopilot.java                        │
│                                                          │
│  onStatus → Whiteboard.advanceTick()                     │
│             TickBudget.tickStart()                       │
│             interpolateIfNoScan() [dead-reckoning]       │
│  onScannedRobot → Whiteboard.setOpponentState()          │
│                   Push TickBudget → predictors           │
│                   Transformer.process()                  │
│                   VirtualGunManager.onScan()             │
│                   StrategyComputer.compute() [every 50t] │
│                                                          │
│  run() loop:                                             │
│    ML model load status logging [tick 10]                │
│    MovementStrategyManager → setAhead/setTurn            │
│    IRadarStrategy → setTurnRadar                         │
│    VirtualGunManager → setTurnGun                        │
│    shouldFire() → setFire()                              │
│    TickBudget.tickEnd()                                  │
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
| Predictor | Output feature | Implementation |
|---|---|---|
| Fire Power | `PREDICTED_FIRE_POWER` | `GbmFirePowerPredictor` (200-tree XGBoost, R²=0.906) |
| Movement | `PREDICTED_LAT_VEL_5` | `GbmMovementPredictor` (200-tree XGBoost, R²=0.739) |
| Fire Timing | `PREDICTED_OPPONENT_FIRES_3` | `GbmFireTimingPredictor` (200-tree XGBoost, AUC=0.773) |

**Distribution predictors** — implement `IPredictor<T>`, stored in `PredictorRegistry`:
| Predictor | Output type | Implementation |
|---|---|---|
| GF Targeting | `double[61]` GF distribution | `MlpGfTargeting` (uniform — deferred, data-starved) |

### 3. Virtual Gun Manager

Classic virtual-gun pattern. Multiple `IGunStrategy` implementations fire
**virtual bullets** every tick. Tracks hit/miss over a 100-bullet sliding
window and selects the best-performing strategy.

**Gun strategies:**
| Strategy | Aiming method | Confidence |
|---|---|---|
| `HeadOnGun` | Direct bearing to opponent | 1.0 |
| `LinearGun` | Linear extrapolation | 0.7 |
| `CircularGun` | Constant turn-rate extrapolation | 0.8 |
| `VcsGun` | Probabilistic sampling from smoothed 61-bin GF histogram | Scales with observations |

**Selection:** Best hit-rate wins. Ties broken by `getConfidence()`.
VCS histogram segmented by distance (3 bins) × lateral direction (2 bins).
Probabilistic firing with Gaussian kernel smoothing (σ=1.5 bins).

### 4. Movement Strategy Manager

Round-level strategy competition with per-tick wave surfing:
| Strategy | Behavior |
|---|---|
| `OrbitalMovement` | Circle opponent at preferred distance, reverse on wall |
| `WaveSurfMovement` | PathPlanner: envelope candidates → VCS danger scoring → lowest-danger position |

**Selection:** First N rounds: rotate through all. Then: pick lowest
damage-taken strategy and stick with it.

### 5. Wave Danger Scoring

`VcsWaveDanger` uses the opponent's actual GF histogram (built from wave-break
observations) with a Gaussian prior for early-game robustness. Urgency-weighted:
imminent waves dominate. Optional random wave selection for anti-exploitation.

### 6. Radar Strategy

`NarrowLockRadar` — oscillates tightly on opponent for near-100% scan rate.
Overshoots by 2° to maintain lock.

### 7. Strategy Layer

`EnergyRatioStrategyComputer` produces `StrategyParams` every 50 ticks:

| Axis | Source | Controls |
|---|---|---|
| **Aggression** | energy ratio | Fire power budget, risk |
| **Range** | stable 350px | Preferred engagement distance |
| **Kill-shot** | opponent energy | Exact-kill at low energy |
| **Wave selection** | opponent strength | Random wave dodge vs strong opponents |

### 8. ML Model Infrastructure

| Component | Description |
|---|---|
| `GbmTreeEnsemble` | Flat-array tree interpreter with adaptive truncation |
| `FeatureMapping` | CSV column name → Feature enum bridge |
| `WindowFeatures` | O(1) incremental 20-tick mean/std for 10 base features |
| `TickBudget` | Adaptive CPU throttle: halves trees on skipped turn, recovers 5%/tick |
| `PersistenceManager` | Versioned binary save/load via `RobocodeFileOutputStream` |
| `DefaultDataFile` | Base64-embedded `autopilot.dat` fallback for first battle on new machine |
| `VcsHistogramStore` | Per-opponent VCS histograms (keyed by bot name hash, LRU 30 entries) |

Models are embedded as Base64 strings in Java source (~440 KB each).
Persistence data (~44 KB) is embedded as a Base64 fallback for first-battle priors.
No file I/O at runtime — works inside Robocode's security sandbox.

---

## Module Boundaries

```
core/src/main/java/cz/zamboch/autopilot/core/
├── Feature.java, Whiteboard.java, Transformer.java, WaveRecord.java
├── gun/           VirtualGunManager, VcsGun
├── movement/      MovementStrategyManager, PathPlanner, WaveSurfMovement
│                  IPositionDanger, WallDistancePositionDanger
│                  IWaveDanger, VcsWaveDanger
│                  CandidatePosition
├── physics/       RobotPhysics, RobotState, MutableRobotState
│                  PrecisePredictor, ReachableEnvelope, EnvelopeData
├── predictors/    IPredictor<T>, PredictorRegistry, IGfTargetingPredictor
│                  (fingerprint predictor removed — 19MB, 51.6% accuracy)
├── strategy/      IGunStrategy, IMovementStrategy, IRadarStrategy
│                  StrategyComputer, StrategyParams, MovementCommand
│                  VirtualBullet
├── features/      EnergyFeatures, SpatialFeatures, MovementFeatures, ...
│                  EnvelopeFeatures, MultiWaveFeatures, CombatProgressFeatures
│                  WindowFeatures (20-tick sliding mean/std)
├── ml/            GbmTreeEnsemble, FeatureMapping, TickBudget
└── persistence/   IPersistable, PersistenceManager

robot/src/main/java/cz/zamboch/
├── Autopilot.java              Wiring + event handling + model logging
├── distilled/                  ML model data + predictors
│   ├── GbmFirePowerPredictor, GbmMovementPredictor, GbmFireTimingPredictor
│   ├── FirePowerData, MovementData, FireTimingData (Base64-embedded trees)
│   ├── MlpGfTargeting (skeleton — deferred)
│   └── OpponentProfileData (strength rating stub)
└── trivial/                    Simple strategies
    ├── HeadOnGun, LinearGun, CircularGun
    ├── OrbitalMovement
    ├── NarrowLockRadar
    └── EnergyRatioStrategyComputer

pipeline/                       Offline CSV processing only
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
| 2 | **Done** | Multi-wave + envelope + combat progress features |
| 3 | **Done** | Path planning: ReachableEnvelope, WaveSurfMovement |
| 4 | **Done** | VCS gun + VCS wave danger + energy strategy + persistence |
| 5 | **Done** | ML distillation: 3 GBM models (fire power, movement, fire timing) |
| 6 | Planned | Online learning (VCS + Bayesian prior blending) |
| 7 | Future | Adaptation detection + mid-battle strategy switching |

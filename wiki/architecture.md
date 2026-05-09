# Robot Architecture — Autopilot 0.1.0

*Last updated: 2026-05-09. Matches code as deployed.*

---

## 1. System Overview

```
┌──────────────────────────────────────────────────────────┐
│                    Autopilot.java                        │
│                                                          │
│  onStatus → Whiteboard.advanceTick()                     │
│             TickBudget.tickStart()                        │
│             interpolateIfNoScan() [dead-reckoning]       │
│  onScannedRobot → Whiteboard.setOpponentState()          │
│                   Push TickBudget → predictors            │
│                   Transformer.process()                   │
│                   VirtualGunManager.onScan()              │
│                   StrategyComputer.compute() [every scan] │
│                                                          │
│  run() loop:                                             │
│    MovementStrategyManager → setAhead/setTurn            │
│    IRadarStrategy → setTurnRadar                         │
│    VirtualGunManager → setTurnGun                        │
│    shouldFire() → setFire()                              │
│    emitTickLog() [internal.csv]                           │
│    TickBudget.tickEnd()                                   │
└──────────────────────────────────────────────────────────┘
```

---

## 2. Boot Sequence & Persistence

### 2a. First tick of first round

```
ensureInitialized()
  ├─ ReachableEnvelope.ensureLoaded()          // force class-load of byte[][] tables (~120 KB static)
  ├─ GbmFirePowerPredictor.ensureLoaded()      // force Base64 decode of model data
  ├─ GbmMovementPredictor.ensureLoaded()
  ├─ GbmFireTimingPredictor.ensureLoaded()
  ├─ new Whiteboard, Transformer, GunManager, MoveManager, Radar, Strategy, TickBudget, VcsStore
  ├─ Register 4 IPersistable sections with PersistenceManager
  ├─ Try load data file:
  │   ├─ getDataFile("autopilot.dat") exists? → read bytes → persistence.loadWithStatus()
  │   └─ else: DefaultDataFile.decode() → Base64 fallback → persistence.loadWithStatus()
  ├─ whiteboard.initVcsPrior(3)   // Gaussian at GF=0 for VCS gun cold-start
  └─ Per-round init: Whiteboard.onRoundStart(), gunManager.onRoundStart(), moveManager.onRoundStart()
```

### 2b. What gets persisted (4 sections)

| Section ID | Class | What's Saved | Size | Purpose |
|---|---|---|---|---|
| 1 | `VirtualGunManager` | Hit/miss ring buffer (50-window) per strategy, hit counts, history index | ~300 B | Remember which gun works across battles |
| 2 | `MovementStrategyManager` | Active strategy index, rounds played, EMA damage per strategy | ~50 B | Remember which movement works |
| 3 | `TickBudget` | Ceiling (max sustainable tree count) | 4 B | Don't re-learn CPU limits |
| 4 | `VcsHistogramStore` | Per-opponent LRU (30 entries) of gun+move VCS histograms, each 12×61 bins as `short` | ~88 KB | Warm-start targeting/dodge against known opponents |

**Binary format:** `[MAGIC "RBAP":4][VERSION=1:4][SECTION_COUNT:4]` then per-section `[ID:4][LENGTH:4][DATA:length]`

### 2c. Battle end save

```
onBattleEnded()
  ├─ vcsStore.saveFrom(opponentBotIdHash, whiteboard)   // snapshot current VCS
  ├─ persistence.save() → byte[]
  └─ write to getDataFile("autopilot.dat") via RobocodeFileOutputStream
```

### 2d. Embedded data blobs

| Java Class | Data | Size | Purpose |
|---|---|---|---|
| `FirePowerData` | Base64 strings (D0-D7) | ~350 KB | XGBoost fire power model (200 trees × depth 6, 80 features) |
| `MovementData` | Base64 strings | ~450 KB | XGBoost movement model (200 trees × depth 6, 76 features) |
| `FireTimingData` | Base64 strings | ~410 KB | XGBoost fire timing model (200 trees × depth 6, 81 features) |
| `DefaultDataFile` | Base64 string | ~44 KB | Persistence blob fallback (VCS histograms from training battles) |
| `EnvelopeData` | Static `byte[][]` arrays | ~120 KB | Pre-computed reachable positions at 9 velocity levels |
| `OpponentProfileData` | Base64 string | ~400 B | 50-entry sorted table of `[botIdHash, strength×1000]` |

---

## 3. Battle Tick Sequence

### Phase 0: `onStatus(StatusEvent)` — called first every tick

1. `ensureInitialized()` — first-round: full boot; new-round: per-round reset
2. `tickBudget.tickStart()` — record `System.nanoTime()`
3. `whiteboard.advanceTick()` — tick++, clear all feature flags to NaN
4. `whiteboard.setTick(time)` — set game clock
5. `whiteboard.setOurState(x,y,h,gh,rh,v,e,gunHeat)` — from StatusEvent
6. `interpolateIfNoScan()` — if no scan this tick AND scan age < 5:
   dead-reckon opponent position, recompute DISTANCE and BEARING_TO_OPPONENT_ABS

### Phase 1: `onScannedRobot(ScannedRobotEvent)` — fires on scan ticks

1. Compute absolute opponent position from bearing + distance
2. `whiteboard.setOpponentScan(name, x, y, heading, velocity, energy)`
   — shifts current → prev for fire detection and heading delta
3. First scan only: `loadOpponentProfile()`
   — hash name → strength rating, warm-start VCS from cross-battle store
4. Push `tickBudget.getBudget()` → all 3 GBM predictors (`setMaxTrees`)
5. `transformer.process(whiteboard)` — runs all features + predictors in topological order
6. `gunManager.onScan(wb, firePowerBudget)` — check virtual bullet hits, fire new virtual bullets, select best gun
7. `strategyComputer.compute(wb)` — refresh StrategyParams (every scan tick)

### Phase 2: `run()` loop body — executes every tick after events

1. `moveManager.getActiveCommand(wb, params)` → `setAhead` + `setTurnRight`
2. `radarStrategy.getRadarTurn(wb)` → `setTurnRadarRight`
3. `gunManager.getGunTurnAngle(wb)` → `setTurnGunRight`
4. Fire decision: clamp power to affordable, fire if gun aimed (< 0.015 rad) and gunHeat ≤ 0 and energy > 0.2
5. `emitTickLog()` — structured TICK row to stdout (captured in .br, extracted to internal.csv)
6. `execute()` — Robocode commits all setXxx commands
7. `tickBudget.tickEnd()` — measure tick duration, recover budget toward ceiling, slow ceiling recovery (+1 every 200 skip-free ticks)

### Phase 3: Round/Battle lifecycle

- `onWin/onDeath` → increment roundsWon/roundsLost
- `onRoundEnded` → `moveManager.onRoundEnd(wb)` → record EMA damage for strategy selection
- `onBattleEnded` → save VCS to store → save all persistence → write data file
- `onSkippedTurn` → `tickBudget.onSkippedTurn(round, tick)` — ignores round 0 tick < 10 (startup), otherwise halves budget

---

## 4. Subsystems

### 4a. Feature Pipeline (Transformer)

Stateless processors registered in dependency order. Each reads from Whiteboard, computes, writes back via `wb.setFeature()`.

**Registered processors (in order):**
1. `PositionFeatures` — OUR_X/Y/HEADING/VELOCITY, OUR_DIST_TO_WALL_MIN, OUR_LATERAL_VELOCITY, OPPONENT_X/Y/HEADING
2. `SpatialFeatures` — DISTANCE, BEARING_TO_OPPONENT_ABS, OPPONENT_DIST_TO_WALL_MIN
3. `MovementFeatures` — lateral/advancing velocity, heading delta, direction, acceleration
4. `EnergyFeatures` — fire detection from energy drop, OPPONENT_FIRED, OPPONENT_FIRE_POWER
5. `TimingFeatures` — gun heat, scan age, ticks since fire
6. `IdentityFeatures` — name/bot/version hashes (FNV-1a)
7. `TargetingFeatures` — linear/circular targeting angles, GF coordinates, MEA
8. `MultiWaveFeatures` — wave creation on fire detect, prune passed waves (updates VCS histograms), wave counts
9. `EnvelopeFeatures` — fill ratio, reachable distance/GF range
10. `CombatProgressFeatures` — cumulative damage, hit rates, shot counts
11. `WindowFeatures` — O(1) incremental 20-tick mean+std for 10 base features
12. `ScanCoverageFeatures` — radar quality metrics
13. `GbmFirePowerPredictor` → PREDICTED_FIRE_POWER
14. `GbmMovementPredictor` → PREDICTED_LAT_VEL_5
15. `GbmFireTimingPredictor` → PREDICTED_OPPONENT_FIRES_3

### 4b. Virtual Gun Manager

5 gun strategies competing via virtual bullets. 50-bullet sliding window.

| # | Strategy | Aiming Method | Confidence |
|---|----------|---------------|------------|
| 0 | `CircularGun` | Constant turn-rate extrapolation | 1.0 |
| 1 | `LinearGun` | Linear extrapolation | 0.7 |
| 2 | `VcsGun` | Peak-firing from smoothed 61-bin GF histogram (σ=1.5 kernel) | scales with observations |
| 3 | `PredictiveGun` | Iterative forward simulation using ML PREDICTED_LAT_VEL_5 | scales with model confidence |
| 4 | `HeadOnGun` | Direct bearing to opponent | 0.3 |

**Selection:** Best hit rate wins (ε=0.01 tolerance). Ties broken by `getConfidence()`. ε-greedy exploration at 3% after 30 data points.

**Virtual bullet hit check:** Euclidean distance from simulated bullet position to opponent position at wave-pass time. Hit if distance ≤ 18px (robot half-size).

**Aim threshold:** 0.015 rad (~0.86°) — gun must be within this angle of target to fire.

**VCS segmentation:** 3 distance bins (< 250, < 500, ≥ 500) × 2 lateral direction = 6 segments. Gun VCS histogram initialized with Gaussian prior (strength=3) at GF=0.

### 4c. Movement Strategy Manager

2 strategies competing at round level via EMA damage comparison (α=0.3):

| # | Strategy | Behavior |
|---|----------|----------|
| 0 | `WaveSurfMovement` | Orbit-primary: high-speed lateral movement with imminent-wave dodge |
| 1 | `OrbitalMovement` | Circle opponent at 350px, reverse on wall proximity (80px) |

**Selection:** First N rounds rotate through all strategies. Then pick lowest EMA damage.

### 4d. WaveSurfMovement (orbit-primary)

Default behavior: high-speed lateral orbit (|ahead|=150) with wall-aware random direction reversals (15-45 tick intervals, forced reversal at wall distance < 60px).

When imminent wave detected (< 12 ticks to break):
- Activate `PathPlanner.plan()` — evaluates 50 candidates from `ReachableEnvelope` (10-tick horizon, 2px grid, ±1px jitter)
- Score: `0.2 × positionDanger + 0.8 × waveDanger`
- Navigate toward lowest-danger candidate at max speed

**`WallDistancePositionDanger`** — wall danger (< 100px), corner danger (< 150px), distance danger (< 150px or > 600px). Weights: wall 0.45, corner 0.30, distance 0.25.

**`VcsWaveDanger`** — reads opponent's targeting histogram (moveVcs). Gaussian prior at GF=0 (σ=0.4 with ≥ 8 observations, σ=0.8 with fewer). Prior weight=3.0. Urgency weighting: `1/max(1, ticksUntilBreak)`. Optional random wave selection for anti-exploitation.

### 4e. Strategy Layer

`EnergyRatioStrategyComputer` produces `StrategyParams` on every scan tick:

| Output | Source | Logic |
|--------|--------|-------|
| `aggression` | energy ratio | 0.8 if ratio > 0.6, 0.5 if > 0.4, 0.2 if > 0.25, 0.05 if critical. ±0.2 from opponent strength rating. ±0.1-0.15 from predicted fire power. |
| `firePowerBudget` | distance, energy, opponent energy | Kill-shot: < 0.5 → 0.1, < 4.0 → exact kill, < 20 → 3.0. Normal: distance-scaled (3.0/2.0/1.5/1.0 at 150/300/500/500+ px) ± aggression × 0.5. Capped at ourEnergy/4. |
| `preferredDistance` | constant | 350px |
| `randomWaveSelection` | opponent strength, predicted fire power | Enabled vs strong opponents (> 0.7) or high predicted bullet power (> 2.5) |

**Firing:** Power clamped to `max(0.1, energy - 0.1)`. Fires if gun aimed, gunHeat ≤ 0, and energy > 0.2.

### 4f. Radar Strategy

`NarrowLockRadar` — oscillates tightly on opponent bearing + 2° overshoot. Falls back to full sweep if no scan yet. Provides near-100% scan rate.

### 4g. TickBudget (Adaptive CPU Throttle)

Controls how many trees each GBM predictor evaluates per tick. Max = 200 trees.

- On skipped turn (round > 0 or tick ≥ 10): halve budget and ceiling. Floor = 10.
- On successful tick: recover budget toward ceiling (+5%/tick). Recover ceiling toward maxTrees (+1 every 200 skip-free ticks).
- Startup immunity: skips during round 0 tick < 10 are ignored (class loading cost, not model cost).
- Persists ceiling across battles (section ID 3).

**Historical bug (fixed 2026-05-09):** ceiling was a one-way ratchet — converged to 10/200 trees (5% capacity) and persisted forever, crippling all ML models. Fix: added upward ceiling recovery + startup skip immunity.

---

## 5. Feature Map

### 5a. Core Observation Features

| Feature | Producer | Consumed By |
|---|---|---|
| `OUR_X`, `OUR_Y`, `OUR_HEADING`, `OUR_VELOCITY` | `PositionFeatures` | WindowFeatures, EnvelopeFeatures, PredictiveGun, PathPlanner |
| `OUR_DIST_TO_WALL_MIN` | `PositionFeatures` | WaveSurfMovement (wall-aware reversal), GBM models |
| `OUR_LATERAL_VELOCITY` | `PositionFeatures` | TargetingFeatures (OPPONENT_GUESS_FACTOR), GBM models |
| `OPPONENT_X`, `OPPONENT_Y`, `OPPONENT_HEADING` | `PositionFeatures` | VcsGun, PredictiveGun, PathPlanner |
| `DISTANCE` | `SpatialFeatures` | All targeting, VCS segment, WindowFeatures, all GBM models |
| `BEARING_TO_OPPONENT_ABS` | `SpatialFeatures` | All targeting, all movement |
| `OPPONENT_DIST_TO_WALL_MIN` | `SpatialFeatures` | GBM models |
| `OPPONENT_VELOCITY`, `OPPONENT_LATERAL_VELOCITY`, `OPPONENT_ADVANCING_VELOCITY` | `MovementFeatures` | TargetingFeatures, PredictiveGun, GBM models |
| `OPPONENT_HEADING_DELTA` | `MovementFeatures` | CircularGun, PredictiveGun, GBM models |
| `OPPONENT_LATERAL_DIRECTION` | `MovementFeatures` | VCS segment selection |
| `OPPONENT_ENERGY` | `EnergyFeatures` | StrategyComputer, GBM models |
| `OPPONENT_FIRED`, `OPPONENT_FIRE_POWER` | `EnergyFeatures` | MultiWaveFeatures, GBM models |
| `OUR_GUN_HEAT`, `TICKS_SINCE_SCAN` | `TimingFeatures` | GBM models, WindowFeatures |
| `ENERGY_RATIO` | computed inline | StrategyComputer |

### 5b. Derived Features

| Feature | Producer | Notes |
|---|---|---|
| `LINEAR_TARGET_ANGLE/OFFSET` | `TargetingFeatures` | Law-of-sines projection |
| `CIRCULAR_TARGET_ANGLE/OFFSET` | `TargetingFeatures` | Iterative circular extrapolation |
| `GF_CURRENT_AT_POWER_{1,1_5,2}` | `TargetingFeatures` | Normalized dodge position |
| `MEA_FOR_OUR_BULLET` | `TargetingFeatures` | Maximum escape angle — bounds VCS GF range |
| `N_OPPONENT_WAVES_IN_FLIGHT`, `NEAREST_OPPONENT_WAVE_GAP` | `MultiWaveFeatures` | Wave pressure (34% of GF importance in nb13) |
| `ENVELOPE_FILL_RATIO`, `REACHABLE_GF_RANGE` | `EnvelopeFeatures` | Movement constraint signal |
| **WindowFeatures ×20** | `WindowFeatures` | 20-tick mean+std of 10 base features. **THE key ML innovation: R² 0.07 → 0.87.** |
| `CUMULATIVE_DAMAGE_DEALT/RECEIVED`, `OUR_HIT_RATE` | `CombatProgressFeatures` | Running battle score |
| `OPPONENT_NAME_HASH`, `OPPONENT_BOT_ID_HASH` | `IdentityFeatures` | Per-opponent VCS store key |
| `OPPONENT_STRENGTH_RATING` | `loadOpponentProfile()` | Aggression tuning for known opponents |

### 5c. ML Predictor Outputs

| Feature | Producer | Consumer | Notes |
|---|---|---|---|
| `PREDICTED_FIRE_POWER` | `GbmFirePowerPredictor` | StrategyComputer | Dodge urgency: high power → reduce aggression |
| `PREDICTED_LAT_VEL_5` | `GbmMovementPredictor` | `PredictiveGun` | Aim where opponent WILL be in 5 ticks |
| `PREDICTED_OPPONENT_FIRES_3` | `GbmFireTimingPredictor` | `WaveSurfMovement` | Pre-emptive dodge when P(fire) > 0.7 |

---

## 6. ML Models

| Model | Output Feature | Trees | Features | Metric | Consumer |
|-------|---------------|-------|----------|--------|----------|
| Fire Power | `PREDICTED_FIRE_POWER` | 200 × depth 6 | 80 | R²=0.862 | Strategy: dodge urgency, aggression |
| Movement | `PREDICTED_LAT_VEL_5` | 200 × depth 6 | 76 | R²=0.866 | PredictiveGun: aim where opponent WILL be |
| Fire Timing | `PREDICTED_OPPONENT_FIRES_3` | 200 × depth 6 | 81 | AUC=0.855 | WaveSurfMovement: pre-emptive lateral dodge |
| GF Targeting | `double[61]` | — | — | — | Deferred (MlpGfTargeting skeleton, uniform output) |

Models are embedded as Base64 in Java source. Decoded at first use via `GbmTreeEnsemble.load()`.

**Training data:** 50-opponent rumble + local battles, ~1.7M tick rows.

**WindowFeatures** (20-tick sliding mean+std of 10 base features) are the single most important ML innovation: without them, movement R² drops from 0.87 → 0.07.

---

## 7. Module Boundaries

```
core/src/main/java/cz/zamboch/autopilot/core/
├── Feature.java, Whiteboard.java, Transformer.java, WaveRecord.java
├── gun/           VirtualGunManager, VcsGun, VcsSamplingGun
├── movement/      MovementStrategyManager, PathPlanner, WaveSurfMovement
│                  IPositionDanger, WallDistancePositionDanger
│                  IWaveDanger, VcsWaveDanger, CandidatePosition
├── physics/       RobotPhysics, RobotState, MutableRobotState
│                  PrecisePredictor, ReachableEnvelope, EnvelopeData, EnvelopeGenerator
├── predictors/    IPredictor<T>, PredictorRegistry, IGfTargetingPredictor
├── strategy/      IGunStrategy, IMovementStrategy, IRadarStrategy
│                  StrategyComputer, StrategyParams, MovementCommand, VirtualBullet
├── features/      PositionFeatures, SpatialFeatures, MovementFeatures, EnergyFeatures
│                  TimingFeatures, IdentityFeatures, TargetingFeatures
│                  MultiWaveFeatures, EnvelopeFeatures, CombatProgressFeatures
│                  WindowFeatures, ScanCoverageFeatures
├── ml/            GbmTreeEnsemble, FeatureMapping, TickBudget
├── persistence/   IPersistable, PersistenceManager, VcsHistogramStore
└── util/          RoboMath, RingBuffer

robot/src/main/java/cz/zamboch/
├── Autopilot.java              Wiring + event handling
├── distilled/                  ML model data + predictors
│   ├── GbmFirePowerPredictor, GbmMovementPredictor, GbmFireTimingPredictor
│   ├── FirePowerData, MovementData, FireTimingData (Base64-embedded trees)
│   ├── PredictiveGun (ML-based gun strategy)
│   ├── MlpGfTargeting (skeleton — deferred)
│   ├── DefaultDataFile (embedded persistence fallback)
│   └── OpponentProfileData (strength rating lookup)
└── trivial/                    Simple strategies
    ├── EnergyRatioStrategyComputer
    ├── OrbitalMovement
    └── NarrowLockRadar

pipeline/                       Offline CSV processing only (not shipped)
```

**Rules:**
- **core** — all interfaces, managers, features. No I/O. Ships with robot.
- **robot** — concrete implementations + `Autopilot` wiring. Depends on core only.
- **pipeline** — offline processing. Depends on core. Not shipped with robot.

---

## 8. Known Issues & Gaps

| # | Issue | Impact | Status |
|---|-------|--------|--------|
| 1 | VCS only 6 segments (3 dist × 2 dir) | Coarse targeting profile; top bots use 47+ | Open |
| 2 | VCS cold-start | ~50+ wave observations per segment needed to converge | Mitigated by Gaussian prior + cross-battle persistence |
| 3 | TickBudget ratcheted to 10 trees permanently | All 3 GBM models ran at 5% capacity | **Fixed 2026-05-09** — upward recovery + startup immunity |
| 4 | `OUR_DIST_TO_WALL_MIN`, `OUR_LATERAL_VELOCITY` were pipeline-only | Features NaN at runtime; ML models got garbage for these | **Fixed** — now computed in `PositionFeatures` |
| 5 | `VcsSamplingGun` still in core | Imported but not registered in VGM gun list | Low — dead code |
| 6 | Virtual bullet hit check uses fire-time distance | Opponent moves during bullet flight; approximation | Low |
| 7 | Orbit-primary movement beats constant wave surfing | Wave surf reachable-envelope oscillation hurt more than it helped | Architecture finding |
| 8 | 47% opponent HR against top-50 | Movement too predictable for GF-targeting opponents | Core competitive gap |

---

## 9. Evolution Path

| Phase | State | Description |
|---|---|---|
| 1-4 | **Done** | Features, path planning, VCS, energy strategy, persistence |
| 5 | **Done** | ML distillation: 3 GBM models (fire power, movement, fire timing) |
| 6 | **Done** | Predictions wired into consumers; VCS persistence; PredictiveGun |
| 7 | **Done** | TickBudget fix; orbit-primary movement; gun re-ordering |
| 8 | Planned | Online learning: Bayesian blending, adaptation detection |
| 9 | Future | Per-family GF priors, GF flattening defense, competition entry |

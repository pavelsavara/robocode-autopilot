# robocode-autopilot — Project Plan (v2)

*Created: 2026-05-05 · Previous plan: [archive/2026-05-04-plan.md](archive/2026-05-04-plan.md)*

## Vision

Build a competitive Robocode 1v1 robot powered by offline-trained ML models
distilled to Java. The robot uses a multi-strategy architecture with virtual
guns, competing movement strategies, and a 4-axis strategic mode layer.

## Project Status Summary

| Phase | Status | Key Deliverable |
|---|---|---|
| 1. Battle recording & rumble | **Done** | 50-bot rumble, CI, ~1944 battles |
| 2. Feature engineering pipeline | **Done** | 80+ features, ticks/waves/scores CSV |
| 3. Statistical exploration | **Done** | 14 notebooks, honest baselines |
| 4. GBM model training | **Done** | Fire power R²=0.960, fingerprint 0.516 |
| 5. Robot architecture (Phase 1) | **Done** | Trivial predictors, full decision wiring |
| 6. Wave stacking research | **Done** | Niche tactic; multi-wave defense priority |
| 7. Feature additions + retrain | **Done** | Multi-wave, envelope, combat features; R² 0.931→0.960 |
| 7e-m. Robot improvements | **Done** | VCS gun/danger, energy strategy, persistence, interpolation |
| 8. Path planning | **Done** | ReachableEnvelope, WaveSurfMovement, danger scorers |
| **9. ML distillation to Java** | **Next** | GBM trees → Java, MLP weights → Java |
| **10. Online learning** | **Future** | VCS + Bayesian prior blending |

---

## Honest ML Baselines (Authoritative)

All numbers post-leakage-fix. See [wiki/ml-results.md](wiki/ml-results.md) for details.

| Task | Model | Metric | Value | Notes |
|---|---|---|---|---|
| Fire power | XGBoost (window) | R² | **0.960** | New features +0.029 lift |
| Fire power | XGBoost (window) | MAE | **0.072** | |
| Round outcome | XGBoost (early 100) | Accuracy | **0.528** | Dropped — energy ratio sufficient |
| Round outcome | XGBoost (early 100) | AUC | **0.545** | |
| Fingerprint (N=20) | LightGBM | Top-1 | **0.488** | Regressed from 0.516 — investigate |
| Fingerprint (N=20) | LightGBM | Top-1 | **0.516** | Clean, 26× random baseline |
| Fingerprint (N=20) | LightGBM | Top-1 | **0.516** | Clean, 26× random baseline |
| GF targeting | MLP [16→128²→64→61] | ±3 bins | **0.570** | Data-starved (11k samples) |
| Movement N=5 | GBM-window | R² | **0.735** | 20-tick windows are key |
| Movement N=5 | LSTM | MAE | **2.08** | Beats GBM on MAE |
| Fire timing (3-tick) | GBM-window | AUC | **0.863** | LSTM fails (0.519) |

**Key insight across all tasks:** 20-tick sliding window features are the single
most important innovation. Without them, no model exceeds R²=0.07 on movement
or R²=0.572 on fire power.

---

## Current Milestone: Phase 7 — Feature Additions + Path Planning

### 7a. Multi-Wave Pressure Features ✅

Add three features to the Feature enum and pipeline, then re-run CSV generation:

| Feature | Type | Source | Why |
|---|---|---|---|
| `NEAREST_OPPONENT_WAVE_GAP` | tick | Multi-wave tracking | 34% of GF importance in nb13 |
| `TOTAL_OPPONENT_WAVE_DAMAGE` | tick | Multi-wave tracking | Danger weighting for movement |
| `NEAREST_OUR_WAVE_GAP` | tick | Multi-wave tracking | Offensive wave spacing signal |

**Depends on:** Multi-wave `List<Wave>` tracking already in Whiteboard
(added with `N_OPPONENT_WAVES_IN_FLIGHT` / `N_OUR_WAVES_IN_FLIGHT`).

### 7b. Opponent Profiles (replaces Round Outcome Predictor)

The `PREDICTED_WIN_PROBABILITY` predictor was removed — energy ratio alone
provides the "am I winning?" signal. The strategy layer now uses:

1. **`ENERGY_RATIO`** (live, per-tick) — direct combat state
2. **`OPPONENT_STRENGTH_RATING`** (offline lookup) — overall win rate
3. **`OUR_POSITION_ADVANTAGE`** (offline heatmap) — per-opponent position value

**Archetype clustering was attempted and dropped** (see
[archive/2026-05-06-opponent-profiles.md](archive/2026-05-06-opponent-profiles.md)):
K-Means on tick-level means failed (silhouette=0.222, BeepBoop + ScalarR in
same cluster). Named archetypes aren't actionable without specialized
counter-strategy tools (anti-surfer gun, wall-projected targeting). The
fingerprint classifier (51.6% top-1) is wrong 48% of the time — too unreliable
for hard strategy switches. Identity hashes (`OPPONENT_BOT_ID_HASH`) already
let ML models learn per-bot patterns directly.

**Notebook 15** (`15_opponent_profiles.ipynb`): computes strength ratings,
generates quarter-field position heatmaps (20px grid).
Outputs a Java lookup table (`OpponentProfileData.java`).

### 7c. Re-run Pipeline ✅

After adding features:
1. Rebuild pipeline Docker image
2. Re-process existing recordings → new CSVs
3. Retrain all models on enriched schema

### 7d. Path Planning Implementation ✅

From [archive/2026-05-03-path-planning.md](archive/2026-05-03-path-planning.md):

| Component | Description | Status |
|---|---|---|
| `ReachableEnvelope` | Pre-computed byte[] tables, jittered subsample, zero-alloc | Done |
| `RobotPhysics.step()` | Exact Robocode kinematics | Done |
| `IPositionDanger` impl | `WallDistancePositionDanger`: wall + corner + distance | Done |
| `IWaveDanger` impl | `UniformWaveDanger`: Gaussian at GF=0, damage-weighted | Done (Phase 1) |
| `PathPlanner` | Envelope → score → select lowest-danger candidate | Done |
| `WaveSurfMovement` | IMovementStrategy using PathPlanner, competes with others | Done |
| `ICandidatePruner` | `KeepAllPruner` (VCS danger is cheap enough) | Done |

### 7e. Energy-Ratio Strategy Computer ✅

Replace `TrivialStrategyComputer` with energy-aware logic:

```
aggression = energyRatio > 0.6 ? 0.8 : energyRatio > 0.4 ? 0.5 : 0.2
firePower  = opponentEnergy < 4 ? opponentEnergy         // guaranteed kill
           : opponentEnergy < 20 ? 3.0                   // finish shot
           : 1.0 + aggression                            // normal
preferredDistance = 350 (stable, not random)
```

Also read `OPPONENT_STRENGTH_RATING` when available:
against strong opponents (>0.7) → default defensive, against weak (<0.3) → aggressive.

### 7f. Inter-Round State Persistence ✅

Currently `ensureInitialized()` creates fresh objects every round. Change to:
- **Persist across rounds:** VGM hit history, movement damage stats, Whiteboard
  cumulative counters (`roundsWon`, `roundsLost`)
- **Reset per round:** Per-tick features, wave lists, per-round counters
- Fix `initialized` flag to distinguish "first round ever" from "new round"

This lets the VGM learn which gun works across all 35 rounds, and the
movement manager accumulate strategy performance data.

**Cross-battle persistence** via `getDataFile()` (Robocode Advanced Robot API):
- Binary file, 200KB limit, versioned header
- Structure: `[magic:4][version:4][sectionCount:4][sections...]`
- Each section: `[sectionId:4][length:4][data:length]`
- Subsystems register sections dynamically (VGM stats, VCS histograms,
  opponent profile cache, fingerprint results)
- Version constant in Whiteboard — bump on schema change → auto-invalidate
  old data files
- Write at battle end (`onBattleEnded`), read at battle start

### 7g. Wave-Time Urgency in Danger Scoring ✅

Modify `UniformWaveDanger` to weight by wave proximity:
```
urgency = 1.0 / max(1, ticksUntilBreak)
danger = gfDanger × urgency × damage
```
Imminent waves (2-3 ticks) dominate; distant waves (30+ ticks) are background.
Currently all waves have equal weight regardless of arrival time.

**Multi-wave conflict resolution**: When multiple imminent waves have
conflicting safe GF zones, randomly select which wave to prioritize
with probability proportional to `damage × urgency`. This makes our
surfing unpredictable to opponents that model our dodge pattern
(a flattening strategy). Without this, we always dodge the highest-damage
wave, which is exploitable.

### 7h. Kill-Shot Logic ✅

In the fire decision:
```
if (opponentEnergy < 4) firePower = opponentEnergy;  // exact kill
if (opponentEnergy < 0.5) firePower = 0.1;           // minimum to finish
```
Prevents wasting 3.0 power on an opponent with 2.0 energy (overkill = wasted energy).

### 7i. VCS Gun (Visit Count Statistics) ✅

New `VcsGun implements IGunStrategy`:
- Maintain a 61-bin GF histogram, updated at each wave-break
- Segmented by distance (3 bins: close/mid/far) and lateral velocity direction
- Fire at the peak bin
- This is the standard competitive Robocode gun — expected ~12-15% hit rate
  vs current ~5% from head-on/linear/circular

The VCS histogram also feeds `IWaveDanger` for movement (7j).

**Remove `RandomGfGun`** — obsoleted by VCS. Keep HeadOn/Linear/Circular
as they're useful against specific simple opponents (the VGM will naturally
stop selecting them when VCS outperforms).

### 7j. VCS-Based Wave Danger (replaces UniformWaveDanger) ✅

Replace Gaussian-at-GF=0 with the opponent's actual GF histogram:
```
danger(candidate, wave) = opponentVcsHistogram[gfBin(candidate, wave)]
```
This makes the robot dodge AWAY from where the opponent actually aims,
instead of dodging toward head-on (which is where most bots aim).

Requires VCS data sharing between the gun system (7i) and movement system.

### 7k. Opponent Name Lookup on First Scan ✅

On the first `onScannedRobot`:
1. Hash opponent name → `OPPONENT_BOT_ID_HASH`
2. Look up `OpponentProfileData` (generated by nb15)
3. Set `OPPONENT_STRENGTH_RATING` → influences fire power budget
4. Set `OUR_POSITION_ADVANTAGE` / `OPPONENT_POSITION_ADVANTAGE` per tick from heatmap

Requires `OpponentProfileData.java` (auto-generated lookup table, ~200 entries).

**Note**: The position heatmap from nb15 measures STARTING position advantage
(initial battlefield placement). For per-tick position advantage during combat,
`OUR_POSITION_ADVANTAGE` should be updated each tick by looking up the current
cell in the heatmap — this gives a continuous "is my current position good?"
signal, not just the opening. The heatmap itself is still computed offline
from round outcomes vs position at early ticks (first 100).

### 7l. Distilled ML Predictor Skeletons ✅

Created Java skeleton classes in `robot/src/.../distilled/` for Phase 8:

| Class | Interface | Input features | Output |
|---|---|---|---|
| `GbmFirePowerPredictor` | `IInGameFeatures` | 80 tick features → `PREDICTED_FIRE_POWER` | Tree if/else |
| `GbmFireTimingPredictor` | `IInGameFeatures` | 80 tick features → `PREDICTED_OPPONENT_FIRES_3` | Tree if/else |
| `GbmMovementPredictor` | `IInGameFeatures` | 80 tick features → `PREDICTED_LAT_VEL_5` | Tree if/else |
| `MlpGfTargeting` | `IGfTargetingPredictor` | 18 features → `double[61]` | Matrix multiply + ReLU |
| `GbmFingerprint` | `IFingerprintPredictor` | 18 features → `FingerprintResult` | Tree if/else |

Each skeleton reads features from Whiteboard, delegates to a generated
`*Model.java` class (auto-generated from Python export), and writes output.
Phase 8 fills in the generated model code.

### 7m. Feature Interpolation on No-Scan Ticks ✅

When `onScannedRobot` doesn't fire (radar miss), opponent features are stale.
Add to `onStatus`:
```
if (!scanThisTick && lastScanAge < 5) {
    interpolate opponent position: oppX += oppVel × sin(oppHeading)
    recompute distance, bearing, wave distances
}
```
Keeps movement decisions fresh during 1-3 tick scan gaps.

---

## Future Milestones

### Phase 8: ML Model Distillation to Java

Export trained models from Python to Java code. Each model becomes a
generated Java class with no runtime dependencies (no XGBoost library,
no ONNX runtime — pure Java 8 if/else trees or matrix math).

**GBM tree export** (fire power, fire timing, movement, fingerprint):
1. Python: `model.get_booster().get_dump(dump_format='json')` → tree JSON
2. Script: convert each tree to nested `if (feature[i] < threshold) ... else ...`
3. Generate `GbmFirePowerModel.java` with `predict(double[] features)` method
4. Wire into `GbmFirePowerPredictor` skeleton from 7l

**MLP forward pass** (GF targeting):
1. Python: export weight matrices as Java `double[][]` constants
2. Generate `MlpGfTargetingModel.java` with layer-by-layer forward pass:
   `h1 = relu(W1 × input + b1); h2 = relu(W2 × h1 + b2); out = softmax(W3 × h2 + b3)`
3. Pre-allocate intermediate buffers (zero per-tick allocation)
4. Wire into `MlpGfTargeting` skeleton

| Model | Export format | Generated class | Size | Runtime cost |
|---|---|---|---|---|
| Fire power (XGBoost, 800 trees) | Nested if/else | `GbmFirePowerModel` | ~50 KB | <0.1ms |
| Fire timing (XGBoost) | Nested if/else | `GbmFireTimingModel` | ~50 KB | <0.1ms |
| Movement (XGBoost) | Nested if/else | `GbmMovementModel` | ~50 KB | <0.1ms |
| Fingerprint (LightGBM, 500 trees) | Nested if/else | `GbmFingerprintModel` | ~100 KB | <0.1ms |
| GF targeting (MLP 16→128²→64→61) | Matrix constants | `MlpGfTargetingModel` | ~124 KB | <0.5ms |

**Feature mapping**: each model needs a `featureIndex[]` mapping from
Feature enum ordinals to the model's input vector positions. Generated
alongside the model code from the training script's `feature_cols` list.

**Validation**: unit test each generated model against the Python model's
predictions on 100 sample inputs (exported as test fixtures).

**GBM vs LSTM for movement (open question):**
- GBM-window: R²=0.735, simple tree export, no recurrent state needed
- LSTM: MAE=2.08 (12% better), requires 20-tick state buffer in Whiteboard,
  matrix multiply per tick
- Decision deferred to after retraining with enriched features

### Phase 9: Online Learning & Adaptation

- **VCS (Visit Count Statistics)** histogram per opponent — online GF tracking
- **Bayesian blending**: MLP prior + VCS online via λ = K/(K+n) mixing
- **Per-family GF priors** loaded from resource files after fingerprint identification
- **Adaptation detector** (KS-distance) for mid-battle strategy switching

### Phase 10: Competition & Iteration

- Enter LiteRumble / submit to RoboRumble
- More battle seasons for training data
- Per-opponent policy tuning (currently ~2 battles per pair, need 10+)

---

## Architecture Reference

See [wiki/architecture.md](wiki/architecture.md) for the full architecture diagram.

```
core/     — In-game logic. No I/O. Interfaces use IInGameFeatures.
              Features are stateless — all state lives in Whiteboard.
pipeline/ — Offline processing. CSV output, .br replay.
robot/    — Competition robot. Depends on core only.
```

**Decision flow (per tick):**
```
Scan → Whiteboard → Transformer (features + scalar predictors)
  → VirtualGunManager.onScan() (all gun strategies fire virtual bullets)
  → StrategyComputer.compute() (every 50 ticks → StrategyParams)
  → MovementStrategyManager.getActiveCommand() → setAhead/setTurn
  → IRadarStrategy.getRadarTurn() → setTurnRadar
  → VirtualGunManager.getGunTurnAngle() → setTurnGun
  → shouldFire() → setFire()
```

---

## Notebook Status

| # | Notebook | Status | Notes |
|---|---|---|---|
| 01 | Data overview | Current | |
| 02 | Correlations | Current | |
| 03 | Clustering | Current | |
| 04 | Simple ML (leakage audit) | Historical | Educational value only |
| 05 | Movement prediction | **Stale outputs** | Re-run to match nb11 corrections |
| 06 | Bot fingerprinting | Current | |
| 07 | Round outcomes | **Historical** | Replaced by opponent profiles (nb15) |
| 08 | Wave analysis | Current | |
| 09 | Adaptation signal | Current (negative result) | |
| 10 | Online fingerprint | Current | |
| 11 | Scan gap density | Current | Key nb05 correction |
| 12 | Wave stacking | Current | |
| 13 | Multi-wave GF | Current | |
| 14 | GBM model analysis | Current (authoritative) | |

---

## Key Decisions Log

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | Java 8 target | Required for Robocode engine classloader |
| 2 | 50-bot rumble | Broad competitive coverage |
| 3 | Stateless features | All inter-tick state in Whiteboard |
| 4 | Observable-only discipline | No god-view data in features |
| 5 | 20-tick sliding windows | Key innovation for all ML tasks |
| 6 | GBM-window as default model | Simpler distillation, strong baselines |
| 7 | LSTM kept as upgrade option | Wins on movement MAE (+12%) |
| 8 | Round outcome = context dimension | Feeds StrategyParams, influences all predictors |
| 9 | Multi-wave defense over wave-stacking offense | Research shows stacking is niche |
| 10 | Path planning with reachable envelopes | 10-tick horizon, 6-7 no-regret first moves |

---

## Documentation Index

- **Wiki:** [wiki/](wiki/) — Knowledge base (physics, features, leakage, ML results, architecture, pipeline, strategy)
- **Archive:** [archive/](archive/) — Historical planning documents with date prefixes
- **Intuition notebooks:** [intuition/](intuition/) — Jupyter notebooks with inline analysis
- **Copilot instructions:** [.github/copilot-instructions.md](.github/copilot-instructions.md) — Coding conventions and leakage rules

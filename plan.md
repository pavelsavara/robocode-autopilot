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
| 4. GBM model training | **Done** | Fire power R²=0.931, fingerprint 0.516 |
| 5. Robot architecture (Phase 1) | **Done** | Trivial predictors, full decision wiring |
| 6. Wave stacking research | **Done** | Niche tactic; multi-wave defense priority |
| **7. Feature additions + retrain** | **Next** | Multi-wave features, re-pipeline, retrain |
| **8. Path planning** | **Next** | Reachable envelopes, danger scoring |
| **9. ML distillation to Java** | **Future** | GBM trees → Java, MLP weights → Java |
| **10. Online learning** | **Future** | VCS + Bayesian prior blending |

---

## Honest ML Baselines (Authoritative)

All numbers post-leakage-fix. See [wiki/ml-results.md](wiki/ml-results.md) for details.

| Task | Model | Metric | Value | Notes |
|---|---|---|---|---|
| Fire power | XGBoost (window) | R² | **0.931** | `opponent_energy_wstd` (38%) drives it |
| Fire power | XGBoost (window) | MAE | **0.094** | |
| Round outcome | XGBoost (early 100) | Accuracy | **0.520** | Collapsed — needs deeper exploration |
| Round outcome | XGBoost (early 100) | AUC | **0.532** | |
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

### 7a. Multi-Wave Pressure Features

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

### 7c. Re-run Pipeline

After adding features:
1. Rebuild pipeline Docker image
2. Re-process existing recordings → new CSVs
3. Retrain all models on enriched schema

### 7d. Path Planning Implementation

From [archive/2026-05-03-path-planning.md](archive/2026-05-03-path-planning.md):

| Component | Description | Priority |
|---|---|---|
| `ReachableEnvelope` | Pre-compute reachable positions at t+N from (v, θ). Replace stub. | High |
| `RobotPhysics.step()` | Already implemented — used by PrecisePredictor | Done |
| `IPositionDanger` impl | Wall proximity + corner penalty + distance-to-enemy | High |
| `IWaveDanger` impl | GF-based danger using per-wave MLP or VCS lookup | High |
| `PathPlanner` | Enumerate candidates → prune → score → select best first-move | High |
| `ICandidatePruner` impl | Phase 1: `KeepAllPruner` (already exists). Phase 2: MLP pruner | Low |

**Target:** Replace round-level movement switching with per-tick wave-surfing
using the 10-tick planning horizon identified in the path-planning research.

---

## Future Milestones

### Phase 8: ML Model Distillation to Java

| Model | Distillation approach | Size | Complexity |
|---|---|---|---|
| Fire power (XGBoost) | Export trees as nested if/else, or XGBoost4J-core | ~50 KB | Low |
| Fingerprint (LightGBM) | Export trees as if/else chains | ~100 KB | Low |
| Fire timing (GBM) | Same as fire power | ~50 KB | Low |
| GF targeting (MLP) | Hand-code forward pass: matrix multiply + ReLU | ~124 KB weights | Medium |
| Movement (GBM or LSTM) | GBM: if/else trees. LSTM: forward pass with state buffer | ~88 KB | Medium–High |

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

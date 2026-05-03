# robocode-autopilot — Project Plan

*Updated: 2026-05-03*

## Project Goal

Build a competitive Robocode robot powered by machine learning, trained on data from battles between top-50 bots. The pipeline: record battles → extract features → statistical analysis → ML models → distill into a competition robot.

## What's Done

### Stage 1: Battle Recording & Rumble Infrastructure ✅

- **CI workflow** (`run-season.yml`) runs automated 50-bot rumble seasons on GitHub Actions
- Docker image `ghcr.io/pavelsavara/robocode-autopilot/battle-runner` handles headless battles
- Multi-season auto-chaining with `min_battles_per_pair` threshold (currently 2)
- 22 workflow runs completed, producing hundreds of 35-round `.br` recordings
- Rankings published to GitHub Pages (`pavelsavara.github.io/robocode/`)
- **50 bots** participating (top competitive bots: DrussGT, Diamond, BeepBoop, Shadow, etc.)
- Scripts: `scrape-wiki.mjs`, `plan-battles.mjs`, `run-battle.mjs`, `compute-rankings.mjs`, `generate-pages.mjs`

### Stage 2: Feature Engineering Pipeline ⚠️ additions needed

- 3-module Gradle project: `core` (in-game logic), `pipeline` (offline processing), `robot` (competition bot)
- Java 8 target (required for Robocode engine compatibility)
- **28 features implemented** out of 443 specified in the catalog
- **Opponent identity**: `opponentName` captured from first scan event, `OPPONENT_NAME_HASH` (FNV-1a 32-bit) as numeric feature for ML segmentation
- Pipeline: `.br` file → Loader → Player (scan synthesis, event replay) → Whiteboard → Transformer → CsvWriter
- Dual-perspective output (both robots' views per battle)
- All 23 unit + integration tests passing
- Output: `ticks.csv`, `waves.csv`, `scores.csv` per battle per robot perspective

**Implemented features (28 + 1 identity):**
- Identity (1): opponent name hash (FNV-1a 32-bit of opponent name string — stable bot fingerprint)
- Core 12: distance, bearing, opponent velocity/lateral/advancing, heading delta, energy, fired detection, fire power, gun heat, ticks since scan, opponent wall distance
- Phase H (16): lateral direction, velocity delta, is decelerating, time since direction change, angular velocity, max turn rate, distance norm, energy ratio, our lateral velocity, our wall distance, opponent wall ahead distance, opponent inferred gun heat, combat state features

**Additions needed** (blocking Steps 3–5):

1. **Absolute positions in ticks.csv** (blocking position danger training):
   - `OUR_X`, `OUR_Y` — our absolute position (already on `Whiteboard.getOurX/Y()`)
   - `OUR_HEADING`, `OUR_VELOCITY` — raw heading and velocity (lateral exists, raw doesn't)
   - `OPPONENT_X`, `OPPONENT_Y` — opponent absolute position (on `Whiteboard.getOpponentX/Y()`)
   - `OPPONENT_HEADING` — absolute heading (delta exists, absolute doesn't)
   All 7 values already exist on the Whiteboard — just not emitted as Feature enum
   entries. New `PositionFeatures implements IInGameFeatures` with no dependencies.
   **Note**: Absolute positions are observable by the in-game robot (`getX()`, `getY()`,
   `getHeading()` from `StatusEvent`; opponent position derived from `ScannedRobotEvent`
   bearing + distance). This does NOT violate observable-only discipline.

2. **Multi-wave tracking in Whiteboard** (blocking multi-wave defense):
   - Replace single-wave tracking (`our_wave_distance`, `opponent_wave_distance`)
     with `List<Wave>` for both our and opponent active waves.
   - Add per-tick features: `N_OPPONENT_WAVES_IN_FLIGHT`, `N_OUR_WAVES_IN_FLIGHT`
   - Emit to ticks.csv for offline training of multi-wave GF models.
   - The `Wave` class is already designed in the robot architecture doc.

3. **Wave origin positions in waves.csv** (blocking path-planning wave simulation):
   - `our_x_at_fire`, `our_y_at_fire` — observer position at opponent fire detection
   - `opponent_heading_at_fire` — opponent heading at fire time
   These enable reconstructing exact wave geometry for path-planning training.

**After these additions**: re-run CI pipeline on existing recordings to
regenerate CSVs, then retrain Step 3 models on the enriched schema.

### Intuition Phase ✅

9 Jupyter notebooks executed against the full 50-bot rumble dataset.
See Step 2 below for honest baselines and gaps. Headline numbers:
fire-power R² = 0.81, round-outcome accuracy 0.706, bot fingerprint
0.349 top-1 over 44 classes, movement R² ≤ 0.07.

> The original Phase-1 "98.88 % fire prediction" claim was retracted in
> Phase 5 — `opponent_inferred_gun_heat` is a 1:1 algebraic restatement
> of the target (gun heat resets to `1 + power/5` on the fire tick).
> Leakage filters now codified in [`intuition/_loader.py`](../intuition/_loader.py).

### Local Recordings Downloaded

| CI Run ID | Files | Size | Notes |
|-----------|-------|------|-------|
| 24360294214 | 10 | 39 MB | Old 5-bot test battles (3 rounds) |
| 24783824060 | 197 | 112 MB | First 50-bot season (1 round each) |
| 24784043472 | 190 | 6 MB | Season 2 (1 round) |
| 24784907991 | 190 | 6 MB | Season 3 (1 round) |
| 24785044560 | 190 | 6 MB | Season 4 (1 round) |
| 24794820810 | 10 | 41 MB | Partial season |
| 24796474717 | 190 | 1,721 MB | Full season (35 rounds) — primary dataset |
| 24796916096 | 1 | 55 MB | Single battle |
| **Total** | **978** | **~1,986 MB** | |

---

## Next Steps

### Step 1: Stage 2 CI Pipeline ✅

Docker image + CI workflow for automated feature extraction.

**Components:**
- `Dockerfile.pipeline` — Alpine + JRE 21, copies Gradle `installDist` output
- `build-docker-pipeline.yml` — Builds pipeline from source, pushes to `ghcr.io/.../pipeline-runner:latest`. Auto-triggers on `core/` or `pipeline/` source changes.
- `process-recordings.yml` — Downloads recording artifacts from a season run, processes `.br` → CSV in parallel chunks via the Docker image, uploads `csv-chunk-*` artifacts (90-day retention)
- `rumble/scripts/download-csv.mjs` — Downloads CSV artifacts from CI to local `output/csv/`

**Flow:**
```
run-season.yml → recordings-chunk-* artifacts
       ↓ (workflow_run trigger)
process-recordings.yml → csv-chunk-* artifacts
       ↓ (manual, local)
download-csv.mjs → output/csv/{battleId}/{perspective}/ticks.csv
```

**Design decisions:**
- CSVs are pipeline artifacts only (not stored on a git branch)
- Parallel chunk processing (same pattern as battle runner)
- Per-battle CSV granularity preserved (not concatenated)
- Docker image rebuilt automatically on source changes

### Step 2: Expanded Intuition Analysis ✅

Five Phases of intuition notebooks executed against the full 50-bot rumble
dataset (1944 battles, ~3.9 k ticks.csv files, ~20 GB on disk). Stratified
loading via [`intuition/_loader.py`](../intuition/_loader.py) keeps each
notebook within ~300 MB of pandas memory. Detailed write-ups in
[planning/archive/2026-05-02-intuition-5.md](archive/2026-05-02-intuition-5.md);
the original Phase 1 "98.88 % fire prediction" headline was retracted as
gun-heat leakage.

**Honest baselines (post-leakage-filter):**

| Question | Notebook | Baseline | Honest result | Lift |
|---|---|---:|---:|---:|
| Q1 — Movement prediction (lat-vel, N=5..20) | nb05 | mean (R²=0) | RF R² ≤ 0.07, MAE −25 % | persistence is *negative* R² |
| Q2 — Movement archetypes | nb03/05 | — | K=5 reproduces oscillator / random / wall-hugger trio | qualitative |
| Q3 — Periodicity | nb05 → **nb11** | — | **per-round** autocorrelation: lag-1=0.984, lag-5=0.785 | **nb05 was wrong** (pooled cross-round) |
| Q4 — Round-to-round adaptation | nb09 | majority 0.935 | per-round-aggregate RF 0.914 *(worse than majority)* | KS ≥ 0.10 cleanly flags 10/50 bots |
| Q5 — Fire power prediction | nb07 | mean MAE 0.712 | RF **MAE 0.21, R² 0.81** | −71 % MAE |
| Q6 — Round outcome | nb07 | majority 0.506 | RF **0.706 accuracy** | +20 pp |
| Q7/Q8 — Bot fingerprinting | nb10 | random 0.023 | 10-stat wave fingerprint **0.349 top-1 / 44 classes**, best at N=20 fires | 15× random |
| Q10 — 3-cluster game state | nb03 | — | holds at 50-bot scale | qualitative |

**Codified leakage discipline** — added during this phase:
[`_loader.WAVE_DERIVED_COLS`](../intuition/_loader.py),
`FIRE_POWER_LEAKAGE_COLS` (12 cols), `BATTLE_CONSTANT_COLS`,
`drop_redundant_features()`. The "'God view' vs target-relative leakage"
section in [`copilot-instructions.md`](../.github/copilot-instructions.md)
captures the pattern. Three Phase-1/3 "100 % accuracy" headlines (`opponent_fired`,
`opponent_fire_power`, gun-heat) collapsed to honest baselines under the
filters.

**Architecture constraints surfaced for Step 3:**
- Per-tick state alone cannot predict fire timing (`opponent_fired`
  collapses to majority baseline). Targeting/timing models need a
  sliding window of opponent state.
- ~~Lateral velocity is memoryless tick-to-tick~~ **CORRECTED in nb11:**
  per-round autocorrelation is 0.984 at lag-1. nb05 pooled across round
  boundaries, destroying temporal structure. 20-tick sliding windows
  unlock R²=0.735 for movement prediction.
- The 0.81 R² fire-power baseline and the 0.706 round-outcome baseline
  are the floor any new model must beat.

**Gaps deferred (not blockers for Step 3):**
- **Q9 — pairing interaction effects** (DrussGT vs Diamond vs Saguaro):
  not isolated as its own study.
- **Q11 — winning game states**: distance / energy-ratio matter for round
  outcome (nb07), but not crossed against the K=3/K=5 cluster IDs.
- ~~Multi-tick movement signal~~ **RESOLVED in nb11 + Step 3:**
  Per-round autocorrelation is high (0.984 at lag-1). 20-tick windowed-GBM
  achieves R²=0.735 at N=5. LSTM adds no value over GBM-window.
- **Adaptation classifier**: per-round aggregates fail (negative result
  documented). The right shape is a sequence model on the round-by-round
  distribution stream; the KS detector itself produces the label so
  there's no useful supervised target until a sequence model exists.

**Notebooks delivered:**
- [`01_data_overview.ipynb`](../intuition/01_data_overview.ipynb)
- [`02_correlations.ipynb`](../intuition/02_correlations.ipynb)
- [`03_clustering.ipynb`](../intuition/03_clustering.ipynb)
- [`04_simple_ml.ipynb`](../intuition/04_simple_ml.ipynb) — leakage-free `opponent_fired` audit
- [`05_movement_prediction.ipynb`](../intuition/05_movement_prediction.ipynb) — Q1–Q4
- [`06_bot_fingerprinting.ipynb`](../intuition/06_bot_fingerprinting.ipynb) — Q7–Q9 (Q9 partial)
- [`07_round_outcomes.ipynb`](../intuition/07_round_outcomes.ipynb) — Q5, Q6, Q10
- [`09_adaptation_signal.ipynb`](../intuition/09_adaptation_signal.ipynb) — Q4 KS detector + classifier null result
- [`10_online_fingerprint.ipynb`](../intuition/10_online_fingerprint.ipynb) — Q7/Q8 wave-trajectory variant

### Step 3: ML Architecture Design ⚠️ redesign needed

Three architectures trained offline on the 50-bot rumble dataset (1944 battles).
All training runs on CPU within GitHub Actions free-tier limits (~45 min total).
Architecture docs in [`planning/archive/2026-05-02-plan-*.md`](archive/).
Training scripts in `intuition/train_gbm.py`, `train_mlp_targeting.py`, `train_sequence.py`.

**Critical correction:** nb05's "autocorrelation ≤ 0.03" was wrong — it pooled
ticks across round boundaries. Per-round autocorrelation (nb11) shows lag-1 = 0.984,
lag-5 = 0.785, lag-10 = 0.457. Movement is highly correlated within a round.
20-tick sliding windows unlock massive predictive signal (R² 0.07 → 0.735).

**Results (all tasks, GroupKFold on battle_id):**

| Task | Model | Metric | Value | Previous | Lift |
|---|---|---|---|---|---|
| Fire power | XGBoost (tuned+window) | R² | **0.928** | 0.572 | +62 % |
| Fire power | XGBoost (tuned+window) | MAE | **0.097** | 0.319 | −70 % |
| Round outcome | XGBoost (tuned) | Accuracy | **0.882** | 0.863 | +2 pp |
| Round outcome | XGBoost (tuned) | AUC | **0.955** | 0.943 | +1 pp |
| Fingerprint (N=20) | LightGBM (tuned+enriched) | Top-1 | **0.516** | 0.253 | **2×** |
| GF targeting | MLP [16→128²→64→61] | Loss | 3.47 | uniform 4.11 | −16 % |
| GF targeting | MLP | ±3 bins | **0.570** | — | new task |
| Movement N=5 | GBM-window | R² | **0.735** | per-tick RF 0.07 | **10×** |
| Movement N=5 | LSTM | MAE | **2.08** | GBM 2.36 | −12 % |
| Movement N=10 | GBM-window | R² | 0.363 | — | — |
| Movement N=20 | Both | R² | ~0.05 | — | autocorrelation exhausted |
| Fire timing (3-tick) | GBM-window | AUC | **0.863** | majority F1 0.49 | — |
| Fire timing | LSTM | AUC | 0.519 | — | near random |

Key improvements from tuned run: 20-tick window features (rolling mean/std),
RandomizedSearchCV hyperparameter tuning, 10× more data (500 battles vs 250),
8 extra tick-derived fingerprint features (movement variability, direction changes).

**Architecture conclusions:**
- **Windowed-GBM dominates LSTM** for both movement (R² 0.735 vs 0.694) and fire timing
  (AUC 0.863 vs 0.519). Sequential processing adds no value over aggregated window statistics.
- **MLP targeting works** but is data-starved (11k samples). More battles needed.
- **Round outcome model** (AUC 0.943) is the foundation for strategic mode switching.
- **`gf_current_at_power_*`** features moved from pipeline to core — cheap (3× asin + arithmetic),
  enabling real-time GF computation in the competition robot.

**Deliverables:**
- [`planning/archive/2026-05-02-plan-gbm.md`](archive/2026-05-02-plan-gbm.md) — GBM architecture
- [`planning/archive/2026-05-02-plan-mlp-targeting.md`](archive/2026-05-02-plan-mlp-targeting.md) — MLP targeting
- [`planning/archive/2026-05-02-plan-sequence.md`](archive/2026-05-02-plan-sequence.md) — LSTM / sequence
- [`intuition/11_scan_gap_density.ipynb`](../intuition/11_scan_gap_density.ipynb) — scan gap analysis + nb05 correction
- `intuition/models/` — saved model artifacts (gitignored)

**Redesign items** (from nb12/nb13 findings and path-planning research):

1. **MLP targeting: add 3 multi-wave features** (15 → 18 input dims).
   `n_waves_in_flight`, `nearest_wave_gap`, `total_wave_damage` — opponents
   dodge differently under wave pressure (KS=0.145, p=10⁻¹⁹ for tight gaps).
   These carry 34% of RF feature importance in nb13. Retrain needed.

2. **All models: add 4 strategic axes as context features**. Aggression,
   range, counter-strategy, phase are slow-moving (change at most per round)
   and should feed every predictor. Conditions model behavior: "when
   aggressive, favor GF ±0.6." Zero marginal runtime cost. Retrain needed.

3. **MLP targeting: design for Bayesian prior + online update**. The MLP
   outputs a 61-bin GF distribution that blends with online VCS histogram
   via λ = K/(K+n) mixing. This requires the MLP distribution to be
   calibrated (not just peaked at the right bin). Retrain with temperature
   scaling or label smoothing.

4. **Family-specific GF priors**: After fingerprint identification
   (confidence > 0.5), load a pre-computed per-family 61-bin GF prior
   from a resource file (~22 KB for 44 families). This is a new training
   pipeline step: aggregate waves.csv by bot family → compute per-family
   GF histograms → store as `FamilyProfile` resources.

5. **Fire timing → movement integration**: The fire-timing GBM (AUC 0.863)
   outputs `PREDICTED_OPPONENT_FIRES_3` but the movement system doesn't
   consume it. Design: wave-surf strategy uses fire-timing prediction to
   start dodging BEFORE the energy drop is detected (DrussGT's gun-heat
   wave trick). This gains 2 ticks of reaction time at close range.

6. **Candidate pruner training**: After MLP danger scorer is retrained,
   generate 28M labeled candidates (280 per game state × 100k states),
   train `MlpCandidatePruner` [12→32→1] for recall@20 ≥ 80%. See
   [path-planning.md §7](archive/2026-05-03-path-planning.md).

### Step 4: Robot Architecture Prototype & Strategic Mode Switching

Implement the competition robot skeleton with trivial predictors, then
design strategic mode switching. Robot architecture doc:
[`planning/archive/2026-05-03-robot-architecture-plan.md`](archive/2026-05-03-robot-architecture-plan.md).

**Robot architecture (Phase 1 — smoke test):**
- All 6 predictors wired with trivial implementations (random/constant)
- Virtual guns: 4 `IGunStrategy` impls (head-on, linear, circular, random-GF)
- Competing movement: 3 `IMovementStrategy` impls (orbital, random-dodge, stop-and-go)
- Radar: narrow lock
- `IFirePlan` for single-shot and wave-stacking sequences
- `ICandidatePruner` defaulting to `KeepAllPruner`
- Split event flow: features on scan, decisions every tick

**Strategic layer — 4-axis mode system:**
The 4 axes are slow-moving dimensions that feed ALL predictors as context:

| Axis | Source | Controls |
|---|---|---|
| **Aggression** | win probability + energy ratio | Fire power, risk tolerance |
| **Range** | distance + wall geometry | Preferred engagement distance |
| **Counter-strategy** | Fingerprint classifier | Per-family GF priors, policy biases |
| **Phase** | round / numRounds | Explore (early) vs exploit (late) |

Computed by `StrategyComputer`, cached on `Whiteboard`, consumed by
every predictor at zero marginal cost.

**Mode switching architecture:**

1. **State classifier** — K=5 game states from nb03 clustering
2. **Opponent adaptation detector** — KS-distance from round-0 baseline
3. **Policy selector** — maps (state, opponent_type, adaptation_flag, axes)
   → `StrategyParams` (preferred distance, fire power, movement style)

**Depends on:**
- Step 3 models (provides the reward signal + predictor interfaces)
- More seasons for per-opponent policy learning (currently ~2 battles per pair)

### Step 5: Path Planning & Reachable Envelopes

Trajectory-based movement planning with pre-computed reachable envelopes,
candidate pruning, and dual-horizon scoring. See
[`planning/archive/2026-05-03-path-planning.md`](archive/2026-05-03-path-planning.md).

**Simulation results** (from `_run_envelope.py`):
- 5px grid at t=10 → ~280 candidates; 10px → ~70. Envelope fill ~90%.
- Decisions every 2 ticks: 81k states (feasible). Every 3 ticks: 1.6k.
- Trajectory prefix sharing: only 6–7 distinct first moves reach ALL
  top-20 destinations. First 2 ticks are "no-regret."

**Cost model** (MLP danger phase):
- VCS danger: score all 280 candidates in 0.2 ms — no pruner needed.
- MLP danger: 280 candidates costs 18 ms (exceeds budget). Pruner needed.
- `MlpCandidatePruner` [12→32→1] reduces 280 → 20 in 0.2 ms.
- Dual horizon: 10 candidates at t+10 (wave dodge) + 10 at t+20
  (positioning). Total: ~1.7 ms.

**Damage-weighted multi-wave scoring**: Each wave's danger contribution
weighted by `damage(power)`. Power-3.0 bullet = 40× weight of power-0.1.
Makes our surfer immune to wave-stacking distractions.

**Offline learning advantages** (signals only offline can capture):
- Multi-wave GF narrowing: 100k+ waves needed, ~200 per battle
- Position danger heatmap: 1M+ ticks needed, ~3k per battle
- Family-specific GF priors: 50k+ waves per family
- Bayesian prior + online update: offline MLP prior blended with online
  VCS via λ = K/(K+n), working from tick 1

### Step 6: Wave-Stacking Guns & Multi-Wave Defense ✅ research complete

Empirical survey of multi-bullet patterns in the 50-bot rumble.
See [`intuition/12_wave_stacking.ipynb`](../intuition/12_wave_stacking.ipynb),
[`intuition/13_multiwave_gf.ipynb`](../intuition/13_multiwave_gf.ipynb), and
[`planning/archive/2026-05-03-intuition-6.md`](archive/2026-05-03-intuition-6.md).

**Key findings:**
- All 50 bots use variable fire power (std 0.50–0.92). None do deliberate
  wave stacking — convergences are incidental from adaptive power selection.
- Near-simultaneous arrivals (≤5 tick gap): 1.1% of pairs, median 577 px.
- Multi-wave is the **norm**: 96% of fire events have 2+ waves in flight.
- Incidental convergence does NOT improve opponent hit rate (p=0.60).
- **Damage-weighted surfing** neutralizes wave stacking for top bots:
  power-3.0 bullet contributes 40× the danger weight of power-0.1.
- Under tight multi-wave pressure, opponent GF std narrows 17% (nb13,
  KS=0.145, p=10⁻¹⁹). This is a targetable feature for the MLP.

**Conclusions:**
- Wave stacking is a niche anti-mid-tier tactic, not a universal edge.
- Multi-wave **defense** (damage-weighted `IWaveDanger`) is mandatory.
- Multi-wave **features** for MLP targeting are the real offline edge.

---

## Architecture Reference

```
core/     — In-game logic. No I/O. Interfaces use IInGameFeatures.
              Features are stateless — all state lives in Whiteboard.
pipeline/ — Offline processing. CSV output, .br replay.
              Offline feature subclasses extend core base classes.
robot/    — Competition robot. Depends on core only.
```

**Key classes:**
- `Whiteboard` — Central state store, implements event interfaces
- `Transformer` — Feature orchestrator with dependency resolution
- `Feature` enum — Numeric keys for array-backed storage
- `Player` — Replays .br snapshots through synthesized robot events
- `Loader` — Reads .br files (ZIP + ObjectInputStream)

**Feature system:**
- `IInGameFeatures` (core) — base interface, shipped with robot
- `IOfflineFeatures` (pipeline) — extends base, adds CSV support
- Features declare dependencies; resolved once at battle start via topological sort
- All CSV output uses `CsvRowWriter`

---

## Reference Documents (Archived)

Previous detailed planning documents are in [planning/archive/](archive/):
- [2026-05-01-plan.md](archive/2026-05-01-plan.md) — Original full project plan with rumble infrastructure, CI, scoring
- [2026-05-01-stage-2-plan.md](archive/2026-05-01-stage-2-plan.md) — Stage 2 architecture, component design, implementation phases
- [2026-05-01-features.md](archive/2026-05-01-features.md) — Complete 443-feature catalog with formulas
- [2026-05-01-rules-and-known-ideas.md](archive/2026-05-01-rules-and-known-ideas.md) — Robocode physics, competitive strategies, ML prior art
- [2026-05-01-intuition-plan.md](archive/2026-05-01-intuition-plan.md) — Statistical exploration notebook plan
- [2026-05-03-robot-architecture-plan.md](archive/2026-05-03-robot-architecture-plan.md) — Robot decision system, interfaces, wiring
- [2026-05-03-path-planning.md](archive/2026-05-03-path-planning.md) — Reachable envelopes, pruner, dual-horizon, cost model, offline edges
- [2026-05-03-intuition-6.md](archive/2026-05-03-intuition-6.md) — Wave stacking & multi-wave GF findings (nb12, nb13)

## Key Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | Java 8 target | Required for Robocode engine classloader compatibility |
| 2 | 50-bot rumble | Broad competitive coverage (top LiteRumble bots) |
| 3 | `.br` binary recordings | Robocode's built-in format, full god-view fidelity |
| 4 | Stage 2 on CI | Batch processing at scale, CSVs as downloadable artifacts |
| 5 | Feature implementation deferred | Wait for larger dataset analysis before expanding beyond 28 features |
| 6 | Stateless features | All inter-tick state in Whiteboard, not in feature classes |
| 7 | Observable-only discipline | Stage 2 filters to what a real robot API exposes |

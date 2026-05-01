# robocode-autopilot — Project Plan

*Updated: 2026-05-01*

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

### Stage 2: Feature Engineering Pipeline ✅

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

### Intuition Phase ✅

- 4 Jupyter notebooks: data overview, correlations, clustering, simple ML
- Fire prediction baseline: 98.88% accuracy (Random Forest)
- Key finding: `opponent_inferred_gun_heat` dominates fire prediction (88.45% feature importance)
- Dataset: 266,890 tick rows from 10 sample battles (5 bots, 3 rounds each)
- **Limitation**: Analysis was done on tiny dataset (5 bots). Need to re-run on full 50-bot data.

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

### Step 2: Expanded Intuition Analysis

**⚠️ CSV re-generation required:** The `opponent_name_hash` column was added to `ticks.csv` after the initial pipeline runs. Must re-run `process-recordings.yml` on CI and re-download CSVs before the expanded analysis.

Re-run and expand the intuition notebooks on the full 50-bot × 35-round × 5-battle dataset.
See [initial findings](archive/2026-05-01-intuition-initial.md) for what the 5-bot dataset revealed.

**Data prep:**
- Download CSVs from all 5 season process-recordings runs via `download-csv.mjs`
- Expected: ~6000 battles × 2 perspectives × ~500 ticks/round × 35 rounds ≈ 200M+ tick rows

**Questions the larger dataset can answer:**

*Movement prediction (Domain A) — the core ML challenge:*
1. **Can we predict opponent position N ticks ahead?** Train regression on `opponent_x(t+N), opponent_y(t+N)` from current features. Baseline: linear extrapolation vs. circular vs. ML. Error in pixels at N=5,10,20.
2. **Do movement patterns differ by bot archetype?** Cluster bots by their movement feature distributions (lateral velocity variance, direction change rate, wall hugging fraction). How many distinct archetypes emerge from 50 bots?
3. **Are movement patterns periodic/predictable?** Autocorrelation of `opponent_lateral_velocity` at various lags. Which bots are oscillators vs. random movers?
4. **Does opponent movement change across rounds?** Compare feature distributions in rounds 1-5 vs. 30-35 for adaptive bots (DrussGT, Diamond) vs. static bots.

*Targeting/firing (Domain B, C):*
5. **Can we predict opponent fire POWER (not just timing)?** The initial analysis showed fire timing is trivial (gun heat). But fire power selection varies — do bots change power based on distance, energy, or our movement?
6. **What determines round outcomes?** With ~6000 × 35 = 210K rounds (vs. 100 in initial analysis), can we build a reliable round-winner model? Which tick-level features best predict who wins?

*Bot fingerprinting (Domain E):*
7. **Can we identify a bot from its behavior alone?** Train a classifier: given 50 ticks of feature data, predict which of the 50 bots is playing. Which features are most discriminative?
8. **How quickly can we identify a bot?** Accuracy vs. number of observed ticks. Can we classify within the first 20 ticks of round 1?
9. **Do bot pairings affect behavior?** Does DrussGT play differently against Diamond vs. against Saguaro? Interaction effects in the feature space.

*Game state analysis:*
10. **Do the 3 game-state clusters hold at scale?** Re-run K-Means/DBSCAN on 50-bot data. Do more clusters emerge? Are they bot-specific or universal?
11. **Are there "winning" game states?** Which cluster (close/mid/long range) correlates with better outcomes for which bot types?

**Notebook plan:**
- `05_movement_prediction.ipynb` — Questions 1-4 (position prediction, archetypes, periodicity, adaptation)
- `06_bot_fingerprinting.ipynb` — Questions 7-9 (identification, speed, pairings)
- `07_round_outcomes.ipynb` — Questions 5-6, 10-11 (power prediction, win factors, game states)

### Step 3: ML Architecture Design

Based on intuition findings:
- Design targeting models (predict opponent GuessFactor)
- Design movement models (predict enemy aim / dodge direction)
- Evaluate: small MLPs, decision trees, KNN, gradient boosted trees
- Consider sequence models (LSTM) for temporal movement patterns
- Design distillation pipeline for in-game inference

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

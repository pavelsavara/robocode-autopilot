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
- Pipeline: `.br` file → Loader → Player (scan synthesis, event replay) → Whiteboard → Transformer → CsvWriter
- Dual-perspective output (both robots' views per battle)
- All 23 unit + integration tests passing
- Output: `ticks.csv`, `waves.csv`, `scores.csv` per battle per robot perspective

**Implemented features (28):**
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

Once Stage 2 CI produces CSVs from the full 50-bot dataset:
- Download CSVs locally
- Re-run existing 4 notebooks on the larger dataset
- Expand analysis with:
  - Per-bot behavioral fingerprinting (50 bots instead of 5)
  - Cross-bot clustering to discover movement archetypes
  - Feature importance analysis with the full dataset
  - Temporal pattern analysis (35 rounds gives real time-series data)
  - Correlation analysis between features and round outcomes

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

# Amos — History

## Project Seed

- **Project:** robocode-autopilot — competitive Robocode 1v1 robot
- **User:** Pavel Savara
- **Stack:** Java 8, Gradle 9.4.1, PowerShell, Node.js
- **Current state:** Pipeline working end-to-end, ~1944 battles recorded
- **TickBudget bug fixed 2026-05-09** — ceiling now recovers upward
- **Persistence:** 4 sections, ~88 KB VCS data, binary format v1

## Learnings

### 2026-05-09 — Sprint 7
- TickBudget PASS: budget 100–200 trees (full capacity confirmed after fix).
- Skipped turns PASS: only 0.2 per battle (negligible).
- Score improved 10× (0.56% → 5.4%) confirming TickBudget fix value.
- Sanity check scripts still ad-hoc — need standardized reusable diagnostic tooling.
- Next sprint: build persistent sanity check script for automated pre/post evaluation.

### 2026-05-09 — Sanity Check Script
- Built `scripts/sanity-check.ps1` + `scripts/sanity_check.py` automating all 6 Phase 4a mandatory checks + 3 bonus ML checks.
- Data sources: `debug.log` for TickBudget (DATA_SAVE budget=N) and skipped turns (SKIPPED lines); `internal.csv` for gun selection, ML predictions; `ticks.csv` for wave detection and feature completeness.
- TickBudget tree count is NOT in internal.csv — it's only in debug.log via DATA_SAVE and SKIPPED log lines. The `fire_power_budget` column in internal.csv is the fire power budget (0.1–3.0), not the tree count.
- Numpy bools (`np.bool_`) fail Python `is True`/`is False` identity checks. Use truthiness checks (`if x:`) instead of identity (`if x is True:`).
- internal.csv rows are per-scan-tick (sparse), not per-tick. ticks.csv has every tick. Skipped turns detectable from debug.log SKIPPED entries.

### 2026-05-09 — Sprint 9
- Created `FeatureLogger.java` — per-tick feature vector CSV logging activated by `-Dautopilot.featureLog=true`.
- Zero cost when disabled (null pointer check, no allocations). Uses `getDataDirectory()` (sandbox-safe).

### 2026-05-09 — FeatureLogger Wiring
- Wired FeatureLogger into GbmFirePowerPredictor: initLogger/closeLogger methods, log call in process() after prediction.
- Autopilot calls initLogger(getDataDirectory()) during createTransformer(), closeLogger() at top of onBattleEnded().
- Actual value uses Feature.OPPONENT_FIRE_POWER from EnergyFeatures (energy-drop detection). NaN when no fire detected that tick.
- Logger field is null when disabled — single null check per tick, zero heap allocation.
- Fixed pre-existing compilation errors in WaveSurfMovement.java (missing field declarations from Sprint 8).
- Feature names in CSV header match `FirePowerData.FEATURE_NAMES` exactly for direct Python comparison.
- Wired into robot lifecycle via system property.

### 2026-05-09 — Sprint 10 Phase 3 (Build, Deploy, Battles)
- Build + deploy: `gradlew clean :robot:jar` → `cz.zamboch.Autopilot-0.1.0.jar` deployed to `c:\robocode\robots\`.
- Phase 3a (Diagnostic): Ran manual `java` battle with `-Dautopilot.featureLog=true` against FloatingTadpole (10 rounds). FeatureLogger produced `features_fire_power.csv` (677 lines, 620 KB) at `c:\robocode\robots\.data\cz\zamboch\Autopilot.data\`. Saved copy to `output\local\features_fire_power_diagnostic.csv`.
- PowerShell `-Dprop=value` quoting: PowerShell treats `-D` as its own parameter prefix and splits it. Must double-quote: `"-Dautopilot.featureLog=true"`.
- Robot data directory under `-DNOSECURITY=true` is `c:\robocode\robots\.data\cz\zamboch\Autopilot.data\` (dot-separated path segments become directories).
- Phase 3b (Full eval): 48/48 battles completed, 0 skipped, 144 .br recordings. All 16 opponents ran 3 battles × 35 rounds successfully.
- Typical score range: 1%–20% across opponents. Best: BlestPain/ChocolateBar (~20%), Worst: Shadow/Pris/BlackBox (~1%).

### 2026-05-10 — Sprint 10 Phase 4 (Post-MlDerivedFeatures Fix Eval)
- Built, deployed, and ran evaluation after MlDerivedFeatures fix (23 NaN features now computed).

### 2026-05-10 — Sprint 11
- Parallelized pipeline CSV processing: `Main.java` now uses fixed thread pool (default 4 threads). `--threads N` CLI flag. `local-pipeline.ps1` passes `--threads 4`.
- ~4× speedup (16 min → ~4 min). Thread safety is structural — each `processBattle()` has own Whiteboard, Transformer, CsvWriter.
- Ran full eval: score 8.0% (project record), first battle win 58% vs eem.zapper.
- **Cross-agent:** Holden wrote Sprint 11 retro — score 8.0%, R² −1.12, skipped turns regression (~12.6 avg from MlDerivedFeatures overhead).
- Sprint 12 priority: fix skipped turns (check #2 failing), profile MlDerivedFeatures.
- Deleted stale autopilot.dat at `c:\robocode\robots\.data\cz\zamboch\Autopilot.data\autopilot.dat` (clean eval).
- Pipeline completed 34/48 battles (12 of 16 opponents). Missing: ary.Help, gh.GresSuffurd, rdt.AgentSmith.AgentSmith, fromHell.BlackBox. Pipeline stopped early — likely timeout or error during opponent 12 (kid.Gladiator only got 1/3 battles).
- **Recording replay issue:** The pipeline binary output CSV to stdout but 0 new CSV directories were written to `output/local/csv/`. All existing 96 CSV dirs are from previous 05/09 run. This blocked fresh ML sanity checks.
- **Score: 6.8% avg** (up from 5.4% on same opponents). Per-opponent: BlestPain 15.7%, ChocolateBar 13.3%, Gladiator 12.7%, FloatingTadpole 7.7%, FourWD/NewBGank 7%, zapper 5.3%, DrussGT 4.3%, Cardigan 3.3%, Diamond 2.7%, Pris 1.7%, Shadow 1%.
- Sanity check 6/6 mandatory PASS (TickBudget=200, skipped turns=0.6/battle, gun0 selected 68%). BUT run on stale CSV data.
- B1 Fire power R² = -3.67 (WARN) — this is from OLD data. Need fresh CSVs to measure impact of the NaN fix.
- **Root cause of missing CSVs needs investigation:** The pipeline binary (`pipeline.bat`) ran with `--input` and `--output` flags but produced no output directories. Could be that old recordings (144 from previous run) confused it, or the binary errored silently.

### Cross-agent: Sprint 10
- Naomi found root cause: 23/80 features were NaN — created MlDerivedFeatures.java in core.
- Bobbie fixed 3 CircularGun physics bugs (turn-move ordering, turn rate cap, wall collision). 17 tests.
- Sprint result: HIT. Score 6.6%, R² −3.67→−1.44.

### 2026-05-10 — Pipeline Parallelization
- Parallelized `Main.java` CSV processing: `ExecutorService` with fixed thread pool (default 4 threads).
- Each `processBattle()` creates its own `Whiteboard`, `Transformer`, `CsvWriter` — confirmed no shared mutable state.
- Used `AtomicInteger` for success/failure counters, `synchronized(System.out/err)` for output.
- Added `--threads N` CLI argument (default 4, minimum 1).
- Updated `local-pipeline.ps1` step 10c to pass `--threads 4`.
- Java 8 compatible: `Runnable` anonymous class (no lambdas), `Future<?>` collection pattern.
- Expected speedup: ~4× for 226 files (16 min → ~4 min).

### 2026-05-10 — 6 Process Improvements
- **Improvement 1 (Recording archive):** `local-pipeline.ps1` now moves old `.br` files to `output/local/recordings-archive/` at the start of step 10b. Ensures only current-sprint recordings are processed.
- **Improvement 2 (Enhanced summary.json):** `summary.json` now includes `timestamp`, `total_battles`, `wins`, `overall_score_pct`, `per_opponent` (with name, avg_score, battles, wins, scores array), and `raw_results`. Backward compatible — raw results still present.
- **Improvement 3 (Sprint-only sanity checks):** Added `--battle-ids` arg to `sanity_check.py` (JSON file with list of battle IDs). `find_battle_dirs()` filters to those IDs when provided. `local-pipeline.ps1` writes `sprint_battles.json` after battles. `sanity-check.ps1` passes `-BattleIds` when file exists.
- **Improvement 4 (Incremental CSV):** `Main.java` skips `.br` files whose CSV output directory already exists. Added `--force` flag to override. Reports "Skipped N already-processed, processing M new." Exit code 0 if all skipped (not a failure).
- **Improvement 5 (Diagnostic):** Deferred — `run-battle.mjs` doesn't support `--jvm-args`. Manual steps documented in history.md from Sprint 10.
- **Improvement 6 (EvalOnly mode):** Added `-EvalOnly` switch to `local-pipeline.ps1`. Runs build + battles, skips CSV processing and retrain, runs sanity-check at the end with sprint battle filter.

### 2026-05-10 — Skipped Turns Fix (MlDerivedFeatures Optimization)
- **Root cause:** MlDerivedFeatures adds ~25 per-tick computations; combined with 3×200-tree GBM models, total scan-tick time exceeds Robocode time limit on ~0.4% of ticks → avg ~12.6 skips/battle.
- **Fix 1 — `PrimitiveRollingBuffer`:** New `core/util/PrimitiveRollingBuffer.java` replaces `RingBuffer<Double>` for the 3 movement history buffers. Stores primitive `double[]` (no autoboxing), maintains running sum + sumSq for both full buffer (30) and short window (10). Enables O(1) mean and population std instead of O(n) iteration.
- **Fix 2 — Whiteboard migration:** Changed `latVelHistory30`, `velHistory30`, `headingDeltaHistory30` from `RingBuffer<Double>` to `PrimitiveRollingBuffer(30, 10)`. Updated getters to return new type.
- **Fix 3 — MlDerivedFeatures update:** Removed `rollingMean`/`rollingStd` iteration-based helpers. Now calls `buffer.mean(10)`, `buffer.mean(30)`, `buffer.std(10)` — all O(1).
- **Performance savings:** Eliminated ~240 iterations + autoboxing per scan tick (4 stats × 10-30 elements each, 2 passes for std). Each iteration saved 1 autobox (Double→double) + 1 method call overhead.
- **All 25 MlDerivedFeatures outputs ARE used by models** — verified against FirePowerData (80 features), FireTimingData (81 features), MovementData (76 features). No features could be removed.
- **Feature values unchanged:** Same formulas, same results (population std via E[X²]-E[X]²). Only computation method differs (running sums vs iteration).
- **Build + 121 tests PASS.**

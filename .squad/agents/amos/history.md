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

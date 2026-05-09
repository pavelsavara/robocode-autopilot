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
- Fixed pre-existing compilation errors in WaveSurfMovement.java (missing field declarations from Sprint 8).
- Feature names in CSV header match `FirePowerData.FEATURE_NAMES` exactly for direct Python comparison.
- Wired into robot lifecycle via system property.

# Squad Decisions

## Active Decisions

### 2026-05-09: Retrospective 6 — Post-Fix Code Audit
**By:** Holden (Lead)
**What:** Created retrospective-6 documenting full team code audit before first post-TickBudget-fix evaluation. Identified 9 critical findings including dead pre-emptive dodge code, VCS lateral direction bugs, and only 6 VCS segments. Team consensus: run evaluation first, then prioritize fixes.
**Why:** Team code review revealed multiple systemic issues that need baseline measurement before any changes are made.

### 2026-05-09: Automated Sanity Check Script
**By:** Amos (Systems Engineer)
**What:** Created `scripts/sanity-check.ps1` + `scripts/sanity_check.py` automating all 6 Phase 4a mandatory sanity checks plus 3 bonus ML diagnostics. Exit code 0/1 for CI integration.
**Why:** TickBudget catastrophe persisted for 5 retrospectives partly because checks were manual. Automated script catches regressions immediately.
**Data sources:** TickBudget from `debug.log` DATA_SAVE lines (not internal.csv), skipped turns from SKIPPED lines, gun/ML from internal.csv, waves/features from ticks.csv.

### 2026-05-09: Sprint Close — Retrospective 7
**By:** Holden (Lead)
**Sprint result: BLOCKED**
Sanity check #3 failed (gun selection). Two of three ML models are broken in-game. The robot is running at ~5% of its designed targeting capability despite TickBudget fix confirming full model capacity.
**Evidence:**
- TickBudget fix WORKS: budget 100–200 trees, score 10× improved (0.56% → 5.4%)
- Fire power model: offline R²=0.862, in-game R²=−0.61
- Fire timing model: predicts fire 83% of ticks, actual fire rate 3%
- HeadOnGun selected 54% despite Decision #10 demoting it
- Movement at max speed only 64% of ticks
**Key decisions:**
1. Phase 12 repurposed to "Fix Broken Systems" — online learning deferred
2. Three blocking priorities: feature parity, gun ordering, movement velocity
3. Decision #13: fix broken systems before new features

### 2026-05-09: Sprint 8 Close — MISS
**By:** Holden (Lead)
**Sprint:** 8
**What:** Sprint 8 closed as MISS. Fire power in-game R² degraded to −3.46 (target was ≥0.5). HeadOnGun selection worsened to 67% (target <20%). Movement mixed (some opponents +4.7 pp, some −4.0 pp). Overall score 5.4%→5.1% (−0.3 pp).
**Positive:** 4 fixes merged, 17 new tests, sanity-check script, offline model improved (R² 0.862→0.946).
**Key finding:** Java/Python feature divergence at inference time is the binding constraint. Better offline model = more sensitivity to in-game feature mismatches.
**Next:** Sprint 9 = diagnostic sprint (feature comparison, gun tie-break fix, isolated movement eval).

### 2026-05-09: Fire Power Feature Parity Fix (Sprint 8 delivery)
**By:** Naomi (ML Engineer)
**What:** Fixed training pipeline to compute window features on consecutive ticks (matching Java) instead of fire-event-only rows. Python `rolling(20)` was operating over non-consecutive fire-event rows, making "20-tick window" actually "20-fire-event window" spanning hundreds of ticks. Also fixed Java `WindowFeatures` to use sample std (ddof=1) matching Python pandas.
**Impact:** Offline fire power R² improved 0.862→0.946. Java std fix improves parity for all models.

### 2026-05-09: Gun Selection — Index-Based Tie-Break
**By:** Bobbie (Targeting Engineer)
**Branch:** `fix-gun-tiebreak-sprint9`
**What:** Replaced confidence-based tie-break with two-pass index-based priority: (1) find max hit rate, (2) among guns within epsilon (3%) of max, pick lowest index. Gun list order = explicit priority: CircularGun(0) > VcsGun(1) > PredictiveGun(2) > LinearGun(3) > HeadOnGun(4). `getConfidence()` no longer called during selection.
**Why:** Old confidence system was broken — CircularGun had `getConfidence()=1.0` ceiling, making it mathematically impossible for other guns to win within epsilon. Also fixed epsilon (0.01→0.03, was below hit rate resolution of 1/50=0.02) and ratchet-down bug in rate update.
**Supersedes:** Earlier epsilon-only fix (bobbie-gun-fix). 17 tests rewritten.

### 2026-05-09: Feature Divergence Root Cause — No Code Mismatch Found
**By:** Naomi (ML Engineer)
**What:** Thorough code review of Java `WindowFeatures` vs Python `train_distill.py` window computation found no code-level mismatch: same 10 base features, same order, same 20-tick window, same min_periods=5, Bessel's correction in both.
**Implication:** R²=−3.46 in-game is NOT a computation error. Divergence is at the input value level (timing differences, scan staleness, feature dependency ordering at round start). Runtime comparison needed via FeatureLogger.

### 2026-05-09: Feature Logging Infrastructure
**By:** Amos (Systems Engineer)
**What:** Added `FeatureLogger` class activated by `-Dautopilot.featureLog=true`. Writes `features_fire_power.csv` with per-tick feature vectors, predictions, and actuals. Zero cost when disabled. Uses `getDataDirectory()` (sandbox-safe). Also fixed pre-existing compilation errors in WaveSurfMovement.
**Why:** Enables direct Java vs Python feature vector comparison to diagnose runtime divergence.

### 2026-05-09: Movement Improvements — Hysteresis + Proportional Dodge
**By:** Alex (Movement Engineer)
**Branch:** `movement-improvements-sprint9`
**What:** Three fixes: (1) Ahead hysteresis 0.15 rad dead-zone prevents per-tick forward/backward oscillation; (2) Proportional dodge commitment MIN=2, MAX=8, scaled by `ticksUntilImpact - 2`; (3) Removed random flip during pre-emptive dodge. Applied to both WaveSurfMovement and OrbitalMovement.
**Why:** Oscillation was primary cause of 64% max-speed and 11.3% direction-change rate. Each reversal costs ~12 ticks of sub-max speed.
**Supersedes:** Earlier direction reversal cooldown fix (alex-movement-fix). 9 tests (4 new).

### 2026-05-09: Sprint 9 Code Review Verdicts
**By:** Holden (Lead)
**Branch 1 (`fix-gun-tiebreak-sprint9`):** REJECTED initially — Autopilot.java called missing `initFeatureLogger`/`closeFeatureLogger` methods (cross-branch dependency). VGM changes correct. Fix: remove 2 lines, re-review.
**Branch 2 (`feature-logging-sprint9`):** APPROVED — new files only, compiles clean.
**Branch 3 (`movement-improvements-sprint9`):** APPROVED — compiles, 62+ tests pass, correct logic.
**Merge order:** Branch 2 first, Branch 3 second, Branch 1 after fix.

### 2026-05-09: Sprint 9 Close — HIT (marginal)
**By:** Holden (Lead)
**Sprint:** 9
**Result:** HIT. Overall score 6.1% — project record (+1.0 pp). All 6 mandatory sanity checks pass for first time.
**Key outcomes:** (1) Gun selection FIXED — CircularGun 68% with 3.5% HR, HeadOnGun 4%. (2) Movement net positive — 6/16 opponents improved ≥1 pp, zero regressed. (3) Feature logging infra ready but not yet exercised. (4) Fire power model still broken in-game (R²=−3.67).
**Sprint 10 direction:** Execute the feature comparison (no more infra), merge rumble+local data for retraining, investigate CircularGun 3.5% HR, Decision #13 holds.

### 2026-05-09: All agents work on main — no feature branches
**By:** Pavel Savara (via Copilot)
**What:** All agents/members work directly on the `main` branch in parallel. No separate feature branches. Domain boundaries prevent file conflicts. Battles run once with all changes combined.
**Why:** User directive — simplifies workflow, eliminates branch management overhead and cross-branch dependency issues (e.g. Sprint 9's feature logger wiring split across branches).

### 2026-05-09: Feature Divergence Root Cause — 23 Missing Features
**By:** Naomi (ML Engineer)
**What:** 23 of 80 fire power model features were ALWAYS NaN in Java inference — computed only by pipeline-only offline feature classes with no in-game counterpart. Created `MlDerivedFeatures.java` in core to compute all 23 at runtime.
**Why:** Root cause of fire power model R²=−3.67 in-game. GBM received NaN for ~29% of inputs, forcing default tree splits.

### 2026-05-09: FeatureLogger wired into GbmFirePowerPredictor
**By:** Amos (Systems Engineer)
**What:** FeatureLogger now wired into GbmFirePowerPredictor's process() loop. Enable with `-Dautopilot.featureLog=true` for per-tick CSV with all 80 features, predictions, and actuals.
**Why:** Enables direct Java vs Python feature vector comparison for diagnosing remaining divergence.

### 2026-05-09: Circular targeting physics fix — 3 bugs
**By:** Bobbie (Targeting Engineer)
**What:** Fixed turn-move ordering (Robocode is turn-then-move), added turn rate capping (`10 - 0.75·|vel|` deg/tick), wall collision now zeroes velocity. 17 unit tests added.
**Why:** CircularGun hit rate 3.5% — physics inaccuracies compounded over ~30 simulation ticks.

### 2026-05-09: MlDerivedFeatures Code Review — APPROVED
**By:** Holden (Lead)
**What:** MlDerivedFeatures.java approved. `final` class, no mutable state, no I/O, formulas match pipeline, correct registration order. 25 features computed (doc says 23, actual is 25). Both `rollingStd` ddof values intentional (ddof=0 matches pipeline).
**Why:** Root cause fix for R²=−3.67. Approved without blocking issues.

### 2026-05-09: Sprint 10 Phase 2 Code Reviews — All APPROVED
**By:** Holden (Lead)
**What:** FeatureLogger wiring (Amos) APPROVED — zero-cost when disabled, correct lifecycle, sandbox-safe. CircularGun physics (Bobbie) APPROVED — all 3 fixes physically correct, 17 tests, no architectural violations.
**Why:** Both changes ready for evaluation.

### 2026-05-09: Sprint 10 Close — HIT
**By:** Holden (Lead)
**Sprint:** 10
**Result:** HIT. Overall score 6.6% — third consecutive project record (+0.5 pp). Fire power R² improved −3.67→−1.44 (+2.23, largest single-sprint gain). Root cause: 23/80 features were NaN, fixed by MlDerivedFeatures. CircularGun physics fixed (3 bugs, 17 tests). Retrained models in JAR but not yet evaluated. Sprint 11: evaluate retrained models, diagnose remaining 57-feature divergence.

### 2026-05-10: User directive — parallel CSV processing
**By:** Pavel Savara (via Copilot)
**What:** Pipeline CSV processing (recording replay step) should process 4 files in parallel instead of sequentially. Currently processes 226 recordings one at a time (~16 minutes), parallelizing 4x would cut this to ~4 minutes.
**Why:** User request — captured for team memory. Amos owns pipeline implementation.

### 2026-05-10: Parallelize pipeline CSV processing
**By:** Amos (Systems Engineer)
**What:** Pipeline `Main.java` now processes .br files in parallel using a fixed thread pool (default 4 threads). New `--threads N` CLI flag controls parallelism. `local-pipeline.ps1` passes `--threads 4`.
**Why:** Sequential processing of 226 .br files took ~16 minutes. 4-thread parallelism should reduce to ~4 minutes. Each `processBattle()` is fully self-contained (own Whiteboard, Transformer, CsvWriter) so thread safety is structural.

### 2026-05-10: Sprint 11 Close — HIT
**By:** Holden (Lead)
**Sprint:** 11
**Result:** HIT. Overall score 8.0% — fourth consecutive project record (+1.4 pp). First battle win EVER (58% vs eem.zapper). R² −1.44 → −1.12 (+0.32).
**Key outcomes:** (1) Retrained models evaluated — marginal R² improvement confirms direction. (2) Pipeline parallelized — 4× speedup. (3) Feature comparison tooling ready for Sprint 12. (4) FIRST BATTLE WIN in 11 sprints of development.
**Regression:** Skipped turns check #2 FAIL — new battles avg ~12.6 skipped turns. Caused by MlDerivedFeatures computation overhead. TickBudget min=199 (first sub-200).
**Sprint 12 direction:** (1) Fix skipped turns via MlDerivedFeatures profiling/optimization (Priority — check #2 failing). (2) Run feature comparison diagnostic with FeatureLogger. (3) Continue R² improvement toward −0.5. Decision #13 holds — fix broken systems before new features.

### 2026-05-10: Optimize MlDerivedFeatures to fix skipped turns regression
**By:** Amos (Systems Engineer)
**What:** Replaced `RingBuffer<Double>` with `PrimitiveRollingBuffer` (primitive `double[]` + O(1) running stats) in MlDerivedFeatures rolling history. Eliminates ~240 autoboxed iterations per scan tick for rolling mean/std. All 25 feature outputs verified as used by models — no features removed.
**Why:** Skipped turns avg ~12.6/battle from MlDerivedFeatures overhead. Target: <5 avg/battle. The rolling stats iteration + autoboxing was the biggest contributor that could be optimized without changing feature values.
**Files changed:**
- `core/src/main/java/cz/zamboch/autopilot/core/util/PrimitiveRollingBuffer.java` (new)
- `core/src/main/java/cz/zamboch/autopilot/core/Whiteboard.java` (3 fields changed from RingBuffer<Double> to PrimitiveRollingBuffer)
- `core/src/main/java/cz/zamboch/autopilot/core/features/MlDerivedFeatures.java` (removed rollingMean/rollingStd helpers, use O(1) buffer.mean()/std())

### 2026-05-10: Pipeline process improvements (6 items)
**By:** Amos (Systems Engineer)
**What:** Implemented 5 of 6 requested process improvements to the pipeline:
1. Recording archival at sprint start (`.br` files moved to `recordings-archive/`)
2. Enhanced `summary.json` with per-opponent averages, timestamps, win counts
3. Sprint-only sanity checks via `--battle-ids` filter (JSON file)
4. Incremental CSV processing in `Main.java` (`--force` to override)
5. Diagnostic battle — deferred (run-battle.mjs lacks JVM arg support)
6. `-EvalOnly` mode: build → battle → sanity-check (no CSV, no retrain)

**Why:** Reduces sprint cycle time and improves diagnostic reliability. Incremental CSV avoids reprocessing ~200 files on each run. EvalOnly gives ~5-minute eval turnaround vs 20+ minute full pipeline.

### 2026-05-10: VCS segment mismatch fix — fire-time segmentation
**By:** Bobbie (Targeting Engineer)
**What:** Fixed critical bug where gun VCS histograms stored GF data under wrong segments.
Two root causes: (1) lateral direction computed from raw velocity sign instead of
`OPPONENT_LATERAL_DIRECTION` feature, (2) segment used wave-break-time distance instead
of fire-time distance. Added `fireLateralDir` to `WaveRecord` so both update and query
use identical fire-time conditions for segmentation.
**Why:** VcsGun hit rate stuck at 2.7% despite having correct GF computation — the data
was being stored in wrong segments so the gun was always reading near-empty histograms.
This explains why VcsGun couldn't outperform CircularGun despite being a more sophisticated
targeting system.
**Impact:** Every VCS data point was misrouted. Fix should unlock VCS accuracy.

### 2026-05-10: VCS-guided orbital direction selection
**By:** Alex (Movement Engineer)
**What:** When waves exist but aren't imminent (> 12 ticks), WaveSurfMovement now uses VCS wave danger histograms to choose the safer orbital direction (CW vs CCW) instead of random flips. This fills the gap where ~70% of active-wave ticks used zero VCS information.
**Why:** Opponent hit rate plateaued at ~45%. The robot only used VCS data in the last 12 ticks before wave impact — too late to reach a significantly different GF. Now it starts positioning toward safer GFs early via direction selection.
**Design:** Binary CW/CCW choice (not position-seeking) avoids the oscillation problem that led to Decision #9. Rate-limited to 8-tick intervals with 0.03 danger hysteresis.
**Files changed:** WaveSurfMovement.java (new `updateOrbitDirection` method, IWaveDanger constructor param), Autopilot.java (shared VcsWaveDanger), WaveSurfMovementTest.java (+2 tests).

## Sprint 20

### 2026-05-10: Sprint 20 plan — single major proposal: CI Offload
**By:** Holden (Lead) — requested by Pavel Savara
**Sprint:** 20
**What:** Sprint 20 = single major architectural proposal sourced from `plan.md` v5 Workstream A: CI Offload. Owner = Amos. New `eval-sprint.yml` workflow on push to `main` builds the JAR, runs the fixed 16-opponent set in a 4×8 matrix (4 runners × 8 opponents each), adds a self-battle job (`cz.zamboch.Autopilot` vs itself, 48–52% sanity band), computes `summary.json` in CI, downloads only that ~2 KB artifact. Existing `run-season.yml`, `Dockerfile.battle`, `run-battle.mjs`, `local-pipeline.ps1` stay intact as fallback.
**Why:** Local battle loop is the binding constraint on iteration speed. Offloading evaluation to GitHub Actions unblocks Workstreams B–F (movement, targeting, feature divergence). Pavel's ~30 Mbps WiFi means transferring recordings is impractical — only `summary.json` crosses the wire.
**Success criterion:** Green CI run on `main` with 16 opponents × N rounds completed, self-battle in 48–52% band, `summary.json` accessible from the commit, per-opponent score table visible without cloning recordings locally.
**Failure mode + fallback:** Workflow ships to `main` regardless. If not green by Phase 3, fall back to `local-pipeline.ps1`. If self-battle skews outside 48–52%, treat as a real position/init bug and open an issue.
**Explicitly deferred:** Movement work (Workstream C — Holden picks for Sprint 21), Workstreams B/D/E/F, MlpGfTargeting (Decision #22 holds).

### 2026-05-10: CI offload — Sprint Eval workflow shipped
**By:** Amos (Systems Engineer) — requested by Pavel Savara
**What:** New `.github/workflows/eval-sprint.yml` (~330 lines) runs the fixed 16-opponent sprint eval in GitHub Actions on every push to `main` (and via `workflow_dispatch`). Four jobs: `build` (JDK 21 Temurin, builds robot JAR, uploads as artifact), `battles` (4×4 matrix `max-parallel: 4`, runs inside `ghcr.io/pavelsavara/robocode-autopilot/battle-runner:latest`, pulls opponent JARs from `robots` branch via `git lfs pull`, runs `BATTLES_PER_OPPONENT × opponents` per chunk, uploads `results.json` per chunk), `self-battle` (`cz.zamboch.Autopilot` vs itself, hard gate: avg `bot_a.score_pct` must be in `[48, 52]`), `aggregate` (`if: always()`, joins chunks + self-battle into single `summary.json`, posts table to `$GITHUB_STEP_SUMMARY`).
**Why:** Pavel's home WiFi (~30 Mbps) was the bottleneck blocking parallel local development. CI offload unblocks Workstreams B–F.
**Data-transfer policy (HARD INVARIANT):** Only `summary.json` (~2 KB, 90-day retention as `sprint-summary` artifact) crosses to Pavel's machine. Recordings (`.br`) never uploaded. Inter-job artifacts (`chunk-results-*`, `self-battle-results`, `robot-jar`) are 7-day retention. `$GITHUB_STEP_SUMMARY` shows per-opponent table without download.
**Local fallback:** `scripts/local-pipeline.ps1` gained one new switch — `-IncludeSelfBattle` — running `cz.zamboch.Autopilot` vs itself for `$BattlesPerOpponent` battles, computing avg `bot_a.score_pct`, logging PASS/FAIL against the 48–52% band, writing `self_battle: {avg_score_pct, battles, scores}` into `summary.json`. WARN-not-error on out-of-band. Schema-compatible (additive). Recommended sprint command: `.\scripts\local-pipeline.ps1 -EvalOnly -IncludeSelfBattle`.
**Files:** NEW `.github/workflows/eval-sprint.yml`; MODIFIED `scripts/local-pipeline.ps1` (+1 switch, +30-line self-battle block, +1 line in summary schema).
**Known limitations:** Workflow not yet validated against a real run (cannot trigger from local). No SHA pinning on `:latest` runner image. ~15–20 runner-minutes per push (within free tier).

### 2026-05-10: Sprint 20 review — APPROVE-WITH-NITS, Phase 3 may proceed
**By:** Holden (Lead)
**Sprint:** 20
**Verdict:** APPROVE-WITH-NITS. Phase 3 (battle eval) may proceed. None of the findings are blockers.
**Files reviewed:** `.github/workflows/eval-sprint.yml`, `scripts/local-pipeline.ps1`.
**Findings (carry-over to Sprint 21, NOT blocking):**
- **F1 (semantic, self-battle gate):** Robocode results are rank-ordered, so `parseResults` returns `bot_a = winner`. `bot_a.score_pct` is mathematically ≥ 50 in every parsed self-battle. The 48% lower bound of the `[48, 52]` band is **dead code** — it cannot trigger. Gate is effectively `AVG ≤ 52`. Still has diagnostic value (catches positional asymmetry / decisive winners). Cheap fix: rewrite the comment to honestly describe `≤ 52%` and tighten the upper bound. Proper fix: match parsed bots back to `--bot-a`/`--bot-b` in `run-battle.mjs::parseResults`.
- **N1 (style, matrix idiom):** Matrix `opponents` is a JSON-string literal consumed via `jq --argjson`. Works deterministically but the idiomatic GH Actions pattern is YAML list + `toJson()`. Convert when next touching the matrix.
- **N2 (push-paths gap):** Missing `rumble/scripts/**` (workflow runs `run-battle.mjs` directly) and `gradle.properties` (affects build behaviour). Two-line edit.
**Other observations:** YAML correct, permissions minimal (`contents: read`, `actions: read`, no `write`), 16-opponent list matches `sprint.md` Phase 1, PowerShell change is small and additive (schema-compatible).

## Governance

- All meaningful changes require team consensus
- Document architectural decisions here
- Keep history focused on work, decisions focused on direction

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

## Governance

- All meaningful changes require team consensus
- Document architectural decisions here
- Keep history focused on work, decisions focused on direction

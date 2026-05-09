# Holden — History

## Project Seed

- **Project:** robocode-autopilot — competitive Robocode 1v1 robot
- **User:** Pavel Savara
- **Stack:** Java 8, Python 3.10, Gradle 9.4.1, PowerShell
- **Current state:** Phase 12, 0.56% win rate vs top 50 opponents
- **Key gaps:** Movement (47% opp HR), Targeting (8% our HR)
- **Priority:** Win rate improvement first (not Phase 12 online learning)
- **TickBudget bug fixed 2026-05-09** — all previous evals were at 5% model capacity

## Learnings

### 2026-05-09 — Sprint 7
- Sprint result: BLOCKED. Score improved 10× (5.4%) after TickBudget fix but 0% win rate.
- Fire power model R²=−0.61 in-game vs 0.862 offline — feature mismatch is root cause.
- Gun selection bug: HeadOnGun at 54% despite Decision #10 demoting it to lowest priority.
- Phase 12 repurposed from "Online Learning" to "Fix Broken Systems" — online learning deferred.
- Decision #13: fix broken systems before adding new features.

### 2026-05-09 — Sprint 9, Phase 2b Code Review
- Reviewed 3 branches: fix-gun-tiebreak, feature-logging, movement-improvements.
- Branch 1 (`fix-gun-tiebreak-sprint9`) REJECTED: **does not compile**. Autopilot.java calls `firePowerPredictor.initFeatureLogger()` / `closeFeatureLogger()` which don't exist on `GbmFirePowerPredictor`. The VGM changes and gun reordering are correct — only the feature logger wiring lines in Autopilot.java must be removed.
- Branch 2 (`feature-logging-sprint9`) APPROVED: new files only, compiles clean, good code quality.
- Branch 3 (`movement-improvements-sprint9`) APPROVED: compiles, 62+ tests pass, correct hysteresis logic.
- Lesson: cross-branch dependencies must be caught before code review. The feature logger wiring was split across two branches without coordination.

### 2026-05-09 — Sprint 9 Retrospective
- Sprint result: **HIT (marginal)**. Score improved +1.0 pp to 6.1% — project record.
- All 6 mandatory sanity checks PASS for the first time in project history.
- Gun selection FIXED: CircularGun at 68% with best HR (3.5%). HeadOnGun demoted to 4%. Root cause was hardcoded `getConfidence()=1.0` acting as ceiling. Two-pass index-based priority system replaced it.
- Movement net positive: 6 opponents gained ≥1 pp, zero regressed ≥1 pp. Cleanest movement result ever.
- Fire power model still broken in-game (R²=−3.67). Diagnostic infra built (FeatureLogger + compare_features.py) but not yet exercised against live data. Code review confirmed no algorithmic mismatch — divergence is runtime inputs.
- 0% battle win rate persists. 3.5% hit rate vs ~46% opponent hit rate = 1:13 damage ratio.
- Local retraining reduced offline R² (~0.1 drop) due to small dataset (48 battles vs ~1944 rumble). Merge datasets for future retraining.
- Review gate held: initial REJECT of gun branch caught cross-branch wiring bug. Lockout rule works.
- Sprint 9 delivered: gun fix, movement improvements, feature logging infra, feature divergence diagnosis.
- Sprint 10 priorities set: (1) execute feature comparison with live data, (2) merge rumble+local datasets, (3) investigate CircularGun 3.5% HR, (4) Decision #13 holds — fix broken ML before new features.

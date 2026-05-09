# Sprint 9 Code Review Verdicts — 2026-05-09

**Reviewer:** Holden (Lead)

## Branch 1: `fix-gun-tiebreak-sprint9` — REJECT

**Author:** Bobbie
**Reason:** Does not compile. `Autopilot.java` calls `firePowerPredictor.initFeatureLogger(getDataDirectory())` and `firePowerPredictor.closeFeatureLogger()` but `GbmFirePowerPredictor` has no such methods. These calls belong to the feature-logging work, not the gun tiebreak fix.

**What's correct:** VGM `selectBestIndex` two-pass rewrite is clean and correct. Gun list reordering in `createGunManager()` matches Decision #10. Tests are thorough (17 cases covering tie-break, priority, edge cases).

**Required fix:** Remove the two feature-logger lines from `Autopilot.java` (lines 181 and 378 in the diff). The feature-logger wiring should be a separate commit after Branch 2's `FeatureLogger.java` is merged AND `GbmFirePowerPredictor` gains `initFeatureLogger`/`closeFeatureLogger` methods.

**Who should fix:** Naomi or Amos (lockout rule — not Bobbie).

## Branch 2: `feature-logging-sprint9` — APPROVE

**Authors:** Amos + Naomi
**New files only:** `FeatureLogger.java`, `compare_features.py`
- `FeatureLogger` is `final`, zero-cost when disabled, handles sandbox SecurityException
- `compare_features.py` is well-structured diagnostic tool
- No modifications to existing code — safe to merge

## Branch 3: `movement-improvements-sprint9` — APPROVE

**Author:** Alex
**Changes:** WaveSurfMovement, OrbitalMovement, WaveSurfMovementTest
- Ahead hysteresis (0.15 rad) prevents per-tick forward/backward oscillation
- Proportional dodge commitment scales with wave distance (2–8 ticks)
- No random flip during pre-emptive dodge — maintains commitment
- 4 new tests covering hysteresis, commitment scaling, no-random-flip
- Compiles clean, all 62+ tests pass
- Mutable state (`goingForward`, `commitDuration`) is strategy-internal, not feature-class state — acceptable

## Merge Order

1. Merge Branch 2 (`feature-logging-sprint9`) first — no dependencies
2. Merge Branch 3 (`movement-improvements-sprint9`) — no dependencies
3. Fix Branch 1 (remove 2 lines), re-review, then merge

# Alex — History

## Project Seed

- **Project:** robocode-autopilot — competitive Robocode 1v1 robot
- **User:** Pavel Savara
- **Stack:** Java 8, Python 3.10, Gradle 9.4.1
- **Current state:** Orbit-primary movement, 47% opponent hit rate
- **Key finding:** Constant wave surfing oscillation hurts — orbit + imminent dodge is better
- **TickBudget bug fixed 2026-05-09** — first real evaluation pending

## Learnings

### 2026-05-09 — Sprint 7
- Movement at max speed only 64% of ticks (should be ~90%+).
- Lateral velocity high only 54.5% — too many direction changes (11.3% of ticks).
- Opponent hit rate 46.2% — opponents adapt to our movement faster than we dodge.
- Orbit-primary strategy confirmed correct (Decision #9) but velocity implementation needs tuning.
- Next sprint priority: reduce unnecessary direction changes, increase time at max speed.

### 2026-05-09 — Sprint 9
- Implemented three movement fixes: ahead hysteresis (0.15 rad), proportional dodge commitment (2–8 ticks), removed random flips during pre-emptive dodge.
- Applied to both WaveSurfMovement and OrbitalMovement.
- Results: 6/16 opponents improved ≥1 pp, zero regressed ≥1 pp. Cleanest movement result in project history.
- Hysteresis prevents per-tick forward/backward oscillation (primary cause of 64% max-speed). Each reversal costs ~12 ticks sub-max speed.
- Proportional commitment: close waves get quick reactions (2 ticks), far waves get sustained dodge (8 ticks).
- 9 tests (4 new) covering hysteresis oscillation, commitment scaling, pre-emptive dodge stability.
- Branch approved on first review. Clean compile, no cross-dependencies.

### 2026-05-10 — Sprint 18 (VCS-guided orbital direction)
- **Gap found:** When waves exist but aren't imminent (> 12 ticks), robot used random orbital direction. VCS data was completely unused during orbit phase (~70% of ticks with active waves).
- **Fix:** Added `updateOrbitDirection()` to WaveSurfMovement. Projects two positions (CW and CCW orbit at max speed) to wave arrival time, scores both with VcsWaveDanger, picks the safer direction with hysteresis.
- Rate-limited to every 8 ticks (DIR_EVAL_INTERVAL) to prevent flutter. Direction change requires 0.03 danger differential (DIR_CHANGE_THRESHOLD).
- Pre-allocated 2 CandidatePositions for zero per-tick allocation. Wall-clamped projections.
- Added IWaveDanger as second constructor parameter to WaveSurfMovement. Reused same VcsWaveDanger instance from PathPlanner in Autopilot.java.
- Added 2 new tests: VCS-guided direction choosing safer side, rate-limiting within eval interval. All 11 tests pass.
- Key insight: choosing orbital DIRECTION (binary CW/CCW) avoids the oscillation problem from Decision #9 while still using VCS data early.

## Learnings

### 2026-05-10: Team update — Sprint 20 = CI offload
Sprint 20 = CI offload (Amos lead, Holden review). No code changes affect the robot. Movement deferred — Holden picks proposals next sprint.

## Learnings

### 2026-05-10: Team update - Sprint 20 = CI offload
Sprint 20 = CI offload (Amos lead, Holden review). No code changes affect the robot. Movement deferred - Holden picks proposals next sprint.

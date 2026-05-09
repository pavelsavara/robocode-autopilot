# Decision: Movement Improvements — Ahead Hysteresis, Proportional Commitment, No Pre-emptive Flip

**Date:** 2026-05-09
**Author:** Alex (Movement Engineer)
**Branch:** `movement-improvements-sprint9`

## Summary

Three targeted improvements to address the 64% max-speed and 11.3% direction-change issues identified in Sprint 7/8:

1. **Ahead hysteresis (0.15 rad)** — Dead-zone around the PI/2 forward/backward boundary. Prevents per-tick oscillation that was the primary cause of low max-speed %. Each unnecessary direction reversal costs ~12 ticks of sub-max-speed travel. Applied to both WaveSurfMovement and OrbitalMovement.

2. **Proportional dodge commitment** — MIN_COMMIT reduced 4→2, MAX added at 8. Duration = `clamp(ticksUntilImpact - 2, 2, 8)`. Close waves get quick reactions; far waves get sustained commitment toward the planned dodge position.

3. **No random flip during pre-emptive dodge** — Removed `maybeFlipDirection()` from `preemptiveLateralMove()`. Random direction changes during a predicted-fire dodge break the dodge trajectory and waste speed.

## Risk Assessment

- **Hysteresis**: Low risk. The 0.15 rad dead-zone is small (~8.6°). Worst case: the robot takes a slightly longer path when it should reverse. But the speed gain from not oscillating far outweighs this.
- **Proportional commitment**: Medium risk. Close waves now get only 2-tick commitment (was 4). If the planner's 1-tick recommendation is bad, 2 ticks of bad movement is better than 4.
- **No pre-emptive flip**: Low risk. Pre-emptive dodge is already a sustained directional movement. Removing random flips makes it more predictable to opponents, but maintains speed — net positive.

## Evidence

- 9 unit tests pass (3 new: hysteresis oscillation, proportional commitment scaling, pre-emptive dodge stability).
- No code changes outside movement files.
- Pre-existing `VirtualGunManagerTest` failure is unrelated (Bobbie's gun ordering).

## Recommended Next Step

Run a 16-opponent evaluation with this branch alone (no gun/ML changes) to isolate movement impact. Target: max-speed % > 75%, direction changes < 8%.

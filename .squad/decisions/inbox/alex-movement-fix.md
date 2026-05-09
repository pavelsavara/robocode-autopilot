# Decision: Movement Direction Reversal Cooldown

**By:** Alex (Movement Engineer)
**Date:** 2026-05-09
**Branch:** squad/fix-movement-velocity

## What

Wall-proximity direction reversals now require a cooldown (25 ticks in both WaveSurfMovement and OrbitalMovement). Previously, direction flipped unconditionally every tick when near a wall.

Random reversal minimum interval increased from 15 to 25 ticks.

Pre-emptive dodge wired (was dead code). Dodge commitment of 4 ticks implemented.

## Why

The wall-proximity oscillation bug was the primary cause of only 64% max-speed ticks. Near any wall, the robot would flip direction every tick, decelerating through 0 continuously. With the 800×600 battlefield, a significant fraction of time is spent within 60px of a wall.

## Impact

- Expected direction-change rate: 11.3% → ~3-4%
- Expected max-speed fraction: 64% → ~85%+
- Fire timing model (AUC=0.855) now actually contributes to dodge timing
- No new allocation, no new fields (used existing unused fields)

## Team Implications

- Bobbie: No impact on targeting code
- Naomi: No ML changes needed
- Holden: Retrospective notebooks R04, R09 will show different movement patterns after next eval

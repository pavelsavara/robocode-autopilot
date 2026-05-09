# Decision: Gun Selection Epsilon & Ratchet-Down Fix

**By:** Bobbie (Targeting Engineer)
**Date:** 2026-05-09
**Branch:** squad/fix-gun-ordering

## What Changed

1. `HIT_RATE_EPSILON` increased from 0.01 to 0.03
2. Confidence tie-break uses `Math.max(bestRate, rate)` instead of `bestRate = rate`
3. Extracted `selectBestIndex()` as testable static method + 10 unit tests

## Why

The old epsilon (0.01) was smaller than the hit rate resolution of a 50-window (1/50 = 0.02). A single random virtual hit made any gun "clearly better," bypassing confidence tie-breaking entirely. HeadOnGun won 54% of selections by random noise.

The `bestRate = rate` in the tie-break could ratchet the threshold downward, letting later guns sneak through the "clearly better" path.

## Impact

- CircularGun (conf=1.0) now correctly wins all ties as intended by Decision #10
- A gun must outperform the best by >3 percentage points to win on rate alone
- No change to how guns win when they have genuinely better hit rates (>epsilon difference)
- 10 unit tests ensure the selection logic stays correct

## Team Note

This is a pure bugfix — no architectural change. The selection algorithm structure is unchanged; only the epsilon constant and the rate-update in the tie-break branch were modified.

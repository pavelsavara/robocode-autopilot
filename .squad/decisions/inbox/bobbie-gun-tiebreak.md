# Decision: Index-based gun tie-break replaces confidence-based

**Author:** Bobbie (Targeting Engineer)
**Date:** 2026-05-09
**Branch:** fix-gun-tiebreak-sprint9
**Status:** Proposed — awaiting team review

## Change

Replaced confidence-based tie-break in `VirtualGunManager.selectBestIndex()`
with a two-pass index-based priority system:

1. Find the maximum hit rate across all guns
2. Among guns within epsilon (3%) of the max, pick the **lowest index**

The gun list order in `Autopilot.createGunManager()` is now the explicit
priority: CircularGun(0) > VcsGun(1) > PredictiveGun(2) > LinearGun(3) > HeadOnGun(4).

## Why

The old confidence-based tie-break was broken by design. CircularGun had
`getConfidence() = 1.0` (a ceiling value), making it mathematically
impossible for any other gun to win within the epsilon band. This caused
gun0 to be selected 67% of ticks regardless of actual performance.

The new logic is deterministic, transparent, and directly encodes
Decision #10 (CircularGun primary, HeadOnGun last).

## Impact

- `IGunStrategy.getConfidence()` still exists but is no longer called
  during selection. Can be repurposed for logging or future adaptive epsilon.
- VcsGun promoted from index 2 to index 1 (higher priority than Linear/Predictive).
- Persisted VGM data from old battles will have misaligned histories
  (acceptable — 50-window data is re-learned quickly).

## Files Changed

- `core/.../gun/VirtualGunManager.java` — simplified `selectBestIndex()`
- `robot/.../Autopilot.java` — reordered gun list
- `core/.../gun/VirtualGunManagerTest.java` — 17 tests rewritten

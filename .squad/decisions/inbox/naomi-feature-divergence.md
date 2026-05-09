# Decision: Feature Divergence Root Cause Analysis

**By:** Naomi (ML Engineer)
**Date:** 2026-05-09
**Sprint:** 9, Phase 2

## Finding

After thorough code review of the Java/Python feature computation pipeline, **no code-level mismatch exists** between WindowFeatures.java and train_distill.py's window feature computation:

- Same 10 base features in identical order
- Same 20-tick window, same min_periods=5
- Bessel's correction applied in both (ddof=1)
- Filter order correct: windows computed on all consecutive ticks before fire-event filtering
- Feature ordering preserved through the export chain

## What This Means

The R²=−3.46 in-game is NOT caused by a computation error in the window statistics code. The Sprint 8 fix (filter order + Bessel's correction) was correct on the training side.

## Remaining Hypothesis

The divergence is at the **input value level**, not the computation level. The live robot's Whiteboard feature values may differ from what the offline pipeline records due to:
1. Timing differences in scan/event processing
2. Missing scans causing stale feature values
3. Feature dependency ordering edge cases at round start

## Action Required

Run a battle with DATA_SAVE logging enabled, then execute:
```
python scripts/compare_features.py <battle_dir>
```
This will identify which specific features diverge and by how much, pointing directly at the input-level root cause.

## Artifacts
- `scripts/compare_features.py` — per-feature correlation diagnostic tool

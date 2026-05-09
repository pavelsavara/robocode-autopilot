# Decision: Feature Logging Infrastructure

**By:** Amos (Systems Engineer)
**Date:** 2026-05-09

## What
Added diagnostic feature logging to `GbmFirePowerPredictor` via `FeatureLogger` class.
Activated by system property `-Dautopilot.featureLog=true`. When enabled, writes
`features_fire_power.csv` to Robocode data directory with per-tick feature vectors,
predicted values, and actual values for direct comparison with Python.

## Why
Fire power model has offline R²=0.946 but in-game R²=−3.46 (Sprint 7/8 finding).
Naomi needs side-by-side Java vs Python feature vectors to diagnose the divergence.
This logging provides the exact input buffer the Java model sees each tick.

## Key details
- Zero cost when disabled (null pointer check per tick, no allocations)
- Feature names in CSV header match `FirePowerData.FEATURE_NAMES` exactly
- Uses `getDataDirectory()` API (Robocode sandbox-safe)
- Branch: `feature-logging-sprint9`

## Also fixed
Pre-existing compilation errors in `WaveSurfMovement.java` — missing field
declarations from Sprint 8 (`goingForward`, `commitDuration`, `AHEAD_HYSTERESIS`,
`MAX_COMMIT_TICKS`).

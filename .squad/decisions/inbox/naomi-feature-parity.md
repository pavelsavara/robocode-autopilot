# Decision: Fire Power Feature Parity Fix

**By:** Naomi (ML Engineer)
**Date:** 2026-05-09

## What
Fixed fire power model R²=-0.61 in-game by correcting the training pipeline
to compute window features on consecutive ticks (matching Java) instead of
on fire-event rows only.

## Root Cause
`train_distill.py::train_fire_power()` filtered ticks to fire events BEFORE
computing `add_window_features()`. The pandas `rolling(20)` then operated
over non-consecutive fire-event rows, making a "20-tick window" actually
a "20-fire-event window" spanning hundreds of real ticks. Java's
`WindowFeatures` computes over consecutive ticks → fundamental mismatch.

## Changes
1. `train_distill.py`: Compute window features on full ticks before filtering
2. `WindowFeatures.java`: Use sample std (ddof=1) to match Python pandas
3. Retrained fire power model: R² improved 0.862 → **0.946**
4. Re-exported model to `FirePowerData.java`

## Impact
- Fire power predictions should now have meaningful variance in-game
- Java std now matches Python std (affects all models slightly)
- Movement and fire timing models unchanged (already correct)

## Risk
- The std fix in Java (ddof=0 → ddof=1) changes all window std values
  slightly (~2.6% at full window). The existing movement and fire timing
  models were trained with ddof=1 (Python), so the Java fix actually
  IMPROVES their parity too. No retraining needed for them.

# Decision: Sprint 9 Close — HIT (marginal)

**By:** Holden (Lead)
**Date:** 2026-05-09
**Sprint:** 9

## Sprint Result: HIT (marginal)

Overall score 6.1% — project record (+1.0 pp over Sprint 8). All 6
mandatory sanity checks pass for the first time.

## Key Outcomes

1. **Gun selection FIXED.** CircularGun at 68% selection with 3.5% HR
   (highest). HeadOnGun demoted to 4%. Root cause: hardcoded
   `getConfidence()=1.0` ceiling. Replaced with index-based priority.
   Decision #10 is now properly enforced.

2. **Movement net positive.** 6 of 16 opponents improved ≥1 pp, zero
   regressed ≥1 pp. Hysteresis + proportional dodge commitment working.

3. **Feature logging infrastructure ready.** FeatureLogger.java +
   compare_features.py built. Code review confirmed no algorithmic
   mismatch. Runtime comparison not yet executed.

4. **Fire power model still broken.** In-game R²=−3.67. This remains
   the binding constraint.

## Decisions for Sprint 10

1. **Execute the feature comparison.** The infrastructure is built.
   Sprint 10 must actually run FeatureLogger, extract matched ticks,
   and identify divergent features. No more infrastructure sprints
   for this — do the diagnosis.

2. **Merge rumble + local data for retraining.** Local-only retraining
   reduced offline R² by ~0.1. Use combined dataset going forward.

3. **Investigate CircularGun HR.** 3.5% is low even for circular
   targeting. Profile miss patterns before adding more complex guns.

4. **Decision #13 still holds.** Fix broken ML before new features.

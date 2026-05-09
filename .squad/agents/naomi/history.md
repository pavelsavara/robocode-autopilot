# Naomi — History

## Project Seed

- **Project:** robocode-autopilot — competitive Robocode 1v1 robot
- **User:** Pavel Savara
- **Stack:** Java 8, Python 3.10, XGBoost, scikit-learn
- **Current state:** 3 distilled GBM models (fire power, movement, fire timing)
- **Key insight:** 20-tick windows are everything (R² jumps from 0.07 → 0.87)
- **TickBudget bug fixed 2026-05-09** — previous evals used only 5% model capacity

## Learnings

### 2026-05-09 — Sprint 7
- Fire power model: R²=−0.61 in-game vs 0.862 offline — Java/Python sliding window divergence.
- Fire timing model: 83% false positive rate (predicts fire 83% of ticks, actual rate 3%).
- Movement prediction model: healthy, no in-game degradation detected.
- Root cause for fire power: feature values in Java don't match Python pipeline features.
- Next sprint priority: diagnose and fix Java/Python feature parity for sliding windows.

### 2026-05-09 — Sprint 9
- Code review of Java WindowFeatures vs Python train_distill.py: **no code-level mismatch found**. Same 10 features, same order, same 20-tick window, same Bessel's correction.
- R²=−3.67 in-game is NOT a computation bug — divergence is at the input value level (scan timing, stale features, round-start edge cases).
- Created `compare_features.py` diagnostic tool for side-by-side Java/Python feature comparison.
- Sprint 8 fix (filter order + ddof=1) was correct on training side but made model MORE sensitive to runtime input mismatches.
- Next sprint: actually execute FeatureLogger against live data and run compare_features.py to find divergent features.

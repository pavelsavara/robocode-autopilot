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

### 2026-05-09 — Sprint 10: Feature Divergence SOLVED
- **ROOT CAUSE FOUND:** 23 of 80 model features were ALWAYS NaN in Java inference.
- These features only existed in pipeline-only offline feature classes — no in-game versions.
- The robot module had no classes computing: energy_ratio, our_lateral_velocity, our_dist_to_wall_min,
  opponent_center_distance, opponent_corner_proximity, opponent_angular_velocity, opponent_max_turn_rate,
  distance_norm, opponent_lateral_direction, opponent_velocity_delta, opponent_is_decelerating,
  opponent_time_since_direction_change, our_bullet_speed, our_bullet_travel_time, mea_for_our_bullet,
  ticks_since_we_fired, our_wave_distance, our_wave_remaining, opponent_wall_ahead_distance,
  opponent_avg_lateral_velocity_10/30, opponent_heading_delta_variability_10,
  opponent_velocity_variability_10, opponent_time_since_velocity_change,
  opponent_distance_since_direction_change
- **FIX:** Created `MlDerivedFeatures.java` in core module — computes all 23 features
  from existing Whiteboard state. Registered in Autopilot's Transformer.
- All Whiteboard inter-tick state was already available; only Feature enum setters were missing.
- No retraining needed — model was trained with correct values, only inference was broken.
- **Key lesson:** When distilling ML models to Java, verify ALL model input features have
  in-game feature classes, not just the ones that happen to share names with core features.
  The pipeline can have feature classes that don't exist in core.

### Cross-agent: Sprint 10
- Bobbie fixed 3 CircularGun physics bugs (turn-move ordering, turn rate cap, wall collision). 17 tests.
- Amos wired FeatureLogger into GbmFirePowerPredictor and ran eval — score 6.6%.
- Sprint result: HIT. R² −3.67→−1.44 (+2.23). Third consecutive project record.

### 2026-05-10 — Sprint 11
- Improved feature comparison script and diagnostic battle runner for Sprint 12 use.
- **Cross-agent:** Amos parallelized pipeline (4× speedup). Holden closed Sprint 11 as HIT — score 8.0%, first battle win (58% vs eem.zapper), R² −1.44→−1.12.
- Skipped turns regression detected (~12.6 avg) from MlDerivedFeatures overhead.
- Sprint 12: run feature comparison diagnostic, continue R² improvement toward −0.5.

### 2026-05-10 — Feature Comparison Script v2
- Rewrote `scripts/compare_features.py` for the new FeatureLogger CSV format (features_fire_power.csv).
- New script aligns by (round, tick), computes Pearson correlation, MAE, max absolute error,
  and % of values diverging >10% for each of the 80 model features.
- Categorizes features into ML-Derived (23 Sprint 10 additions), Window (20), and Core groups.
- Maps each feature to a root-cause category (window, scan, cumulative, angle, wave, ml_derived)
  and prints suggested diagnosis for the top 10 most divergent features.
- Created `scripts/run_diagnostic_battle.py` as a helper for the battle→compare loop.
- Usage: `python scripts/compare_features.py <feature_logger_csv> <ticks_csv_dir>`

## Learnings

### 2026-05-10: Team update — Sprint 20 = CI offload
Sprint 20 = CI offload (Amos lead, Holden review). No code changes affect the robot. Movement deferred — Holden picks proposals next sprint.

## Learnings

### 2026-05-10: Team update - Sprint 20 = CI offload
Sprint 20 = CI offload (Amos lead, Holden review). No code changes affect the robot. Movement deferred - Holden picks proposals next sprint.

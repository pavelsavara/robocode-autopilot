# Sprint 24 Retrospective — Feature Ordering Bug, ML Safeguards, 50-Opponent Eval

*Date: 2026-05-11 · Sprint goal: Run feature divergence diagnostic, fix R² gap*

## Diagnostic Health

All 7 sanity checks **PASS** (0 failures) in both Stage 1 and Stage 3.
Self-battle 51–52% — healthy band. Zero errors across 150 battles × 2 stages.

## Metrics Table

| Metric | Sprint 23 (16 opp) | Sprint 24 Stage 1 (50 opp) | Sprint 24 Stage 3 (50 opp) |
|---|---|---|---|
| Overall score % | 9.5% | **3.1%** | **3.2%** |
| Battle win rate | 0/48 | 0/150 | 0/150 |
| Self-battle | 55.7% | 51.3% | 52.3% |
| Opponents evaluated | 16 | **50** | **50** |
| Errors | 0 | 0 | 0 |
| Sanity checks | 7/7 | 7/7 | 7/7 |

**Score context:** The drop from 9.5% to 3.1% is NOT a regression — the opponent
set expanded from 16 (mixed tiers including `sample.TrackFire`, `sample.Walls`)
to the full RoboRumble top 50 (all elite bots). Against top-50, 3% is expected
for our current skill level.

**Stage 1 vs Stage 3 delta: +0.1 pp** — retraining had no effect because the
models were already correctly exported from local in this sprint.

## Per-Opponent Breakdown (Stage 3, top 50)

| Opponent | Score % | Wins |
|---|---:|---:|
| oog.mega.saguaro.Saguaro | 7.7 | 0/3 |
| gh.GresSuffurd | 6.7 | 0/3 |
| dft.Cardigan | 6.0 | 0/3 |
| tjk.deBroglie | 6.0 | 0/3 |
| jk.mega.DrussGT | 5.7 | 0/3 |
| rsalesc.roborio.Roborio | 5.0 | 0/3 |
| florent.test.Toad | 4.7 | 0/3 |
| mn.Combat | 4.7 | 0/3 |
| florent.XSeries.X2 | 4.3 | 0/3 |
| jk.mini.CunobelinDC | 4.3 | 0/3 |
| ... (40 more) | 1.0–4.0 | 0/3 |
| kc.mega.BeepBoop | 1.0 | 0/3 |

## What Worked

1. **Feature ordering bug FOUND AND FIXED.** 53/80 features were in the wrong
   index position between the Python-trained model and the Java deployment.
   Every tree split was reading the wrong feature. The model has been producing
   random noise since it was first deployed (Sprint 10). This is the single
   biggest bug in the project's history.

2. **Root cause chain fully traced:**
   - `compare_features.py` showed features are VALUE-aligned (71/80 OK)
   - R² gap analysis revealed in-game R² = -0.18 (not +0.48 as reported)
   - Per-opponent analysis showed 13/16 opponents had negative R²
   - Model predicts ~1.8 for everything (base score hedging)
   - `FEATURE_NAMES` in Java vs `feature_cols.json` from training: 53/80 ORDER mismatch
   - Root cause: `numeric_feature_cols()` returns different column order between
     training runs depending on DataFrame column order; export baked in one order,
     but retrain produced a different order

3. **GroupKFold fixed for all 3 models.** Changed from `battle_id` to
   `opponent_bot_id_hash`. Honest cross-opponent baselines:
   - Fire power R² = 0.94 (was 0.84 inflated)
   - Movement R² = 0.74 (was 0.88 inflated)
   - Fire timing AUC = 0.76 (was 0.98 inflated)

4. **Comprehensive safeguards added at every level:**
   - Python export: feature order hash verification
   - Java runtime: `validateFeatureDimension()` in all 3 predictors
   - Java unit tests: fixture-based prediction check (100 samples vs Python)
   - CI Stage 3: feature order + prediction variance checks
   - `sanity_check.py`: B1 threshold 0.3→0.5, new B4 (movement R²), B5 (timing AUC)
   - `compare_features.py`: order check + cross-prediction with Python model

5. **CI pipeline expanded and hardened:**
   - 16 → 50 opponents (full top-50 from RoboRumble)
   - 4 → 17 chunks, max-parallel 20
   - Stage 2→3 auto-trigger fixed (curl API dispatch)
   - Sprint number → git SHA for models branch naming
   - Orphan branch for model push (avoids workflow permission error)
   - `--slurpfile` for jq (fixes argument-too-long with 150 battles)

6. **Subsample cap raised** from 500K to 1.5M for movement and fire timing.
   Confirmed models are capacity-limited, not data-limited (zero R² gain).

## What Didn't Work

1. **Score didn't improve** — 3.1% against top 50 is our true baseline.
   The previous 9.5% was inflated by weak opponents. The feature ordering fix
   enables the model to make real predictions for the first time, but against
   elite bots the behavioral features alone aren't enough.

2. **CI retrain metrics don't match local honest numbers.** CI fire timing
   AUC = 0.979 vs local 0.762. The CI data (50 opponents × 3 battles from
   Stage 1) has different characteristics than the rumble dataset. The
   `opponent_bot_id_hash` merge may not be working correctly in the CI
   CSV structure. Needs investigation.

## Root Cause Analysis

**Binding constraint: opponent hit rate ~40%.** With correct models, our fire
power prediction now works, but the competitive gap is dominated by:
1. Movement predictability (opponents hit us 10× more than we hit them)
2. Targeting accuracy (our hit rate 3.6% is extremely low)
3. No per-opponent adaptation (behavioral model alone can't distinguish opponents)

## Honest ML Baselines (Sprint 24)

| Model | Local (cross-opponent) | CI retrain | Note |
|---|---|---|---|
| Fire power R² | **0.940** | 0.883 | Local uses bigger rumble dataset |
| Movement R² | **0.737** | 0.912 | CI inflated — needs investigation |
| Fire timing AUC | **0.762** | 0.979 | CI inflated — needs investigation |

## Key Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| 30 | Feature ordering fix | 53/80 features scrambled — model was random noise |
| 31 | GroupKFold by opponent_bot_id_hash | Honest cross-opponent CV, no within-opponent inflation |
| 32 | Fixture-based prediction tests | Definitive guard against ordering bugs |
| 33 | Full top-50 opponent eval | Previous 16-opponent set was misleading |
| 34 | Git SHA for models branch | Deterministic, no sprint-number fragility |
| 35 | Subsample cap 500K→1.5M | Confirmed capacity-limited, not data-limited |

## Proposals for Sprint 25

1. **Investigate CI retrain metric inflation** — the CI fire timing AUC (0.979)
   is way above local honest baseline (0.762). Verify `opponent_bot_id_hash`
   merge works correctly in the CI CSV pipeline.
   *Metric:* CI and local retrain metrics should agree within ±0.05.
   *Owner:* Naomi.

2. **Per-opponent EMA fire power** — implement the dual-model blend: behavioral
   model + per-opponent exponential moving average of observed fire power.
   `prediction = λ·EMA + (1-λ)·model`, `λ = n/(n+10)`. Pure Java change.
   *Metric:* in-game fire power R² improvement.
   *Owner:* Bobbie.

3. **Movement: wall avoidance** — the robot gets cornered against wall-aware
   opponents. Improve wall distance penalty in movement danger scoring.
   *Metric:* opponent hit rate reduction.
   *Owner:* Alex.

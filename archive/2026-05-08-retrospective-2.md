# Retrospective 2 — Retrained Models + Embedded Data (2026-05-08)

*Second local pipeline run after retraining ML models on local battle data
and embedding autopilot.dat VCS histogram priors.*

## Changes Since Retrospective 1

1. **ML models retrained** on local battle data (25 battles × 5 opponents):
   - Fire Power: R² 0.58, MAE 0.11 (previously MAE 3.45 on rumble-only data)
   - Movement N=5: R² 0.83, MAE 1.73 (previously R² 0.46)
   - Fire Timing: AUC 0.82 (improved calibration)
2. **autopilot.dat embedded** (45 KB) as `DefaultDataFile.java` — VCS histogram
   priors for all 5 known opponents loaded at first scan.
3. **Java model files regenerated** from retrained XGBoost models.

## Test Setup

- **Robot version:** Autopilot 0.1.0 (with retrained models + embedded data)
- **Opponents (5):** ScalarR, DengerousRoBatra, Shadow, Glacier, Midboss
- **Battles:** 50 total (25 from run 1 + 25 from run 2), 35 rounds each
- **Total rounds analyzed:** 1,680

## Overall Results (Combined Runs)

| Metric | Run 1 (old models) | Run 2 (retrained) | Combined |
|---|---|---|---|
| Win rate | 0.0% | 0.0% | **0.0%** |
| Our hit rate | 3.4% | ~3.2% | **3.3%** |
| Opponent hit rate | 43.3% | ~41.5% | **42.4%** |
| Dmg dealt/round | 3.3 | ~3.3 | **3.3** |
| Dmg received/round | 84.8 | ~82.8 | **83.8** |
| Avg ticks survived | 498 | ~514 | **506** |

### Per-Opponent Breakdown (Combined 1,680 rounds)

| Opponent | Rounds | Win% | Our HR | Opp HR | Dmg Dealt | Dmg Recv | Ticks | Zero Dmg% |
|---|---|---|---|---|---|---|---|---|
| DengerousRoBatra | 315 | 0% | 6.2% | 39.0% | 6.8 | 84.7 | 535 | 38% |
| Glacier | 350 | 0% | 3.1% | 39.8% | 3.3 | 82.4 | 496 | 63% |
| Midboss | 350 | 0% | 2.3% | 44.0% | 2.5 | 79.5 | 545 | 69% |
| ScalarR | 350 | 0% | 2.8% | 46.7% | 2.5 | 87.6 | 439 | 69% |
| Shadow | 315 | 0% | 2.2% | 42.1% | 1.9 | 85.3 | 518 | 77% |

## Impact of Retraining

The retrained models did **not produce a measurable improvement** in battle
outcomes. This confirms the core problem is not ML model calibration:

- **Fire Power MAE still 3.7** in-battle (R10 notebook). The training MAE was
  0.11, but in-battle the model sees different feature distributions than
  training (the training sees opponent fire events; in-battle it sees all
  ticks including non-fire ticks, and confidence=0.9 everywhere suggests the
  model isn't distinguishing contexts).
- **Movement R² 0.42 in-battle** vs 0.83 at training time — large train/test
  gap suggests overfitting or distribution shift between the model's training
  window and real-time inference.
- **Fire Timing mean P(fire) = 0.627** — the model predicts the opponent fires
  62.7% of the time, but actual fire rate is ~15%. Massive over-prediction.

## Key Findings from Notebooks

### R01 — Win/Loss: 0% across 1,680 rounds
Not a single round won. This is purely due to opponent quality — all 5 are
elite-tier bots. The robot is mechanically sound but tactically outclassed.

### R02 — Gun Accuracy: 3.3% overall
- Best: DengerousRoBatra (6.2%) — this bot has simpler movement
- Worst: Shadow (2.2%) — the most sophisticated wave surfer

### R08 — Gun Selection: HeadOn gun selected 72% of ticks
The VGM (Virtual Gun Manager) selects HeadOn 72% of the time because all
guns have nearly identical hit rates (~2%). When all guns perform equally
badly, the VGM defaults to gun index 0 (HeadOn).

**Gun Hit Rates:**
| Gun | Hit Rate |
|---|---|
| Circular | 2.68% |
| Linear | 2.65% |
| RandomGF | 2.04% |
| HeadOn | 1.97% |
| VCS | 1.97% |
| Predictive | 1.97% |

None of the guns achieve even 3% accuracy.

### R09 — Movement: Wave surfing active but ineffective
- Velocity: 5.6 px/tick average, only 0.7% at zero — good movement activity
- WaveSurf strategy selected 100% of the time (no alternatives compete)
- Movement commands: |ahead|=39.3 px, |turn|=0.508 rad — short, jerky moves
- Opponent waves in flight: 1.7 average — waves are tracked
- **Wall distance = NaN** — `our_dist_to_wall_min` not being populated in
  internal.csv, suggesting a feature computation gap

### R10 — ML Predictions: Poorly calibrated in-game
- Fire Power MAE=3.70 — model predicts ~1.0-1.1 but actual powers are 1.9-3.0
- Fire Timing P(fire)=0.627 — over-predicts firing 4× (actual ~15%)
- Movement MAE=3.70, R²=0.42 — moderate but insufficient for precise aiming

## Root Cause Analysis

The fundamental problem is **not ML calibration** — it's that the robot's
core algorithms (VCS gun, wave surfing) are too primitive against elite bots:

1. **VCS gun has only 61 bins × 6 segments** — top bots use 47+ segments
   with adaptive binning. Our histogram is too coarse.

2. **Wave surf danger model uses VCS histogram** from the opponent perspective,
   but the histogram is nearly empty after 35 rounds against a single opponent.
   The danger scorer can't distinguish safe from dangerous positions.

3. **No gun convergence** — all 6 guns plateau at ~2% against wave surfers
   who dodge based on GF distribution. Our guns fire predictably.

4. **Energy drain** — we fire at 1.33 power (aggression=0.33), dealing ~1.3
   damage per hit but losing 83.8 damage per round. We drain energy faster
   than we can deal damage.

## Improvement Proposals (Updated)

### Critical — Must fix to see any improvement

1. **Test against weaker bots first** — include `sample.Walls`,
   `sample.Corners`, `sample.RamFire`, and mid-tier bots (rank 100-150)
   in the test set. We need a baseline where we can actually win rounds
   to validate the robot works.

2. **Fix fire power prediction calibration** — the model predicts ~1.0 when
   actual is ~2.0. Either the feature extraction in Java doesn't match
   Python training, or the model input normalization is wrong.

3. **Increase VCS histogram resolution** — more segments (distance × lateral
   velocity × acceleration) and implement kernel density smoothing
   instead of raw bin counts.

### High Priority

4. **Minimum fire power when losing** — when `energy_ratio < 0.3`, fire at
   power 0.1 to conserve energy while still gathering targeting data.

5. **Gun diversity incentive** — the VGM should occasionally (10% of shots)
   fire with a random gun to explore, not just exploit the best (which is
   barely better than the worst).

6. **Wave surf wall avoidance** — `our_dist_to_wall_min` is NaN in internal
   data — investigate whether the wall smoothing in PathPlanner is working.

### Medium Priority

7. **Retrain on rumble + local combined** — pass both `output/csv` and
   `output/local/csv` as roots to `train_distill.py` (requires fixing the
   `--roots` parameter to accept multiple paths).

8. **Opponent profile warm-start** — load per-opponent VCS priors more
   aggressively from the embedded data file; current loading may not be
   activating early enough.

## Data Artifacts

- Recordings: `output/local/recordings/` (50 .br files)
- CSVs: `output/local/csv/` (384 files from 50 battles)
- Results: `output/local/results/summary.json`
- Notebooks: `intuition/retrospective/R01–R10`
- Models: `intuition/models/distill/` (retrained)
- Java exports: `robot/src/.../distilled/*Data.java` (retrained)
- Embedded data: `robot/src/.../distilled/DefaultDataFile.java` (45 KB)

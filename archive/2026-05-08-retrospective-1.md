# Retrospective 1 — Local Pipeline Run (2026-05-08)

*First end-to-end local pipeline test after static model loading fix.*

## Test Setup

- **Robot version:** Autopilot 0.1.0
- **Opponents (5):** ScalarR, DengerousRoBatra, Shadow, Glacier, Midboss
- **Battles:** 25 (5 per opponent × 5 opponents), 35 rounds each
- **Total rounds analyzed:** 875
- **Note:** All 5 opponents are elite-tier (top-50 RoboRumble). This is an
  extremely hard test set — no weak bots included.

## Overall Results

| Metric | Value |
|---|---|
| Win rate | **0.0%** (0/875 rounds) |
| Our hit rate | **3.4%** |
| Opponent hit rate | **43.3%** |
| Damage dealt/round | 3.3 |
| Damage received/round | 84.8 |
| Avg ticks survived | 498 |
| Rounds with 0 damage dealt | 63% |

### Per-Opponent Breakdown

| Opponent | Win% | Our HR | Opp HR | Dmg Dealt | Dmg Recv |
|---|---|---|---|---|---|
| DengerousRoBatra 1.3 | 0% | 6.3% | 39.2% | 7.0 | 85.3 |
| Glacier 0.3.2 | 0% | 3.0% | 40.9% | 3.1 | 83.3 |
| Midboss 1q.fast | 0% | 2.4% | 46.1% | 2.4 | 81.1 |
| ScalarR 0.005h.053-noshield | 0% | 3.2% | 46.8% | 2.7 | 88.0 |
| Shadow 3.83c | 0% | 1.9% | 43.3% | 1.6 | 86.5 |

## What Went Right

1. **No skipped turns.** The static model loading fix (moving GBM Base64
   decoding to `static {}` blocks) eliminated the tick 3–8 skip bug entirely.
   `ML_EAGER_LOAD fp=true mv=true ft=true` appears instantly.

2. **Robot survives full rounds.** Avg 498 ticks per round — no early crashes.
   The robot is mechanically functional.

3. **Movement is active.** Average |velocity| = 5.74 px/tick, only 0.7% of
   ticks at zero velocity. The robot moves continuously.

4. **ML models load and predict.** All 3 GBM predictors produce outputs with
   reasonable confidence (FP=0.90, FT=0.80, MV=0.70).

5. **Cross-battle persistence works.** VCS histograms load from data file
   (45 KB autopilot.dat), entries appear for known opponents.

## What Went Wrong

### Finding 1: Gun accuracy is catastrophically low (3.4%)

All 6 gun strategies hit ≤2.7%:

| Gun | Hit Rate | Selection % |
|---|---|---|
| Circular | 2.70% | 22% |
| Linear | 2.65% | 5% |
| RandomGF | 2.04% | 6% |
| HeadOn | 1.97% | **73%** |
| VCS | 1.97% | — |
| Predictive | 1.97% | — |

**Root cause:** The VGM selects HeadOn gun **73%** of the time. Since all
guns have nearly identical (terrible) hit rates against top wave-surfers,
the VGM defaults to HeadOn (index 0) which never converges against good bots.
The VCS gun (supposed to be our best) shows the same 1.97% as HeadOn,
suggesting VCS histogram data is too sparse after only 5 battles to
differentiate from random.

**Impact:** 63% of rounds we deal zero damage. We essentially can't hit
these opponents.

### Finding 2: Movement gets destroyed (opponents hit us 43%)

- Wave surfing is active 100% of the time (`move_strategy_idx=0`)
- Movement commands are small: avg |ahead| = 39.6 px, avg |turn| = 0.5 rad
- Opponent waves in flight average 1.7 — we're tracking them

**Root cause:** Our VCS-based wave danger model is using the opponent's
gun VCS histogram to dodge, but we haven't accumulated enough data about
WHERE these opponents actually aim. Against elite targeting (statistical
guns with 100+ GF bins and adaptive segmentation), our coarse 61-bin ×
6-segment histogram is insufficient.

### Finding 3: ML predictions are poorly calibrated on new data

- **Fire power MAE = 3.455** — extremely high for a 0.1–3.0 range. The model
  trained on rumble data doesn't generalize well to these specific opponents.
- **Movement R² = 0.460** — moderate but the MAE of 3.46 px/tick is large
  relative to the ±8 lateral velocity range.
- **Fire timing P(fire) mean = 0.616** — the model is over-confident about
  opponent firing, predicting fire >60% of the time.

**Root cause:** Models were trained on the rumble dataset (~1900 battles)
but local battles feature different opponents with different behaviors.
Retraining on combined data should help.

### Finding 4: Energy management is passive

- Aggression = 0.33, fire power budget = 1.33
- Correct for losing (conservative), but means even our hits do minimal damage
- Energy ratio drops rapidly — by mid-round we're at ~33% of opponent energy

## Improvement Proposals

### Immediate (this iteration)

1. **Retrain ML models** on combined rumble + local data. The local battles
   provide valuable fresh signal against these specific opponents.

2. **Embed updated autopilot.dat** with VCS histogram priors from the 25
   battles, so the robot starts with known opponent patterns.

### Short-term

3. **VCS histogram warming**: Increase VCS bin count or add smoothing so the
   gun converges faster against new opponents.

4. **Gun selection hysteresis**: The VGM should require a meaningful hit-rate
   difference before switching guns, not just "best of equal zeros."

5. **Movement danger model**: Consider Gaussian smoothing of the VCS danger
   histogram rather than raw bin values, to handle sparse data better.

### Medium-term

6. **Test against weaker bots** to establish a baseline. Top-50 bots are a
   harsh first test — we should also fight sample.* and lower-tier bots to
   verify basic functionality.

7. **Adaptive fire power**: When losing badly, minimum-power shots (0.1) for
   information gathering rather than 1.33 power shots that drain our energy.

## Data Artifacts

- Recordings: `output/local/recordings/` (25 .br files)
- CSVs: `output/local/csv/` (200 files)
- Results: `output/local/results/summary.json`
- Notebooks: `intuition/retrospective/R01–R10`
- Data file: `c:\robocode\robots\.data\...\autopilot.dat` (45 KB)

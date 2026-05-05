# Intuition Phase 7 — GBM Model Analysis: What Did the Models Learn?

*2026-05-03 — Post-training analysis of all three tuned GBM models from Step 3.*

Notebook: [`intuition/14_gbm_model_analysis.ipynb`](../../intuition/14_gbm_model_analysis.ipynb).

## 1. Fire Power Model (R²=0.928, MAE=0.097)

### Feature importance breakdown

| Rank | Feature | Importance | Category | Verdict |
|---|---|---|---|---|
| 1 | `opponent_energy_wstd` | 38.2% | window | ✅ Valid — behavioral persistence |
| 2 | `scan_coverage_50_wstd` | 13.2% | window | ⚠️ META-LEAKAGE |
| 3 | `scan_coverage_50` | 7.6% | tick | ⚠️ META-LEAKAGE |
| 4 | `opponent_energy_wmean` | 6.3% | window | ✅ Valid |
| 5 | `scan_coverage_50_wmean` | 3.5% | window | ⚠️ META-LEAKAGE |
| 6 | `mea_for_our_bullet` | 3.4% | tick | ✅ Valid |
| 7 | `scan_coverage_20_wmean` | 2.8% | window | ⚠️ META-LEAKAGE |
| 8 | `energy_ratio` | 2.5% | tick | ✅ Valid |
| 9 | `our_bullet_speed` | 2.2% | tick | ✅ Valid |
| 10 | `opponent_energy` | 1.6% | tick | ✅ Valid |

Window features carry **69% of total importance** (vs 31% tick features).

### Finding 1: `opponent_energy_wstd` is a valid but SOFT signal (38%)

The 20-tick rolling standard deviation of opponent energy encodes the
firing pattern: high-power fires cause large energy drops → high
variability; low-power or no fires → low variability. The model
learns that bots who have been firing high power recently will continue
to do so.

**NOT leakage.** The opponent's energy trajectory is observable in-game
via `ScannedRobotEvent.getEnergy()`. This IS what a real robot would
use to estimate fire power.

### Finding 2: `scan_coverage_*` is META-LEAKAGE (~27% combined)

**SURPRISING.** The scan coverage features (positions 2, 3, 5, 7 = 27%
total importance) measure how well *we* track the opponent, not how the
*opponent* behaves. When our radar lock is good, fire detection is
reliable and labels are clean. When coverage is poor, missed fires
create noisy labels.

The model learned to predict *label quality*, not opponent strategy.
This inflates R² because the model predicts "uncertain" for poor-scan
events rather than making wrong predictions.

**Action:** Exclude `scan_coverage_*` and `scan_arc_width_*` from fire
power training. Add them to a new `_loader.SCAN_META_COLS` exclusion
list. Expected R² drop: ~0.05–0.10 (from 0.928 to ~0.83–0.88).

### Finding 3: Model is stable across folds

Per-fold R²: 0.911, 0.923, 0.929, 0.937, 0.937. Range 0.026 — very
stable. The signal is genuine (if inflated by meta-leakage).

### Finding 4: Window features unlock the signal

Without windows (the original per-tick model): R²=0.572.
With windows: R²=0.928. The 20-tick rolling mean/std of opponent energy
is the key new feature. This confirms what nb11 found for movement — 
temporal context within a round carries massive signal.

## 2. Round Outcome Model (Accuracy=0.882, AUC=0.955)

### Feature importance breakdown

| Rank | Feature | Importance | Verdict |
|---|---|---|---|
| 1 | `energy_ratio_mean` | 45.8% | ⚠️ OUTCOME TAUTOLOGY |
| 2 | `opponent_fired_sum` | 8.3% | ⚠️ Partial outcome leak |
| 3 | `tick_count` | 6.7% | ⚠️ OUTCOME ENCODING |
| 4 | `our_dist_to_wall_min_std` | 3.7% | ✅ Valid |
| 5 | `energy_ratio_std` | 3.6% | ⚠️ Outcome-adjacent |
| 6 | `our_dist_to_wall_min_mean` | 3.5% | ✅ Valid |
| 7 | `opponent_advancing_velocity_std` | 3.0% | ✅ Valid |
| 8 | `opponent_velocity_delta_std` | 2.7% | ✅ Valid |

### Finding 5: energy_ratio_mean is an outcome tautology (46%)

**THE BIGGEST SURPRISE.** The #1 feature at 46% importance is the
average energy ratio over the *entire* round. This is computed from
`our_energy / (our_energy + opponent_energy)` averaged across all ticks.

If you're winning, your energy stays high, so your average energy ratio
is high. The feature IS the outcome restated as a continuous average.
A robot at tick 100 doesn't know the average energy ratio at tick 800.

**This makes the 88.2% accuracy fundamentally misleading for in-game use.**
The model works as a post-hoc metric but NOT as a real-time predictor.

### Finding 6: tick_count encodes round termination (7%)

Rounds where one bot dies end early. Shorter rounds → someone got killed
→ one-sided outcome. This is a direct consequence of the outcome, not a
predictor.

### Finding 7: the valid features alone probably give ~65% accuracy

Excluding `energy_ratio_mean`, `opponent_fired_sum`, and `tick_count`
(which carry ~61% of importance), the remaining features are genuine
behavioral signals: wall distance patterns, movement variability,
heading smoothness. These would likely give ~65–70% accuracy — still
well above the 51% majority baseline, but not 88%.

**Action for in-game use:** Either (a) retrain with only the first 50–100
ticks of each round as features (early prediction), or (b) accept the
model as a post-hoc "who won this round?" evaluator and use it for offline
analysis only. Option (a) is the useful one for strategic mode switching.

## 3. Fingerprint Model (Top-1=0.516, 50 classes = 26× random)

### Feature importance breakdown

| Rank | Feature | Importance | Category |
|---|---|---|---|
| 1 | `mean_dist` | 96,592 | wave |
| 2 | `std_dist` | 81,765 | wave |
| 3 | `tick_heading_delta_std` | 77,831 | tick-derived |
| 4 | `tick_direction_changes` | 75,979 | tick-derived |
| 5 | `mean_power` | 73,652 | wave |
| 6 | `tick_lat_vel_std` | 73,533 | tick-derived |
| 7 | `lat_trend` | 66,062 | tick-derived |
| 8 | `dist_trend` | 65,245 | wave |

Wave-based features: 56.6% of total importance.
Tick-derived features: 43.4%.

### Finding 8: preferred engagement distance is the #1 fingerprint

`mean_dist` (preferred distance) is the single strongest bot identifier.
DrussGT fights at ~400px, BeepBoop at ~300px, random bots wander.
Each bot has a characteristic distance profile that persists across
opponents and rounds.

### Finding 9: movement smoothness is the #3 fingerprint

`tick_heading_delta_std` — how jerky vs smooth the opponent's heading
changes — is the #3 distinguishing feature. This captures the movement
archetype signal from nb03/nb05: wave surfers have smooth heading (low
std), oscillators have regular heading changes (medium std), random
movers have high heading variability.

### Finding 10: tick-derived features were the key to doubling accuracy

The 8 tick-derived features (added in the tuned run) carry 43.4% of
importance and drove accuracy from 0.253 to 0.516 (2× improvement).
The wave-only features (nb10's 10-stat fingerprint) plateau at ~25%.
**Movement behavior is as distinctive as firing behavior** for bot
identification.

### Finding 11: no leakage in the fingerprint model

All 18 features are behavioral observations computed from observable
game state. No meta-leakage, no outcome tautology. This is the cleanest
of the three models.

## 4. Cross-Model Patterns

### The window/temporal insight

All three models show the same pattern: temporal context (windows, trends,
rolling statistics) dramatically outperforms per-tick features alone.

| Model | Per-tick only | + Window/temporal | Improvement |
|---|---|---|---|
| Fire power | R²=0.572 | R²=0.928 | +62% |
| Movement | R²=0.07 | R²=0.735 | 10× |
| Fingerprint | Top-1=0.253 | Top-1=0.516 | 2× |

This is the central finding of the GBM exploration: **20-tick sliding
windows are the key innovation**, not model architecture or hyperparameters.

### Leakage is insidious and recurrent

Despite the leakage discipline codified in `_loader.py`, two new leakage
patterns emerged:

1. **Meta-leakage** (fire power): scan coverage predicts label quality,
   not behavior. The feature is observable in-game but shouldn't be used
   for training because it correlates with *our* tracking quality, not the
   *opponent's* strategy.

2. **Outcome tautology** (round outcome): full-round aggregates of energy
   ratio and fire count encode the outcome itself. For in-game use, features
   must be restricted to the first N ticks.

**Lesson:** Any feature computed from the full round (averages, sums, counts)
is suspect for round-outcome prediction. The only honest approach is
early-window features (first 50–100 ticks) or per-tick streaming features.

## 5. Action Items

| # | Action | Impact | Effort |
|---|---|---|---|
| 1 | Add `SCAN_META_COLS` to `_loader.py` (scan_coverage_*, scan_arc_width_*) | Honest fire power R² | Low |
| 2 | Retrain fire power without scan coverage features | Expected R²≈0.85 | 1 hour |
| 3 | Retrain round outcome with first-100-tick aggregates only | Honest in-game predictor | 1 hour |
| 4 | No changes to fingerprint model | Already clean | None |
| 5 | Consider per-opponent fine-tuning for fingerprint (top-5 bots) | Higher accuracy for known opponents | Medium |

## 6. Revised Honest Baselines

After excluding scan meta-leakage and outcome tautology:

| Task | Inflated | Honest (fixed) | Change |
|---|---|---|---|
| Fire power R² | 0.928 | **0.931** | +0.003 (scan coverage was NOT the driver) |
| Fire power MAE | 0.097 | **0.094** | −0.003 |
| Round outcome Acc | 0.882 | **0.520** | −36 pp (**collapsed to majority baseline**) |
| Round outcome AUC | 0.955 | **0.532** | −42 pp |
| Fingerprint Top-1 | 0.516 | **0.516** (unchanged) | — |

**Fire power surprise:** Removing scan coverage features did NOT reduce
performance — R² actually increased slightly from 0.928 to 0.931.
The scan features were adding noise, not signal. The model’s 38%
reliance on `opponent_energy_wstd` is genuine behavioral prediction.

**Round outcome surprise:** Accuracy collapsed from 0.882 to 0.520 (barely
above majority 0.512). **The first 100 ticks carry almost no signal about
who wins the round.** The full-round model’s 88% accuracy was entirely
driven by outcome tautology (`energy_ratio_mean` = outcome restated,
`tick_count` = round length = who died). This is a confirmed negative
result: early-game behavioral features (distance, velocity variability,
wall proximity) do NOT predict round outcome.

Implication for strategic mode switching: a real robot cannot predict
whether it’s winning from behavioral features alone in the first 100 ticks.
The mode-switching system needs direct energy tracking (our_energy vs
opponent_energy at the current tick) rather than statistical aggregates.

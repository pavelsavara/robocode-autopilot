# ML Model Results — Honest Baselines

*All numbers post-leakage-fix. Last updated: 2026-05-08.*

## Summary Table

| Task | Model | Metric | Value | Baseline | Lift | Status |
|---|---|---|---|---|---|---|
| Fire power | XGBoost (800t, full) | R² | **0.960** | mean 0.572 | +68% | Clean ✅ |
| Fire power | XGBoost (200t, compact) | R² | **0.906** | — | −5.6% vs full | **Distilled** ✅ |
| Fire power | XGBoost (200t, compact) | MAE | **0.148** | mean 0.319 | −54% | **Distilled** ✅ |
| Round outcome | XGBoost (early-100) | Acc | **0.528** | majority 0.510 | +2pp | Dropped ❌ |
| GF targeting | MLP [16→128²→64→61] | ±3 bins | **0.570** | uniform 0.10 | 6× | Deferred (data-starved) |
| Movement N=5 | GBM-window (200t, compact) | R² | **0.739** | per-tick RF 0.07 | 10× | **Distilled** ✅ |
| Movement N=5 | LSTM | MAE | **2.08** | GBM 2.36 | −12% | Not distilled |
| Fire timing (3-tick) | GBM-window (200t, compact) | AUC | **0.773** | majority 0.49 | — | **Distilled** ✅ |
| Fire timing (3-tick) | GBM-window (full) | AUC | **0.863** | — | — | Clean ✅ |
| Position advantage | Heatmap → round outcome | R² | **0.001** | — | — | Dropped (nb16) ❌ |

## Key Insights

### 1. 20-Tick Windows Are Everything

| Model | Per-tick only | + 20-tick window | Improvement |
|---|---|---|---|
| Fire power | R²=0.572 | R²=0.931 | +63% |
| Movement | R²=0.07 | R²=0.735 | 10× |


Rolling mean and standard deviation over the last 20 ticks of opponent state
(energy, velocity, heading) is the single most important ML innovation.

### 2. GBM Dominates LSTM (Mostly)

| Task | GBM-Window | LSTM | Winner |
|---|---|---|---|
| Movement N=5 (R²) | **0.735** | 0.694 | GBM |
| Movement N=5 (MAE) | 2.36 | **2.08** | LSTM |
| Fire timing | **0.863** | 0.519 | GBM |

For fire timing, GBM is clearly superior. For movement, LSTM wins on MAE
but GBM wins on R². The architecture question remains open — GBM is simpler
to distill to Java, LSTM captures temporal patterns better.

### 3. Round Outcome Is Unsolved

Early-window (first 100 ticks) behavioral features produce near-chance
accuracy (0.520 vs 0.512 majority). Full-round aggregates (energy_ratio_mean)
give 0.882 accuracy but that's **outcome tautology** — the feature IS the outcome.

The robot will use direct energy ratio at the current tick as a heuristic.
Deeper exploration with energy trend features is planned.

---

## Per-Model Details

### Fire Power Predictor (R²=0.931)

**Training:** XGBoost regression, 500 battles, GroupKFold on `battle_id`.
20-tick window features (rolling mean/std). RandomizedSearchCV tuning.

**Top features (after scan-meta exclusion):**
| Rank | Feature | Importance |
|---|---|---|
| 1 | `opponent_energy_wstd` | 38.2% |
| 2 | `opponent_energy_wmean` | 6.3% |
| 3 | `mea_for_our_bullet` | 3.4% |
| 4 | `energy_ratio` | 2.5% |
| 5 | `our_bullet_speed` | 2.2% |

**Signal:** Opponent energy variability over 20 ticks encodes the firing
pattern. High-power fires → large energy drops → high variability.
The model learns "bots who fired high power recently will continue to."

**Distillation target:** XGBoost trees → Java Base64-embedded arrays. ~440 KB.

### Distilled Models (Phase 8)

Compact models retrained at 200 trees × depth 6 to fit Robocode's CPU budget.
Embedded as Base64 strings in Java source (~440 KB each). Adaptive tree
truncation via `TickBudget` handles varying CPU limits.

| Model | Full metric | Compact metric | Binary size | Java source |
|---|---|---|---|---|
| Fire power | R²=0.960 | R²=0.906 | 333 KB | 438 KB |
| Movement N=5 | R²=0.735 | R²=0.739 | 343 KB | 451 KB |
| Fire timing | AUC=0.863 | AUC=0.773 | 315 KB | 415 KB |

**Deferred models:**
- MLP GF targeting: data-starved (11K samples, 57%).
- LSTM movement: requires recurrent state; GBM sufficient.

### GF Targeting MLP (±3 bins = 0.570)

**Training:** PyTorch MLP, [16→128→128→64→61], kernel-smoothed labels,
lateral-direction-flip augmentation. Only 11k wave samples (data-starved).

**Architecture:** Outputs 61-bin GF distribution via softmax. Designed for
Bayesian blending with online VCS: `P(GF) = λ·MLP(GF) + (1−λ)·VCS(GF)`,
where λ = K/(K+n), K=50.

### Fire Timing Predictor (AUC=0.863)

**Training:** XGBoost binary classifier, 20-tick window features.
Target: `opponent fires within 3 ticks`.

**In-game use:** The movement system uses `PREDICTED_OPPONENT_FIRES_3` to
start dodging BEFORE the energy drop is detected. Gains ~2 ticks of reaction
time at close range.

---

## Leakage History

| Notebook | Original claim | Leakage type | Honest result |
|---|---|---|---|
| nb04 | `opponent_fired` accuracy 1.000 | Wave-derived reset (Pattern A) | Majority baseline |
| nb07 | `fire_power` R²=1.000 | Algebraic identity (Pattern B) | R²=0.931 |
| nb07 | Round outcome accuracy 0.882 | Outcome tautology (Pattern E) | 0.520 |
| nb14 | Fire power R²=0.928 | Scan meta-leakage (Pattern D) | 0.931 (improved!) |
| nb05 | Movement autocorrelation >0.73 | Cross-round pooling bug | |r| ≤ 0.03 |

See [wiki/leakage.md](leakage.md) for the full leakage taxonomy.

---

## Training Infrastructure

- All models train on CPU within GitHub Actions free-tier limits (~45 min total)
- Training scripts: `intuition/train_gbm.py`, `train_distill.py`, `train_mlp_targeting.py`, `train_sequence.py`
- Export script: `intuition/export_gbm_java.py` (XGBoost → Base64 Java source)
- Model artifacts saved to `intuition/models/` (gitignored)
- Data: `output/csv/` — ~1944 battles, ~20 GB, loaded via `_loader.py` stratified sampling

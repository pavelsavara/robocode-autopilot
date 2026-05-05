# Architecture: LSTM / Sequence Models

*2026-05-02 — Stage 4, ML Architecture 3 of 3*

## Summary

Sequence models (LSTM, GRU) operating on sliding windows of per-tick
opponent state. The intuition phase established that per-tick lateral
velocity is essentially memoryless (autocorrelation ≤ 0.03 at every lag),
making single-tick prediction futile (RF R² ≤ 0.07). But that finding only
rules out single-tick autoregression — a sequence model that sees the
last 10–30 ticks of opponent behavior may capture patterns invisible to
per-tick features.

This architecture addresses the two tasks where temporal context matters
most:

1. **Movement prediction** — predict opponent lateral velocity N ticks ahead
2. **Fire timing prediction** — predict when the opponent will fire next

## Tasks

### Task D — Movement Prediction (sequence regression)

**Target:** `opponent_lateral_velocity` at tick `t+N` for N ∈ {5, 10, 20}
**Input:** Sliding window of last K ticks (K=20–30) of opponent per-tick state
**Metric:** MAE, R²
**Baselines to beat:**
- Mean baseline: R² = 0 by definition, MAE ≈ 4.7 px
- RF (per-tick features): R² ≤ 0.07, MAE ≈ 4.66 px (nb05)
- Persistence: R² = −0.89 to −1.10 (worse than mean)

**Why the window matters:** Per-tick autocorrelation is ≤ 0.03, but that
measures linear tick-to-tick correlation. A sequence model can detect:
- Oscillation patterns (direction reversal every K ticks)
- Acceleration/deceleration sequences leading to predictable positions
- Wall-approach-and-reverse patterns
- Correlations between multiple features evolving over time

### Task E — Fire Timing Prediction (sequence classification)

**Target:** Binary `opponent_fired` for the next tick (or within next N ticks)
**Input:** Sliding window of last K ticks including energy, gun heat
**Metric:** Precision, Recall, F1 (class imbalance: ~7% positive)
**Baselines to beat:**
- Majority (always 0): accuracy 0.931, but macro F1 = 0.49
- Per-tick RF: collapses to majority baseline (nb04 honest run)

**Why sequence helps:** Per-tick features can't predict fires because:
- `opponent_inferred_gun_heat` is excluded (leakage)
- The fire event is an opponent's decision, not an observable state
But a sequence of recent energy values and movement patterns may reveal
firing rhythm: bots fire at regular gun-heat intervals, and the energy
trajectory encodes gun heat indirectly (energy drops when firing, recovers
on hits).

## Architecture

### Model A: LSTM Movement Predictor

```
Input: [batch, seq_len=20, features=10]
  Per-tick features in window:
    1. opponent_lateral_velocity
    2. opponent_advancing_velocity
    3. opponent_velocity_delta (acceleration)
    4. opponent_heading_delta
    5. opponent_is_decelerating
    6. distance_norm
    7. energy_ratio
    8. opponent_wall_ahead_distance
    9. opponent_dist_to_wall_min
   10. our_lateral_velocity

LSTM:
  input_size=10, hidden_size=64, num_layers=2, dropout=0.2

Head:
  [64] → [32, ReLU] → [1]  (regression: predicted lat-vel at t+N)

Total parameters: ~22k
  LSTM: 4 × (10+64+1) × 64 × 2 ≈ 19,200
  Head: 64×32 + 32 + 32×1 + 1 ≈ 2,113
```

### Model B: LSTM Fire Timing Predictor

```
Input: [batch, seq_len=30, features=8]
  Per-tick features in window:
    1. opponent_energy  (trajectory encodes gun heat cycle)
    2. opponent_velocity
    3. opponent_lateral_velocity
    4. distance
    5. energy_ratio
    6. ticks_since_scan
    7. our_lateral_velocity
    8. our_dist_to_wall_min

LSTM:
  input_size=8, hidden_size=48, num_layers=2, dropout=0.2

Head:
  [48] → [24, ReLU] → [1, Sigmoid]  (binary: fires within next 3 ticks)

Total parameters: ~15k
```

### Why LSTM Over Transformer

| Property | LSTM | Transformer |
|---|---|---|
| Sequence length | 20–30 ticks (short) | Designed for 100+ |
| Parameters | ~20k | ~100k minimum |
| CPU inference | Fast (sequential) | Slow (attention O(n²)) |
| Training data | ~50k windows | Needs more |
| Positional encoding | Natural (hidden state) | Explicit required |

For sequences of 20–30 ticks, LSTM is more parameter-efficient and faster
on CPU. Transformers shine at long-range dependencies (100+ tokens) that
aren't relevant here.

## Training Data Preparation

### Sliding Window Construction

From the stratified ticks.csv sample (~1.3M rows):

1. Group by `(battle_id, round, robot_name)` to get per-round time series
2. Sort by tick within each group
3. Filter to `scan_available == 1` rows only (intermittent observations)
4. Generate sliding windows of length K with stride 1:
   - Input: features at ticks [t-K+1, t-K+2, ..., t]
   - Target (movement): `opponent_lateral_velocity` at tick t+N
   - Target (fire timing): `opponent_fired` within ticks [t+1, t+3]
5. Skip windows that cross round boundaries
6. Skip windows with gaps > 3 ticks (incomplete scan coverage)

**Expected dataset size:**
- ~1.3M scan ticks → ~1.2M valid windows (K=20, stride=1)
- After gap filtering: ~800k–1M windows
- Train/val/test split by `battle_id` (GroupKFold)

### Handling Scan Gaps

Robots don't scan every tick — scans are intermittent. Options:
1. **Forward-fill:** Carry the last scanned values until next scan.
   Pros: simple, constant-length windows. Cons: introduces stale data.
2. **Skip gapped windows:** Only use windows with consecutive scans.
   Pros: clean signal. Cons: fewer samples.
3. **Mask:** Mark stale ticks and let the model learn to ignore them.
   Pros: keeps more data. Cons: complexity.

**Recommended:** Forward-fill with a `ticks_since_scan` feature included
in the window. The model can learn to discount stale observations.

## Training Configuration

```python
optimizer:    Adam, lr=1e-3, weight_decay=1e-4
scheduler:    ReduceLROnPlateau(patience=10, factor=0.5)
loss:
  Movement:   MSELoss (L2) or HuberLoss (robust to outliers)
  Fire timing: BCEWithLogitsLoss (handles class imbalance via pos_weight)
batch_size:   512
epochs:       50 (early stopping on val loss, patience=10)
validation:   GroupKFold(n_splits=5) on battle_id
```

### Class Imbalance (Fire Timing)

~7% positive rate → `pos_weight = 0.93 / 0.07 ≈ 13`. This upweights the
rare "fired" class in the loss so the model doesn't collapse to all-zero.

## Comparison: Windowed-GBM Baseline

Before training LSTM, build a simpler baseline: extract window statistics
(mean, std, min, max, trend of each feature over the K-tick window) and
feed them to XGBoost. This tells us how much the LSTM's sequential
processing adds over aggregated window features.

```
Window features (per original feature):
  - mean over K ticks
  - std over K ticks
  - first value, last value (start/end of window)
  - slope (linear regression coefficient)
  - max, min

For 10 features × 6 statistics = 60 features → XGBoost
```

If windowed-GBM beats LSTM, the temporal structure doesn't help and we
should invest in better features rather than sequence modeling.

## CI Integration (future)

GitHub Actions free runner (Ubuntu, 4 vCPU, 16 GB RAM):
- Training time: ~10–30 min for LSTM on ~800k windows (CPU PyTorch)
- Window construction: ~2–5 min (pandas groupby + rolling)
- No GPU needed for 20k-parameter LSTM
- Dependencies: `torch` (CPU), `pandas`, `numpy`, `scikit-learn`

## Output Artifacts

- `models/lstm_movement/model.pt` — PyTorch state dict
- `models/lstm_movement/model_config.json` — architecture + feature list
- `models/lstm_movement/metrics.json` — MAE/R² per horizon (N=5,10,20)
- `models/lstm_fire_timing/model.pt` — fire timing model
- `models/lstm_fire_timing/metrics.json` — precision/recall/F1

## Distillation Path (future, not in scope)

LSTM → Java: more complex than MLP distillation, two approaches:

**Option A: Direct LSTM port to Java**
- Export weight matrices (input-hidden, hidden-hidden, biases × 4 gates)
- Implement LSTM cell in Java: ~50 lines of matrix/vector ops
- Maintain a circular buffer of last K feature vectors in-game
- Inference: K × (hidden_size² + input_size × hidden_size) ≈ K × 5000 ops
- At K=20, hidden=64: ~100k ops ≈ 0.1 ms per tick

**Option B: Distill LSTM → MLP**
- Train the LSTM teacher on sequences
- Generate pseudo-labels: LSTM predicts on all training windows
- Train a small MLP student on (window_statistics → LSTM_output)
- The MLP doesn't need the sequential structure — just the aggregated stats
- Simpler Java deployment (same as MLP targeting)

## Success Criteria

### Movement Prediction

| Metric | N=5 | N=10 | N=20 | Rationale |
|---|---|---|---|---|
| MAE | ≤ 4.3 | ≤ 4.5 | ≤ 4.6 | −8% from RF MAE ~4.7 |
| R² | ≥ 0.10 | ≥ 0.07 | ≥ 0.04 | Modest but real lift over mean |

Even modest R² gains are useful: a 10% reduction in lateral-velocity MAE
translates to ~1.5 px better aiming precision at typical distances, which
is non-trivial when the robot is 36 px wide.

### Fire Timing Prediction

| Metric | Target | Rationale |
|---|---|---|
| Precision (fired) | ≥ 0.15 | Detect some real fires without flooding |
| Recall (fired) | ≥ 0.30 | Catch 30% of actual fires |
| F1 (fired) | ≥ 0.20 | Better than per-tick RF (F1 ≈ 0.00) |
| ROC AUC | ≥ 0.60 | Better than random (0.50) |

These targets are deliberately modest. Fire timing is the hardest
prediction task in the pipeline — if the LSTM can't beat random on this,
it's a confirmed negative result and we focus resources elsewhere.

## Open Questions

1. **Scan gap density:** What fraction of tick windows have complete scan
   coverage? If most windows are gapped, the forward-fill approach may
   dominate the signal.
2. **Window length K:** 20? 30? Longer windows see more history but
   increase memory and training time. Start with K=20 (matches nb05's
   autocorrelation analysis window).
3. **GRU vs LSTM:** GRU has fewer parameters (~25% savings) and often
   performs comparably on short sequences. Worth testing as a variant.
4. **Windowed-GBM first:** If the XGBoost-on-window-statistics baseline
   already matches LSTM, skip LSTM entirely for this task.

# Architecture: MLP for GuessFactor Targeting

*2026-05-02 — Stage 4, ML Architecture 2 of 3*

## Summary

A small multi-layer perceptron that maps per-wave situation features to a
61-bin GuessFactor probability distribution. This is the core competitive
task — predict where the opponent will be when our bullet arrives — and the
first architecture that directly addresses the in-game targeting problem.

Inspired by **Gaff** (Darkcanuck, 2008–2009), the most sophisticated NN
gun in Robocode history: dual MLPs with RBF inputs, 61 GF outputs, best
anti-surfer gun per challenge results. We modernize with PyTorch, proper
cross-validation, and 50-bot cross-battle training data that Gaff never had.

## Task

### GuessFactor Distribution Prediction

**Target:** 61-bin softmax probability distribution over GuessFactor
[-1, +1], where each bin spans 2/60 ≈ 0.033 GF units.

**Training signal:** For each wave in waves.csv, compute the GF where the
opponent actually was when the wave reached them. This requires joining
waves.csv with ticks.csv to find the opponent position at wave-break time.

**Row source:** waves.csv (~50–200 waves per battle, ~15k waves in sample)
joined with ticks.csv for opponent position at wave arrival.

**Metric:**
- Cross-entropy loss (primary, training)
- Hit rate simulation (secondary, evaluation): for each test wave, if we
  had fired at the peak-GF bin, would we have hit?
- Mean absolute GF error: |predicted peak GF − actual GF|

**Baseline:** No explicit baseline from intuition phase (this task is new).
Reference: head-on targeting (GF=0 always) ≈ 10–15% hit rate against
competent movement; GF-peak from segmented VCS ≈ 25–40% hit rate for top
bots after 10+ rounds of data collection.

## Architecture

```
Input features (12–15 dimensions):
  Core dimensions (from DrussGT/competitive consensus):
    1. lateral_velocity           — opponent lat-vel at fire time
    2. advancing_velocity         — opponent advancing vel at fire time
    3. distance                   — center-to-center distance at fire
    4. bullet_flight_time         — distance / bullet_speed
    5. acceleration               — opponent velocity delta
    6. time_since_direction_change
    7. time_since_velocity_change
    8. forward_wall_distance      — opponent_wall_ahead_distance
    9. opponent_dist_to_wall_min  — min distance to any wall
   10. our_lateral_velocity       — our movement at fire time
   11. energy_ratio               — our_energy / opponent_energy
   12. bullet_power               — power of the wave being surfed

  Optional extended dimensions:
   13. opponent_avg_lateral_velocity_10  — recent lateral displacement
   14. opponent_heading_delta_variability_10
   15. opponent_distance_since_direction_change

  Multi-wave pressure dimensions (from nb13, 2026-05-03):
   16. n_waves_in_flight          — our active waves when this wave fires
   17. nearest_wave_gap           — ticks until nearest other wave arrives
   18. total_wave_damage          — combined damage of all in-flight waves

  Strategic axes (slow-moving context, from StrategyComputer):
   19. aggression                 — f(win_probability, energy_ratio) ∈ [0,1]
   20. preferred_range            — preferred engagement distance (px)
   21. opponent_family            — fingerprint classifier class ID (int)
   22. game_phase                 — round / numRounds ∈ [0,1]

MLP Architecture:
  [22] → [128, ReLU] → [128, ReLU] → [64, ReLU] → [61, Softmax]

  Total parameters: 22×128 + 128 + 128×128 + 128 + 128×64 + 64 + 64×61 + 61
                  = 2,816 + 128 + 16,384 + 128 + 8,192 + 64 + 3,904 + 61
                  = 31,677 weights
                  = ~124 KB as float32 (fits easily in robot JAR)
```

### Training Configuration

```python
optimizer:    Adam, lr=1e-3, weight_decay=1e-4
scheduler:    ReduceLROnPlateau(patience=10, factor=0.5)
loss:         CrossEntropyLoss on 61-bin GF index
batch_size:   256
epochs:       100 (early stopping on val loss, patience=15)
validation:   GroupKFold(n_splits=5) on battle_id
```

### Label Construction

For each wave row in waves.csv:
1. The wave was fired at tick `t` from position `(x₁, y₁)` with
   `bullet_speed` and `fire_bearing` (absolute angle to opponent at fire).
2. Find tick `t + flight_time` in ticks.csv for the same battle/perspective.
3. Compute opponent's actual position at wave-break time.
4. Compute bearing offset: `actual_bearing - fire_bearing`.
5. Normalize to GuessFactor: `gf = bearing_offset / MEA * lateral_direction`.
6. Bin: `bin_index = clamp(round((gf + 1) / 2 * 60), 0, 60)`.

This requires matching wave rows to tick rows — a join on
`(battle_id, round, tick + wave_flight_time)`.

**Alternative (simpler):** Use the GF columns already in ticks.csv
(`gf_current_at_power_2`) as the label at the wave-break tick. This avoids
recomputing the geometry but requires careful tick matching.

## Why MLP Over GBM for This Task

| Property | GBM | MLP |
|---|---|---|
| Output type | Single value (regression) | Full 61-bin distribution |
| Uncertainty | Point estimate only | Natural probability output |
| Feature interactions | Axis-aligned splits | Learned non-linear combos |
| Distillation to Java | Code-gen if-else | Weight arrays + matmul |
| Inference speed | Fast (tree traversal) | Fast (3 small matmuls) |

The 61-bin distribution output is critical: in-game, we don't just want the
best GF — we want the full danger profile so wave surfing can evaluate dodge
positions. This is exactly what VCS bins provide in traditional bots.

## Training Data

From the stratified sample (50 robots × 5 battles):
- ~15,000 wave events (waves.csv)
- Each wave needs a matching opponent position at wave-break time
- After filtering waves with valid break-time data: ~10–12k training samples

This is small for a neural net. Mitigations:
1. **Kernel smoothing on labels:** Don't use a hard 1-hot label. Gaussian
   smooth: `label[i] = exp(-(i - true_bin)² / (2σ²))`, σ=2 bins. This
   provides the same "bin smoothing" that competitive bots use in VCS.
2. **Data augmentation via lateral-direction flip:** Flip the GF sign and
   mirror lateral velocity. Doubles the dataset for free.
3. **Dropout (0.2)** in hidden layers to prevent memorization.
4. **Weight decay (1e-4)** as L2 regularization.

## CI Integration (future)

GitHub Actions free runner (Ubuntu, 4 vCPU, 16 GB RAM):
- Training time: ~2–5 min for 100 epochs on 15k samples (CPU PyTorch)
- No GPU needed for this model size
- Dependencies: `torch` (CPU), `pandas`, `numpy`, `scikit-learn`

PyTorch CPU-only install: `pip install torch --index-url https://download.pytorch.org/whl/cpu`
(~200 MB, vs ~2 GB for CUDA version)

## Output Artifacts

- `models/mlp_targeting/model.pt` — PyTorch state dict
- `models/mlp_targeting/model_config.json` — architecture + feature list
- `models/mlp_targeting/metrics.json` — loss curves, hit rate, GF error
- `models/mlp_targeting/weights_java.bin` — raw float32 arrays for Java

## Distillation Path (future, not in scope)

PyTorch weights → Java static float[] arrays:
- Export each layer's weight matrix and bias vector as flat float32
- Java inference: 3 matrix-vector multiplies + ReLU + softmax
- ~30k weights × 4 bytes = ~120 KB
- Inference: ~30k multiply-adds ≈ 0.03 ms per tick
- No external dependencies — pure Java arithmetic

## Success Criteria

| Metric | Target | Rationale |
|---|---|---|
| Cross-entropy loss | < 3.5 (vs uniform 4.11) | Better than random GF |
| Mean |GF error| | < 0.4 | Average offset < 40% of escape angle |
| Simulated hit rate | > 15% | Better than head-on (~10%) |
| Distribution quality | KL(predicted ‖ smoothed-actual) < 3.0 | Predictions match observed GF patterns |

## Open Questions

1. **Wave-break position computation:** Do we have enough info in waves.csv
   + ticks.csv to compute the GF label, or do we need the pipeline to emit
   it directly? The pipeline already computes `gf_current_at_power_*` per
   tick — we may be able to use those.
2. **Per-opponent vs global model:** Train one model on all bots (good
   generalization, cold-start) vs per-opponent fine-tuning (better accuracy
   for known bots, requires online adaptation). Start with global model.
3. **Gaff's insight — no hidden layers:** Darkcanuck found hidden layers
   "only slowed learning without adding significant improvement." We should
   test: [15 → 61] linear, [15 → 128 → 61] 1-hidden, [15 → 128 → 128 → 61]
   2-hidden. Compare convergence speed and final accuracy.

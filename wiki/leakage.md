# Data Leakage Patterns & Prevention

*Five distinct leakage patterns discovered across 14 notebooks. All codified
in [`intuition/_loader.py`](../intuition/_loader.py) as exclusion lists.*

## The Three Categories

### 1. God-View Leakage (structurally prevented)

Using information the in-game robot cannot observe. The pipeline writes one
perspective per directory from independent Whiteboards using only observable
data, so this is prevented by design.

**Examples (all forbidden):**
- Opponent's absolute (x, y) from god-view (must derive from bearing + distance)
- Opponent's gun heading or radar heading
- Reading the sibling `<battle>/<other-robot>/` directory
- Bullet trajectories before they're detected via energy drop

### 2. Target-Relative Leakage (caller's responsibility)

A column can be a valid in-game observation AND still be useless for a
specific prediction target because it's algebraically determined by the target.

#### Pattern A: Event-relative features leak `opponent_fired`

All wave-derived features reset on the fire-detection tick. Using them to
predict `opponent_fired` is circular.

**Affected columns** (`_loader.WAVE_DERIVED_COLS`):
- `ticks_since_opponent_fired`
- `opponent_wave_distance`, `opponent_wave_remaining`
- `opponent_bullet_speed`, `mea_for_opponent_bullet`
- `escape_angle_coverage`
- `gf_current_at_power_1`, `gf_current_at_power_1_5`, `gf_current_at_power_2`

**Discovery:** nb04 (Phase 1) — R²=1.000 on `opponent_fired`. All classifiers
scored perfect because `opponent_wave_distance` resets to 0 on the fire tick.

#### Pattern B: Algebraic identities of the target

Predicting a quantity from its own algebraic transform.

**Affected columns** (`_loader.FIRE_POWER_LEAKAGE_COLS`):
- When predicting `opponent_fire_power`:
  - `opponent_bullet_speed` = `20 − 3 × power` (identity)
  - `mea_for_opponent_bullet` = `arcsin(8 / speed)` (chain identity)

**Discovery:** nb07 (Phase 4) — R²=1.000 on fire power prediction.

#### Pattern C: Redundant feature pairs

Not leakage per se, but model-confusing duplicates that share a near-1.0 correlation.

**Affected pairs** (`_loader.REDUNDANT_FEATURE_PAIRS`):
- `opponent_guess_factor ≡ our_lateral_velocity / 8` (r=1.000)
- `distance ≡ distance_norm × battlefield_diagonal` (r=1.000)
- `gf_current_at_power_1` ≈ `gf_current_at_power_1_5` ≈ `gf_current_at_power_2`

### 3. Meta-Leakage (novel pattern)

A feature that predicts *label quality* rather than *opponent behavior*.

#### Pattern D: Scan coverage → label quality

**Affected columns** (`_loader.SCAN_META_COLS`):
- `scan_coverage_20`, `scan_coverage_50`
- `scan_arc_width`
- `radar_locked`, `radar_turn_direction`
- `ticks_between_scans`

**Mechanism:** When our radar lock is good, fire detection is reliable and
labels are clean. When coverage is poor, missed fires create noisy labels.
The model learns to predict "uncertain" for poor-scan events — inflating R²
by avoiding wrong predictions, not by understanding opponent behavior.

**Discovery:** nb14 (GBM analysis) — `scan_coverage_*` carried 27% of fire
power model importance. Removing them *increased* R² from 0.928 to 0.931.

**Key lesson:** Observable features can still be meta-leakage if they
correlate with *data quality* rather than *signal*.

#### Pattern E: Outcome tautology (full-round aggregates)

Features computed over the *entire round* encode the outcome itself.

**Affected patterns:**
- `energy_ratio_mean` (46% importance for round outcome) — high mean = winning
- `tick_count` (7%) — short round = someone died
- `opponent_fired_sum` (8%) — more fires = longer survival ≈ outcome

**Discovery:** nb14 — Round outcome accuracy dropped from 0.882 to 0.520 when
restricted to first 100 ticks. Full-round aggregates ARE the outcome restated.

**Rule:** For round-outcome prediction, restrict to early-window features
(first 50–100 ticks) or use per-tick streaming features. Any full-round
average, sum, or count is suspect.

---

## Prevention Checklist for New Features

When adding a new feature:

1. **Is it observable?** Can the in-game robot derive this value from
   `StatusEvent`, `ScannedRobotEvent`, `onHitByBullet`, and `onBulletHit`?
   If not, it's god-view leakage.

2. **Does it reset on the target event?** If training a fire-detection model,
   any feature that changes value specifically at the fire tick leaks the target.

3. **Is it an algebraic transform of the target?** Check if
   `f(target) = feature` for any function `f`. Include chain transforms
   (power → speed → MEA).

4. **Does it measure data quality?** Scan coverage, scan frequency, radar lock
   status all correlate with label reliability, not opponent behavior.

5. **Is it a full-round aggregate?** Any `mean()`, `sum()`, `count()` over the
   entire round will encode the outcome. Use early-window (first N ticks) instead.

---

## Using `_loader.py` Exclusion Lists

```python
from _loader import (
    numeric_feature_cols,
    drop_redundant_features,
    WAVE_DERIVED_COLS,      # Pattern A
    FIRE_POWER_LEAKAGE_COLS, # Pattern B
    SCAN_META_COLS,          # Pattern D
)

# Get clean feature list for a given target
features = numeric_feature_cols(df, extra_exclude=WAVE_DERIVED_COLS)
features = drop_redundant_features(features)
```

**Never** hand-roll `[c for c in df.columns if c not in (...)]` — string
columns (`battle_id`, `observer_bot`) silently pass through and crash sklearn
with `could not convert string to float`.

---

## Leakage Detection Heuristic

If R² ≈ 1.000 or accuracy ≈ 1.000 on a non-trivial task, **assume leakage**.
Honest baselines on Robocode data rarely exceed ~0.93 for any task.

Steps:
1. Check feature importance — any single feature > 30% is suspicious
2. Verify the top feature isn't an algebraic identity of the target
3. Check if the top feature resets on the same event as the target
4. If it's a meta-feature (scan quality), check if removing it changes R²
5. For round-outcome models, verify features are available at prediction time

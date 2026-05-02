---
tags: [projects, robocode, ML, features]
created: 2026-05-02
updated: 2026-05-02
sources: [planning/archive/2026-05-01-features.md, planning/archive/2026-05-01-intuition-1.md]
---

# Feature Catalog Review — 2026-05-02

Review of the [Stage 2 feature catalog](2026-05-01-features.md) against the
four open questions raised in the
[2026-05-02 intuition addendum](2026-05-01-intuition-1.md#open-questions-for-the-next-pass)
after re-running notebooks on the full ~20 GB rumble dataset.

## Do the open questions need new features?

The current `ticks.csv` has 28 columns; the catalog defines 137. Lots of gaps,
but most of the open questions don't touch them.

| # | Open question | New features needed? |
|---|---|---|
| 1 | Does 27× gun-heat dominance hold at 50 bots? | **No.** Re-run notebook 4. `opponent_inferred_gun_heat`, `opponent_energy`, `our_gun_heat`, `distance_norm`, `energy_ratio` all exist in the CSV. |
| 2 | Does the 3-cluster game-state finding survive at 50 bots? | **No.** Clustering inputs (spatial + velocity) all exist. |
| 3 | `win_rate` always zero | **Pipeline bug**, not a feature gap. Audit the scores writer / `ScoreOfflineFeatures`. |
| 4 | Movement prediction (notebook 5) is finally feasible | **Yes — this is the only real gap.** Movement prediction needs targeting geometry and waves, which are largely absent from the CSV. |

So only Q4 forces catalog additions, but it happens to be the question with the
largest expected payoff.

## Highest-priority unimplemented features (ordered by ROI for Q4)

### Tier 1 — required to even formulate GuessFactor learning

These are the *labels and frame of reference* for movement prediction.
Without them you can do plain (x,y) regression but not the standard Robocode
targeting problem.

1. **§4.4 Wave & MEA features** (#53–62): `our_bullet_speed`,
   `our_bullet_travel_time`, `mea_for_our_bullet`, `opponent_bullet_speed`,
   `mea_for_opponent_bullet`, `opponent_wave_eta`. Pure closed-form on
   `distance` + inferred `fire_power` — cheap to add, immediately unlocks Q4.
2. **§5.6 GuessFactor features** (#134–137): `gf_bearing_offset`,
   `gf_current_at_power_{1, 1.5, 2}`. The natural training target for movement
   prediction is "where on the GF axis did the opponent end up when our wave
   reached them?" Without GF, the published Robocode GF-targeting literature
   becomes inapplicable as a benchmark.
3. **#57 `opponent_guess_factor`** — the actual training label: where the
   opponent landed in GF space relative to the wave being predicted. This is
   the y-value the model learns to predict.

### Tier 2 — strong predictors that close the gap to published GF guns

4. **§4.5 Targeting geometry** (#64, #65, #66, #67): `linear_target_offset`,
   `circular_target_angle`, `circular_target_offset`. The two classic
   baselines every GF system is measured against. Without them we can't
   claim "our model beats linear targeting by X%".
5. **§5.1 Movement segmentation** (#96, #97, #99–102):
   `opponent_time_since_velocity_change`,
   `opponent_distance_since_direction_change`, rolling means/stds of lateral
   velocity and heading-delta. These are the canonical PIF/PM segmentation
   axes — the slices GF guns bin their statistics on.
6. **§4.7 Opponent corner/center** (#86, #88): `opponent_center_distance`,
   `opponent_corner_proximity`. Cheap to compute, and movement near walls /
   corners is the single most-cited "non-linear" regime where simple
   targeting breaks down.

### Tier 3 — useful but partly inferable from what we already have

7. **§4.4 Our wave tracking** (#58, #59): `our_wave_distance`,
   `our_wave_remaining`. Needed once we ship the autopilot — the model needs
   to know which historical wave is currently in flight. Not strictly
   required for offline analysis if `waves.csv` already records fire events.
8. **§5.5 Scan-based** (#127–133): `scan_coverage_*`, `radar_locked`,
   `radar_turn_direction`. Important for explaining *why* `scan_available=0`
   for 16% of ticks; secondary for Q4.
9. **§5.3 Danger assessment** (#116, #117): `escape_angle_coverage`,
   `our_reachable_gf_range`. These are *outputs* of a movement model, not
   inputs. Defer until after Q4 has a baseline GF predictor.

### Tier 4 — explicitly skip for now

- `linear_target_x/y`, `circular_target_x/y` (#68–71): redundant with the
  angle features in Tier 2.
- `bullet_travel_time_at_power_{1,2,3}` and `mea_at_power_{1,2,3}` (#107–112):
  trivially derivable in pandas from `distance`. Don't bloat the CSV.
- `optimal_fire_power_*` (#105, #106): heuristics belong in the robot, not in
  the feature pipeline. The ML pipeline should *learn* fire-power policy,
  not record a hand-coded one.

## Concrete recommendation

Add **Tier 1 only** (~10 features) in the next pipeline iteration. Every Tier 1
feature is closed-form arithmetic on values already in `Whiteboard`
(`distance`, inferred `opponent_fire_power`, `opponent_velocity`,
`opponent_heading`, `bearing_*`). Implementation cost: one new
`WaveOfflineFeatures` class plus the GF computation in an existing or new
`TargetingOfflineFeatures`.

Tier 2 follows once the wave-event timeline is exposed in `Whiteboard` (i.e.
once we know which historical fire-event is currently in flight at each tick,
so we can compute "where the opponent was when our wave hit them").

Until Tier 1 lands, notebook 5 is limited to predicting opponent `(x, y)` at
`t + k` — a regression problem strictly weaker than the GF formulation used
across the Robocode literature.

## Cross-cutting fix

Independent of new features: investigate the **`win_rate` always-zero bug** in
the scores writer before notebook 7 is meaningful. Likely the round-end
perspective is being recorded after the perspective bot has already died, so
its computed win rate against itself collapses to zero. Quick fix; high impact
on Q3.

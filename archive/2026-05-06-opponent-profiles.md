# Opponent Profiling Research — Archetype Clustering & Fingerprint Analysis

*2026-05-06 — Research document for opponent profile system design.*

## 1. Goal

Design the robot's opponent profiling system: given an opponent name (or
behavioral observations), what information should the strategy layer use
to adapt its play?

Three candidate approaches were evaluated:
- **A.** Named archetype enum (ELITE_SURFER, STOP_AND_GO, etc.) assigned by clustering
- **B.** ML fingerprint classifier (LightGBM, 51.6% top-1 over 50 classes)
- **C.** Per-bot hash lookup (strength rating + position heatmap)

## 2. Archetype Clustering (Approach A) — Failed

### Setup
K-Means on 8 tick-level behavioral features (lateral velocity std, heading
smoothness, direction-change interval, deceleration fraction, fire rate,
wall proximity, distance mean, velocity mean). Evaluated K=3..9.

### Results
- Best: K=7, silhouette=0.222 (low — clusters overlap heavily)
- BeepBoop (wave surfer) and ScalarR (stop-and-go) in the **same cluster** (4)
- Diamond (elite surfer) in cluster 3 with 13 mid-tier orbiters
- Two giant "everything else" buckets (13 and 14 bots)

### Why it failed
Mean-level aggregation destroys behavioral signatures. A wave surfer's mean
lateral velocity ≈ 0 (oscillates symmetrically). An orbital mover's mean
lateral velocity ≈ 0 (steady direction, but also oscillates over longer
periods). The *pattern* differs but the *mean* is identical.

Better approaches that weren't tried: per-round profiles, wave-level
fingerprint features (as in nb10/nb14), or semi-supervised labeling.

## 3. Fingerprint Classifier (Approach B) — Partially Works

### Performance
- 51.6% top-1 accuracy over 50 classes (26× random baseline of 2%)
- Needs 20 fires + tick-derived features (heading delta, direction changes)
- Wave-only model peaks at 34.9% — never crosses 50%

### Per-bot identification
| Bot | F1 | Identifiable? |
|---|---|---|
| BeepBoop | 0.800 | ✅ Reliably detected |
| DrussGT | 0.500 | Coin flip |
| Foilist | 1.000 | ✅ (but tiny support) |
| 10+ bots | 0.000 | ❌ Completely unidentifiable |

### Key features (from nb14)
1. `mean_dist` (preferred engagement distance) — #1 fingerprint
2. `std_dist` — distance variability
3. `tick_heading_delta_std` — movement smoothness
4. `tick_direction_changes` — oscillation frequency
5. `mean_power` — fire power preference

### Limitation
Wrong 48% of the time. Bots that fight at similar distances with similar
smoothness are indistinguishable. Most mid-tier bots look identical.

## 4. Why Named Archetypes Aren't Actionable

The proposed archetypes implied counter-strategies:
- ELITE_SURFER → anti-surfer gun
- STOP_AND_GO → linear gun targeting stopped robot
- WALL_HUGGER → wall-projected targeting

Problems:
1. **We don't have those specialized guns.** Only head-on, linear, circular,
   random-GF. The virtual gun manager already picks the best one empirically.
2. **VGM adapts without labels.** Within 20 fires, the VGM discovers which
   gun works best against this specific opponent — regardless of archetype.
3. **48% misclassification risk.** The wrong initial strategy costs more
   than the default balanced strategy for the first 20 fires.

## 5. What IS Actionable (Approach C) — Adopted

### Strength rating (hash lookup)
- Directly controls fire power budget from tick 0
- Against BeepBoop (0.966): fire conservatively (power 1.0)
- Against weak bots (0.200): fire at power 3.0
- Zero classification error for known bots (exact hash match)
- Unknown bots default to 0.5

### Position advantage heatmap
- Quarter-field, 20px grid, 20×15 = 300 cells per bot
- Measured from actual battle outcomes in our data
- Feeds both IPositionDanger (path planning) and StrategyComputer
- Per-bot heatmaps: "where should I stand against THIS specific opponent?"

### Identity hashes for ML
- `OPPONENT_BOT_ID_HASH` (FNV-1a of family name) already in Feature enum
- ML models can learn per-bot behavioral patterns directly from the hash
- No archetype abstraction layer needed

## 6. Decision

**Drop the archetype enum entirely.** Keep:
- `OPPONENT_STRENGTH_RATING` — hash lookup, fire power budget
- `OUR_POSITION_ADVANTAGE` — per-opponent heatmap, movement decisions
- Identity hashes — ML feature for per-bot learning

The archetype concept can be revisited when we have specialized tactical
tools (anti-surfer gun, flattened GF profiles, wall-projected targeting)
that would actually benefit from opponent type classification.

## 7. What We Know vs What We Assume

### Measured in our project (high confidence)
| Bot | Finding | Notebook |
|---|---|---|
| BeepBoop | 99.5% WR, smooth movement, minimal adaptation (KS=0.016) | nb05, nb06, nb09 |
| ScalarR | 97.6% WR, shortest direction-change interval (15.8 ticks) | nb05, nb06 |
| DrussGT | 91.2% WR, largest adaptation (KS=0.172) | nb05, nb09 |
| Diamond | 88.8% WR, very stable (KS=0.011) | nb05, nb09 |
| CassiusClay | Highest lat_vel_std (6.85), most periodic | nb05 |
| Domogled | Closest to walls (51px) | nb05 |

### Assumed from community knowledge (unverified by us)
- DrussGT uses "dynamic clustering" gun — we see adaptation but can't confirm the algorithm
- Shadow uses "pattern matching" — we see gradual adaptation but can't confirm
- Dookious is a "wave surfer" — our clustering puts it with generic mid-tier bots

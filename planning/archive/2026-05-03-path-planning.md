# Path Planning — Reachable Envelopes & Distance Dimension

*2026-05-03 — Research document. Will be refined through discussion.*

## Problem Statement

Our current ML predictors operate almost entirely in **GuessFactor (angle)
space** — lateral velocity, GF bins, bearing offsets. They answer "at what
angle will the opponent be?" but not "where on the battlefield should I
stand to dodge best?"

Movement planning needs both dimensions:
1. **Angle** — which GF position to occupy relative to incoming waves
2. **Distance / position** — absolute (x, y) placement considering walls,
   corners, distance-to-enemy, and multi-wave interactions

This document explores pre-computed reachable envelopes as the bridge
between per-tick movement commands and multi-tick trajectory planning.

---

## 1. Robocode Movement Physics (Exact Rules)

| Property | Value |
|---|---|
| Max velocity | 8 px/tick |
| Acceleration | +1 px/tick² |
| Deceleration | −2 px/tick² (forced if `setAhead` reverses) |
| Body turn rate | `10 − 0.75 × |velocity|` degrees/tick |
| At velocity 0 | 10°/tick (0.175 rad) |
| At velocity 4 | 7°/tick (0.122 rad) |
| At velocity 8 | 4°/tick (0.070 rad) |
| Robot bounding box | 36×36 px (18 px half-size) |
| Wall collision | Robot stops, velocity → 0, no energy loss |

### Per-tick state transition

Given state $(x, y, \theta, v)$ and control inputs $(a, \omega)$:

1. $\omega' = \text{clamp}(\omega,\; -\text{maxTurn}(v),\; +\text{maxTurn}(v))$
2. $\theta' = \theta + \omega'$
3. $v' = \text{clamp}(v + a,\; -8,\; 8)$ where $a \in \{-2, -1, 0, +1\}$
4. $x' = x + v' \sin(\theta')$
5. $y' = y + v' \cos(\theta')$
6. If $(x', y')$ violates wall bounds: clamp to bounds, $v' = 0$

The deceleration asymmetry ($-2$ vs $+1$) means the robot can stop much
faster than it accelerates. This shapes the reachable envelope: backward
reach expands faster than forward reach.

---

## 2. Reachable Envelope Concept

### Definition

The **reachable envelope** at horizon $t$ from initial state
$(x_0, y_0, \theta_0, v_0)$ is the set of all positions $(x_t, y_t)$
achievable by some sequence of valid control inputs over $t$ ticks.

In open space (no walls), this envelope depends only on $|v_0|$ and $t$.
The shape is a heading-relative arc that widens with $t$:

- **$t = 1$**: Thin crescent. 3 velocity outcomes × narrow turn arc.
- **$t = 5$**: Moderate arc. Velocity can range from $\max(-8, v-10)$ to
  $\min(8, v+5)$. Several distinct heading angles reachable.
- **$t = 10$**: Wide region. Nearly full velocity range reachable regardless
  of starting speed. Multiple full direction reversals possible.
- **$t = 20+$**: Very wide — almost any position within a large circle is
  reachable. The envelope becomes less useful as a constraint.

### Why pre-compute

At runtime, generating the full tree of reachable states is expensive:
each tick branches into ~20 choices (turn angles at some step × 3 accel
options). Over $t = 10$ ticks that's $20^{10} \approx 10^{13}$ paths.

Instead, **pre-compute representative boundary points** of the envelope
for each $(|v_0|, t)$ pair offline. At runtime:

1. Look up the envelope for current $|v_0|$ and desired horizon $t$.
2. Rotate by current heading $\theta_0$.
3. Translate to current position $(x_0, y_0)$.
4. Clamp candidates to wall bounds. Discard unreachable ones.
5. Sample ~20–30 candidates from the surviving envelope.
6. Score each by danger (§4).

This reduces candidate generation from $O(\text{exponential})$ to
$O(\text{table\_size} \times \text{wall\_check})$ per tick.

### Pre-computation parameters

*Updated 2026-05-03 with exact simulation results from `_run_envelope.py`.*

The reachable tree was exhaustively explored with exact Robocode physics
(3 accel × 5 turn options = bf=15 per tick). Key findings:

**Reachable positions (2px grid) by horizon:**

| Horizon | v=0 positions | v=8 positions | Max distance | Full states |
|---|---|---|---|---|
| 5 | 192 | 120 | 28–40 px | 11k–20k |
| 8 | 990 | 599 | 52–64 px | 190k–295k |
| 10 | 1,465 | 1,384 | 60–80 px | 577k–706k |

**Coarse time steps (decisions every N ticks, horizon=10):**

| Step size | Decisions | States | Positions | Feasible? |
|---|---|---|---|---|
| 1 tick | 10 | 706,027 | >1,384 | No (bf^10) |
| 2 ticks | 5 | 81,222 | 1,113 | **Yes** |
| 3 ticks | 3 | 1,614 | 292 | Very fast |
| 5 ticks | 2 | 125 | 77 | Trivial |

**Trajectory prefix sharing (among top-20 endpoints, bf=9):**

| Prefix (ticks) | Distinct prefixes | Sharing ratio |
|---|---|---|
| 1 | 6–7 | 143–167× |
| 2 | 21–32 | 31–48× |
| 3 | 66–98 | 10–15× |

At tick 1, only **6–7 distinct first moves** lead to all top-20
destinations. The first 1–2 ticks are "no-regret" — most options remain
open. Commitment to a specific destination happens at tick 3+.

**Endpoint fill ratio (5px grid, % of bounding box that is reachable):**

| | t=5 | t=10 |
|---|---|---|
| v=0 | 100% | 89% |
| v=4 | 105%* | 90% |
| v=8 | 114%* | 86% |

\* >100% because the reachable set is not rectangular — it extends beyond
the axis-aligned bounding box at some velocities.

**Grid density for candidate generation:**

| Grid size | Candidates at t=10 | Use case |
|---|---|---|
| 5 px | ~280 | Full coverage |
| 10 px | ~70 | **Sweet spot** |
| 20 px | ~17 | Phase 1 coarse |

### Recommended online approach

Based on the simulation results, the recommended architecture is:

1. **Plan with decisions every 2–3 ticks** (not every tick). At step=2
   with 10-tick horizon, there are only 81k states — feasible for full
   evaluation. At step=3, only 1,614 states.

2. **Score ~20–70 endpoint candidates** on a 10–20px grid. The endpoint
   fill ratio is ~90%, so grid sampling covers the reachable set well.
   Score each by `positionDanger + waveDanger`.

3. **Pick the first-tick action from the winning cluster**. The top
   destinations share first-move prefixes (6–7 options at tick 1).
   Choose the first action that leads toward the largest cluster of
   low-danger endpoints. Re-plan every tick.

4. **Full precise-prediction only for the chosen destination**. Once
   the best endpoint is selected, simulate the exact trajectory to verify
   reachability and compute the wave intercept point.

This is a hybrid of DrussGT's GoTo Surfing (endpoint-based, trajectory-
simulated) with pre-computed envelopes (fast candidate generation) and
coarse-step planning (manageable state space).

### Wall clamping

The pre-computed envelope assumes infinite open space. Walls truncate it:

- If a candidate point is outside the field (minus 18px margin), discard it.
- If the *path* to a candidate requires passing through a wall zone, the
  candidate is not truly reachable even if the endpoint is inside. This is
  the hard case.

**Approach A (simple)**: Just discard candidates outside walls. Accept that
some remaining candidates may not be reachable via the assumed path. The
scoring function will penalize wall-adjacent positions anyway.

**Approach B (precise)**: For each candidate, simulate the shortest path
using `PrecisePredictor` and verify reachability. Expensive but exact.
Only needed for the ~5 candidates near walls.

**Approach C (DrussGT-style)**: Don't pre-compute the envelope. Instead,
do full precise-prediction from current state: simulate forward movement
(clockwise and counter-clockwise), collecting points along the path.
Filter to ensure minimum spacing (7px). This is what DrussGT does and it
naturally handles walls because the simulation includes wall collision.

**Recommendation**: Start with Approach A for Phase 1 (simplicity), plan
for Approach C when wave surfing is implemented (Phase 2+).

---

## 3. Competitive Prior Art

### 3a. True Surfing (Diamond, BasicSurfer)

Each tick, evaluate 3 movement options: {forward, stop, reverse}. For each,
Precise-Predict where the robot will be when the incoming wave arrives.
Score each option by the wave's danger at that GF. Pick the safest.

- **Candidate generation**: Implicit — only 3 options.
- **Horizon**: Until wave intercepts (10–40 ticks), but decision is 1 tick.
- **Distance dimension**: No explicit distance planning. The robot's
  position is a consequence of the angle-based dodge, not a goal.
- **Multi-wave**: Sometimes evaluates 2 waves (current + next).

**Pros**: Simple, fast, debuggable. Widely used.
**Cons**: Locally optimal but globally suboptimal. Can't plan "go to
position X, arriving in 15 ticks." The forward/stop/reverse choice is
re-evaluated every tick, which can oscillate.

### 3b. GoTo Surfing (DrussGT — #1 bot)

Generate ~20 destination points via Precise Prediction (simulate forward
movement, collecting positions along the path). For each destination,
simulate the full trajectory (including turns and acceleration) and
compute where the incoming wave intersects the path. Score by wave danger
at the intersection GF + heuristics (distance-to-enemy, wall distance).

- **Candidate generation**: Precise-prediction from current state along
  two arcs (clockwise and counter-clockwise), pruned to 7px spacing.
- **Horizon**: Full trajectory to destination. Plans the complete path.
- **Distance dimension**: Partially — destination distance is controlled
  by the "distancing angles" in point generation, and danger evaluation
  includes distance-to-enemy scaling.
- **Multi-wave**: Yes — evaluates against 2 incoming waves. Uses aggressive
  pruning: sort by wave-1 danger, early-exit when wave-1 danger > best
  total. For wave-2, first estimate ~20 coarse GF values to get a lower
  bound before running full precise prediction.

**Pros**: Globally better than True Surfing — evaluates whole trajectories.
Multi-wave planning. The state-of-the-art architecture.
**Cons**: $O(n^2)$ for 2-wave (each destination × each wave). Complex
implementation. Point generation is coupled to the trajectory simulation.

### 3c. Minimum Risk Movement (HawkOnFire, melee bots)

Generate candidate points (orbital angles at varying radii, grid points).
Score each by hand-tuned heuristics: distance to enemies, wall distance,
corner proximity, angular spread between threats. Move toward the lowest-
risk point.

- **Candidate generation**: Fixed geometric patterns (angular offsets
  around current position at 2–3 radii).
- **Horizon**: None — evaluates the destination, not the path.
- **Distance dimension**: Yes — explicitly scores position quality.
- **Multi-wave**: No (designed for melee, not wave-specific).

**Pros**: Natural fit for position danger. Handles multi-enemy naturally.
**Cons**: Ignores trajectory — you might be hit en route to a "safe" spot.
Not competitive in 1v1 because it doesn't account for wave timing.

### 3d. What's new in the reachable-envelope approach

Our proposal combines elements:

| Concept | Source | Our addition |
|---|---|---|
| Pre-computed envelope | Novel (bots compute online) | Offline table, O(1) lookup |
| Position danger scoring | Minimum Risk Movement | ML-trained from battle data |
| Wave danger scoring | True/GoTo Surfing | Same — GF profile at intercept |
| Distance dimension | Partial in DrussGT | Explicit axis in movement planning |

The key novelty is **decoupling candidate generation (envelope lookup) from
trajectory simulation**. DrussGT tightly couples them — it generates
candidates by simulating forward. We separate: (1) look up what's
geometrically reachable, (2) wall-clamp, (3) score by danger. This
enables swapping the danger function (hand-tuned → ML) without touching
the candidate generation.

---

## 4. Danger Scoring — Two Independent Dimensions

### 4a. Position danger (static)

Danger of being at absolute position $(x, y)$ regardless of incoming
waves. Captures battlefield geometry that doesn't change tick-to-tick.

**Hand-tuned version (Phase 1):**
```
danger(x, y) = w₁ · wallProximity(x, y)
             + w₂ · cornerProximity(x, y)
             + w₃ · |distToEnemy(x, y) − preferredDistance|
             + w₄ · centerBias(x, y)
```

- `wallProximity`: high near walls (less room to dodge next wave)
- `cornerProximity`: high near corners (trapped in two dimensions)
- `distToEnemy`: penalize deviations from strategic preferred distance
- `centerBias`: mild preference for central positions (more options)

**ML-trained version (Phase 4):**
Train on `ticks.csv` columns (`our_dist_to_wall_min`,
`opponent_center_distance`, `opponent_corner_proximity`, `distance_norm`)
crossed with per-round damage outcomes from `scores.csv`. The model learns
which positions correlate with taking less damage across 1944 battles.

### 4b. Wave danger (dynamic)

Danger of being at position $(x, y)$ at the moment a specific incoming
wave arrives. This is the GF-based scoring from classical wave surfing.

Given a candidate position and an incoming wave:
1. Compute the GF at which the wave would hit the candidate.
2. Look up the opponent's historical fire-frequency at that GF.
3. Scale by bullet damage and distance weighting.

**Phase 1**: Uniform (all GFs equally dangerous — no wave intelligence).
**Phase 2**: VCS bin lookup from opponent targeting stats accumulated
in-battle.
**Phase 3**: ML-predicted GF distribution from the MLP targeting model
(used in reverse — the model predicts where the opponent aims, so high
probability GFs are dangerous).

### 4c. Combined scoring

```
totalDanger(candidate) = α · positionDanger(x, y)
                       + Σ waveDanger(candidate, wave_i) × damage(wave_i)
                         / Σ damage(wave_j)
```

Each wave's contribution is weighted by `damage(power)`: a power-3.0
bullet (16 damage) dominates over a power-0.1 bullet (0.4 damage) by
40:1. This makes the surfer immune to wave-stacking distractions from
cheap bullets — it always optimizes for the most lethal incoming wave.

Weight $\alpha$ depends on wave proximity:
- No waves in air → pure position danger.
- Wave close → wave danger dominates.
- Multiple waves → damage-weighted sum.

---

## 5. Planning Horizon — Signal Decay Analysis

From our ML results (nb05, nb11, train_sequence.py):

| Horizon | Movement autocorrelation | GBM-window R² | Usable? |
|---|---|---|---|
| +1 tick | 0.984 | ~0.95 | Near-deterministic |
| +5 ticks | 0.785 | 0.735 | Strong — sweet spot |
| +10 ticks | 0.457 | 0.363 | Moderate |
| +15 ticks | ~0.15 | ~0.10 | Marginal |
| +20 ticks | ~0.05 | 0.050 | Noise |

Bullet flight times at typical engagement distances:

| Distance | Power 1 (speed 17) | Power 2 (speed 14) | Power 3 (speed 11) |
|---|---|---|---|
| 200 px | 12 ticks | 14 ticks | 18 ticks |
| 350 px | 21 ticks | 25 ticks | 32 ticks |
| 500 px | 29 ticks | 36 ticks | 45 ticks |

### The asymmetry

- **Our movement**: No prediction problem — we *choose* where to go. The
  reachable envelope tells us all options. Longer horizons = more options
  = wider envelope. Planning 20+ ticks ahead is geometrically useful even
  though opponent prediction fails at that range.
- **Opponent movement**: Prediction-dependent. Signal dies at ~10 ticks.
  This is why GF targeting at long range is inherently harder.
- **Opponent wave dodge**: The opponent bullet is already in flight. Its
  position is deterministic (constant velocity). The only uncertainty is
  whether it exists (fire detection) and its exact trajectory (which GF
  it's aimed at).

### Recommended planning horizons

| Situation | Horizon | Rationale |
|---|---|---|
| **Close range, wave in air** | 5–10 ticks | Wave arrives soon, need tight dodge. Envelope is narrow at high velocity — few options, must be precise. |
| **Mid range, wave in air** | 10–20 ticks | More time, wider envelope. Plan a destination point, not just next-tick action. |
| **Far range, wave in air** | 20–30 ticks | Envelope is nearly unconstrained. Position danger and multi-wave considerations dominate over single-wave GF. |
| **No wave in air** | 5–10 ticks | Pure position-danger + distancing. Keep moving to maintain options. |
| **Gun-heat wave** (DrussGT trick) | Bullet flight + 2 ticks | Start planning before wave is detected — gains crucial reaction time at close range. |

The sweet spot for our architecture is **10 ticks** as the primary
planning horizon: it aligns with the signal decay boundary, gives a
meaningful reachable envelope, and covers most mid-range bullet flight
times.

---

## 6. Cost Model & Candidate Pruning

*All costs are napkin estimates for Java 8 on a single core (~3 GHz).*

### 6a. Per-candidate operation costs

| Operation | Cost per candidate | 280 candidates | 20 candidates |
|---|---|---|---|
| Precise prediction (10 steps) | ~0.5 μs | 0.14 ms | 0.01 ms |
| VCS bin lookup × 2 waves | ~0.2 μs | 0.06 ms | 0.004 ms |
| MLP wave danger (18→128²→64→61) × 2 waves | ~60 μs | **16.8 ms** | **1.2 ms** |
| ML position danger (small MLP) | ~5 μs | 1.4 ms | 0.1 ms |
| **Total with VCS danger (Phase 3)** | | **0.2 ms** | 0.01 ms |
| **Total with MLP danger (Phase 4+)** | | **18.3 ms** | **1.3 ms** |

The **MLP danger scorer is the bottleneck**: 31k multiply-adds per wave ×
2 waves × 280 candidates = 17.4M ops. Pure Java without BLAS runs at
~1 GFLOP/s → ~17 ms. This exceeds the 10 ms tick budget.

**Conclusion**: VCS danger (Phase 3) can score all 280 candidates — no
pruner needed. MLP danger (Phase 4+) requires a pruner to reduce from
280 to ~20 candidates.

### 6b. The Pruner — fast candidate pre-selection

```java
public interface ICandidatePruner {
    /** Score candidates cheaply. Return top-K indices. */
    int[] prune(CandidatePosition[] candidates, int k, Whiteboard wb);
}

// Phase 3: no pruning — VCS is cheap enough
public final class KeepAllPruner implements ICandidatePruner {
    public int[] prune(CandidatePosition[] c, int k, Whiteboard wb) {
        int[] all = new int[c.length];
        for (int i = 0; i < c.length; i++) all[i] = i;
        return all;
    }
}

// Phase 4: distilled MLP pruner
public final class MlpCandidatePruner implements ICandidatePruner {
    private final float[] weights; // distilled small MLP
    public int[] prune(CandidatePosition[] c, int k, Whiteboard wb) {
        // score each candidate with small MLP, return top-k indices
    }
}
```

**Pruner architecture**: Small MLP [12 → 32 → 1, sigmoid].
~420 parameters, ~0.7 μs per candidate, 0.2 ms for 280 candidates.

**Pruner input features** (12 dimensions, cheap to compute):
1. Candidate `(dx, dy)` relative to current position — 2
2. Current velocity, heading — 2
3. Distance to opponent from candidate — 1
4. Nearest wall distance from candidate (lookup, not simulation) — 1
5. Wave 1 angle + radius relative to candidate — 2
6. Wave 2 angle + radius relative to candidate — 2
7. Strategic axes: aggression, preferred range — 2

**Pruner metric**: Recall@K — fraction of the true top-K (from full
scorer) preserved in the pruner's selection. Target: recall@20 ≥ 80%.

### 6c. Dual-horizon planning

The pruner enables splitting the tick budget across two planning horizons:

| Horizon | Purpose | Candidates | Scoring | Cost |
|---|---|---|---|---|
| **t+10** | Dodge current wave(s) | 10 (pruned from ~280) | MLP wave danger + position danger | 1.2 ms |
| **t+20** | Post-dodge positioning | 10 (pruned from ~150) | Position danger only (no wave — too uncertain) | 0.15 ms |
| | | **Total** | | **~1.7 ms** |

**t+10 (tactical)**: Where do I dodge THIS wave? Scored by full MLP wave
danger at the intercept GF, damage-weighted across all active waves,
plus position danger.

**t+20 (strategic)**: After dodging, where do I want to BE? Scored by
position danger only — walls, corners, distance to enemy, future
dodgeability. No wave danger because waves at t+20 haven't been fired
yet.

The dual horizon captures what DrussGT's 2-wave surfing does
(wave 1 → t+10, wave 2 → t+20), but generalizes it: t+20 uses position
danger even when no second wave exists.

**Combined scoring**:
```
totalDanger(candidate_10, candidate_20)
    = α · waveDanger(candidate_10, waves)
    + β · positionDanger(candidate_10)
    + γ · positionDanger(candidate_20)
    + δ · pathContinuity(candidate_10, candidate_20)
```

`pathContinuity` penalizes t+20 destinations unreachable from t+10. This
comes free from the reachable envelope: given the t+10 endpoint as a new
start state, look up what's reachable at +10 more ticks.

### 6d. Full tick budget breakdown

| Phase | Operation | Cost |
|---|---|---|
| Features | Transformer.process() — all 63 features | ~0.1 ms |
| Predictors | Scalar predictors (fire power, fire timing, etc.) | ~0.2 ms |
| **Gun** | Virtual gun angles × 4 strategies + virtual bullet tracking | ~0.5 ms |
| **Movement** | | |
| | Generate ~280 candidates (envelope lookup + rotate + wall-clamp) | 0.1 ms |
| | Prune to 10+10 (pruner MLP) | 0.2 ms |
| | Precise-predict 20 trajectories (10 to t+10, 10 to t+20) | 0.03 ms |
| | Score 10 candidates × MLP wave danger × 2 waves | 1.2 ms |
| | Score 20 candidates × position danger | 0.1 ms |
| | Select first-tick action from best cluster | 0.01 ms |
| Radar | Narrow lock computation | 0.01 ms |
| **Total** | | **~2.4 ms** |
| **Budget** | Robocode gives ~10 ms per tick | **7.6 ms spare** |

---

## 7. Training the Pruner

### 7a. Should the pruner be trained end-to-end?

**No — distillation is the right approach.** Here's why:

End-to-end training would optimize: "given the pruner's top-K, the best
candidate scored by the full danger function should be as good as the best
among all 280." This requires scoring all 280 during training anyway
(to compute the loss), so there's no training-time benefit. And the top-K
selection (argmax over scores) is non-differentiable — you'd need
REINFORCE or Gumbel-Softmax, both finicky.

**Distillation** is simpler and sufficient:

1. **Train the full danger scorer first** (MLP wave danger + position
   danger — already done in Step 3).
2. **Generate labels**: For many game states from `ticks.csv`, generate all
   280 candidates, score each with the full danger function (offline, no
   time limit), label the top-20 as positive.
3. **Train the pruner** to predict these labels. Standard binary
   classification with `pos_weight ≈ 280/20 = 14` to handle class
   imbalance.
4. **Evaluate by recall@20**: What fraction of the true top-20 does the
   pruner keep? Target ≥ 80% (16 of 20 true best captured).

**Why this works**: The pruner doesn't need to reproduce exact danger
scores — it just needs to *rank* candidates well enough to keep most of
the true top-20. This is a much easier learning task than regression on
the full danger value.

**Training data**: ~100k game states × 280 candidates = 28M examples.
Each labeled 1/0 by whether it's in the full scorer's top-20. The pruner
sees 12 cheap features per candidate + shared game-state context. This is
a standard learning-to-rank problem solvable with a few minutes of CPU
training.

### 7b. When to retrain

The pruner depends on the danger scorer's ranking. If the danger scorer
is retrained (e.g., MLP wave danger updated with more battle data), the
pruner should be retrained too — but this is cheap (a few minutes on the
28M labeled dataset).

The pipeline:
```
battle recordings → pipeline CSVs → train danger scorer → label candidates → train pruner
                                  → train MLP targeting
                                  → train movement GBM
```

All models are trained from the same CSV data. The pruner is the only one
that depends on another model's output (the danger scorer), making it the
last step in the pipeline.

---

## 8. Offline Learning Advantages

The general principle: **any signal that requires more data to learn than
a single battle provides is a competitive edge for offline models.** Our
robot ships with trained models that work from tick 1 — no learning phase.

### 8a. Signals only offline training can capture

| Signal | Data needed | Available per battle | Why online can't learn it |
|---|---|---|---|
| Multi-wave GF narrowing (nb13) | 100k+ waves | ~200 waves | Need to segment by wave pressure — 5 sub-bins × 61 GF bins = 305 cells, ~0.7 events per cell online |
| Per-family GF priors | 50k+ waves per family | 200 waves vs this opponent | The first 10 rounds have zero data; offline prior is the model |
| Position danger heatmap | 1M+ ticks | ~3000 ticks | Need to cross (x, y) grid × distance × wall geometry → damage; 1000+ cells, <3 events per cell online |
| Movement prediction (R²=0.74) | 50k+ windows | ~2500 windows | Windowed-GBM needs 10k+ samples to reach 0.735; online reaches maybe 0.3 |
| Fire power prediction (R²=0.57) | 10k+ fire events | ~200 fire events | Same sparsity problem |
| Candidate pruner | 28M labels | 0 labels (no full scorer online) | The pruner is a distillation — can't distill what doesn't exist |

### 8b. Bayesian prior + online update

The most powerful pattern is **offline prior + online Bayesian update**.
The offline model provides a strong prior that works from tick 1. As the
battle progresses, online observations update the prior toward this
specific opponent.

**Example — GF targeting**:
- Tick 1: Use the offline MLP's 61-bin GF distribution as the prior.
  This already captures "under wave pressure, opponents cluster near GF=0"
  and "at this distance, opponents favor GF ±0.3."
- After 10 waves observed: blend the prior with the online VCS histogram
  using a mixing weight $\lambda$ that decays from 1.0 → 0.0 as online
  data accumulates. The formula:
  
  $P(\text{GF}) = \lambda \cdot P_{\text{MLP}}(\text{GF} \mid \text{state}) + (1-\lambda) \cdot P_{\text{VCS}}(\text{GF} \mid \text{history})$
  
  $\lambda = \frac{K}{K + n_{\text{observed}}}$ where $K \approx 20$ (strength of prior).

- After 50 waves: $\lambda = 20/70 = 0.29$. The online VCS dominates, but
  the prior still regularizes sparse bins.

This gives the best of both worlds: offline model's generalization in
early rounds, online model's specificity in late rounds.

**Applies to**: GF targeting, position danger, fire power prediction,
opponent movement prediction. Each can blend offline prior + online update.

### 8c. Opponent family priors (fingerprint → lookup)

The fingerprint classifier (nb10, 35% top-1 over 44 classes) identifies
which bot family we're fighting. This unlocks **family-specific priors**:

- **GF distribution per family**: Pre-computed from all waves against that
  family. Loaded when the classifier reaches confidence > 0.5 (typically
  after 10–20 fires, ~5 rounds).
- **Preferred distance per family**: Some families fight better at close
  range, others at long range. Pre-computed from round outcomes × distance.
- **Adaptation rate per family**: The KS adaptation detector (nb09) has
  different thresholds per family. Pre-calibrated offline.
- **Wave-stacking vulnerability per family**: Pre-computed from nb12.
  If the family uses equal-weighted surfing, activate the `WaveStackPlan`.

**Data structure**: A `FamilyProfile` loaded from a resource file:
```java
public final class FamilyProfile {
    public final double[] gfPrior;        // 61-bin distribution
    public final double preferredDistance; // optimal engagement range
    public final double adaptationThreshold; // KS trigger
    public final boolean waveStackVulnerable;
}
```

~500 bytes per family × 44 families = 22 KB. Trivial.

### 8d. Strategic axes as shared context

The 4 strategic axes (aggression, range, counter-strategy, phase) are
slow-moving dimensions that should feed into **every predictor** as a
context vector:

| Predictor | How axes are used |
|---|---|
| MLP GF targeting | Aggression adjusts which part of the GF distribution to aim at (risky peak vs safe spread) |
| Position danger | Range axis sets the preferred distance component |
| Candidate pruner | Aggression + range bias candidate selection toward offensive/defensive positions |
| Fire plan | Aggression → power budget; range → wave-stacking distance threshold |
| Movement scorer | Aggression weights offensive positioning (closer, better gun angles) vs defensive (farther, more dodge room) |

Since axes change at most once per round (~every 100 ticks), their values
can be precomputed and cached. Every predictor receives them as 4 extra
input features at zero marginal cost.

**Training implication**: All offline models should include the 4 strategic
axes as input features. During training, the axes are computed from
`scores.csv` (win probability) and `ticks.csv` (energy ratio, distance)
aggregated per round. The models learn conditional behavior: "when
aggressive, favor GF ±0.6; when defensive, aim at GF 0."

---

## 9. Implementation Phases (revised)

### Phase 0 (robot architecture prototype — Step 4)
- Define `RobotPhysics`, `RobotState`, `CandidatePosition` in core.
- Define `IPositionDanger`, `IWaveDanger`, `ICandidatePruner` interfaces.
- `ICandidatePruner` defaults to `KeepAllPruner`.
- Movement strategies from Phase 1 don't use path planning.
- Strategic axes computed by `StrategyComputer`, available on `Whiteboard`.

### Phase 1: Offline envelope pre-computation
- Exhaustive reachable tree exploration (already done in `_run_envelope.py`).
- Store as Java resource: `double[9][8][~100][2]` per (velocity, horizon).
- Validate by plotting envelopes.

### Phase 2: Runtime candidates + hand-tuned danger (VCS)
- `ReachableEnvelope.getCandidates()` → ~280 candidates at 5px grid.
- `HandTunedPositionDanger` (wall/corner/distance heuristics).
- `VcsWaveDanger` scores all 280 candidates (0.2 ms total — no pruner).
- Single-horizon: t+10 only. `WaveSurfStrategy` uses `PathPlanner`.

### Phase 3: MLP danger + pruner + dual horizon
- Train MLP wave danger (already in Step 3, with multi-wave features).
- Generate pruner labels: score 280 candidates with full MLP on 100k states.
- Train `MlpCandidatePruner` [12→32→1] for recall@20 ≥ 80%.
- Dual horizon: 10 candidates at t+10 (wave+position), 10 at t+20 (position).
- Total movement cost: ~1.7 ms.

### Phase 4: Bayesian prior + online update
- Blend offline MLP prior with online VCS histogram using $K/(K+n)$ mixing.
- Family-specific priors loaded from fingerprint classifier results.
- Strategic axes as shared context across all predictors.

### Phase 5: Position danger from ML
- Train position danger model on ticks.csv × scores.csv.
- Distill to Java. Replace hand-tuned version.
- Retrain pruner with updated scorer.

---

## 10. Open Questions (updated)

1. **Position danger signal** (unchanged): Does absolute position (x, y)
   actually predict damage taken, or is it entirely mediated by GF and
   wave timing? Needs a dedicated notebook.

2. **Pruner recall vs candidate count**: Is recall@20 ≥ 80% achievable
   with a 420-parameter MLP? May need more features or a larger model.
   Test empirically once the danger scorer is trained.

3. **Bayesian mixing schedule**: The $\lambda = K/(K+n)$ formula is
   standard but $K$ needs tuning. Too high → slow adaptation to this
   specific opponent. Too low → prior is useless after round 2.

4. **Strategic axes as training features**: The 4 axes are derived from
   round-level aggregates. During training, do we compute them from
   ground-truth scores.csv, or simulate them from the per-tick features
   the robot would actually have? The former leaks outcomes; the latter
   is noisier but more honest.

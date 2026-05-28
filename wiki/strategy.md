# Competitive Strategy — Known Ideas & Top-Bot Analysis

*Summary of competitive concepts, how top bots implement them, and ML prior art.
See also: [targeting.md](targeting.md), [movement.md](movement.md), [terminology.md](terminology.md), [physics.md](physics.md).*

## Core Concepts

### Waves

A "wave" is the expanding circle from a bullet's origin. When we detect
an opponent fire (energy drop), we create a wave at their position with
`radius(t) = bulletSpeed × (t − fireTick)`. The wave "breaks" when it
reaches our position.

### GuessFactor (GF)

GF maps the angular offset from head-on to a [-1, +1] scale, where
±1 = Maximum Escape Angle (MEA). Each opponent fire is recorded as a
GF observation. The GF at wave-break tells us where the opponent aimed.

**GF = offset_angle / MEA × lateral_direction**

### Visit Count Statistics (VCS)

Online histogram of GF values observed from an opponent. Index = GF bin (0–60),
value = smoothed count. Fire at the peak bin. Simple, effective,
adaptive — the foundation of most competitive guns.

### Wave Surfing

Movement strategy: for each incoming wave, compute the danger at each
reachable GF position, then move to minimize danger. The danger function
uses the inverse of the opponent's GF histogram (where they've aimed
before, they'll aim again).

### Precise Prediction

Tick-by-tick forward simulation of our robot's movement to determine
exactly which positions are reachable before a wave breaks. Used by
Diamond and DrussGT for wave surfing.

---

## Targeting Strategies

| Strategy | Description | Effective against |
|---|---|---|
| **Head-on** | Aim directly at opponent | Stationary bots |
| **Linear** | Assume constant velocity + heading | Simple movers |
| **Circular** | Assume constant turn rate | Orbital movers |
| **GF targeting** | Fire at peak of VCS histogram | Patterned movers |
| **Pattern matching** | Match current movement to history | All, if enough data |
| **Dynamic clustering** | Segment by game state, per-segment VCS | Complex movers |

### GuessFactor Targeting (Detail)

1. On each scan: compute the GF bin for each possible fire angle
2. On fire: shoot at the GF bin with highest VCS weight
3. On wave break: record the actual GF the opponent was at → update VCS

**Segmentation:** Top bots segment VCS by distance, lateral velocity,
acceleration, wall distance, time-since-direction-change. Each segment
has its own histogram. More segments = more precise but slower learning.

---

## Movement Strategies

| Strategy | Description | Effective against |
|---|---|---|
| **Orbital** | Circle at fixed distance | Head-on guns |
| **Random dodge** | Random direction changes | Simple targeting |
| **Stop-and-go** | Stop when opponent fires | Linear/circular guns |
| **Wave surfing** | Minimize danger vs incoming waves | GF targeting |
| **Orbit-primary** | Orbit at max speed; wave-dodge only on imminent waves | Most guns |
| **Minimum risk** | Move to lowest-danger position | All |
| **Flattening** | Deliberately uniform GF profile | VCS-based guns |

### Wave Surfing (Detail)

1. Track all incoming opponent waves
2. For each wave: compute danger at each reachable GF position
3. Choose the movement that minimizes total weighted danger
4. Weight by `damage(power)` — power-3.0 bullet = 40× the weight of power-0.1

**Damage-weighted surfing** is mandatory in multi-wave combat (96% of
engagements have 2+ concurrent waves).

### Orbit-Primary (Our Current Approach)

**Finding (2026-05-09):** Constant wave surfing (evaluating candidates every
tick) caused oscillation that HURT performance vs most opponents. The
reachable-envelope candidates are too close together, leading to jittery
direction changes that reduce velocity and make movement predictable.

**Current implementation:** High-speed lateral orbit (|ahead|=150) with
random direction reversals (15-45 tick intervals). Only activate the
PathPlanner when an opponent wave is imminent (< 12 ticks to break).
This gives 28% opponent HR vs 47% with constant wave surfing against
top-50 opponents.

**Wall-aware reversal:** Force direction change when wall distance < 60px
to avoid getting trapped in corners.

---

## How Top Bots Work

### DrussGT 3.1.7 (Wave Surfer + Dynamic Clustering)

- **Gun:** Dynamic Clustering GF targeting with multi-dimensional
  segmentation (distance, lateral velocity, acceleration, wall distance,
  time-since-direction-change, bullet flight time)
- **Movement:** True wave surfing with precise prediction. Evaluates
  ~200 candidate positions per wave. Flattener for anti-GF defense.
- **Unique:** Largest cross-round adaptation in our data (KS=0.172).
  Shifts firing patterns significantly between rounds.

### Diamond 1.8.22 (True Surfer)

- **Gun:** Multi-mode: GF targeting + pattern matching + anti-surfer
- **Movement:** True wave surfing (precise prediction, reachable
  envelope). Pioneered the approach.
- **Unique:** Very stable behavior. Low cross-round adaptation.

### BeepBoop 2.0 (Dominant, 99.5% win rate)

- **Movement:** Distinct Cluster 3 (smooth, deliberate, wave-surfing)
- **Unique:** Best overall bot in the 50-bot pool. Minimal adaptation
  between rounds (KS=0.016). Dominant without needing to adapt.

### Shadow 3.83c (Pattern Matcher)

- **Gun:** Symbolic pattern matching on movement sequences
- **Movement:** Wave surfing variant
- **Unique:** Pattern matching is effective when it has enough data
  but struggles with novel movement styles

---

## ML Prior Art in Robocode

### Why ML Hasn't Dominated (Historically)

1. **Data scarcity:** 35 rounds × ~3k ticks = 105k data points per battle.
   Not enough for deep learning within a single battle.
2. **Runtime constraints:** Java, no GPU, 4 KB data persistence limit
   between rounds (Robocode's `getDataFile()`)
3. **VCS is hard to beat:** Simple histogram with good segmentation
   achieves ~20% hit rate. ML models rarely exceed this significantly.
4. **Adaptation requirement:** Opponents change behavior between rounds.
   Static models can't keep up.

### Neural Targeting Timeline
- **Gaff** (Darkcanuck) — MLP with no hidden layers, online training.
  First competitive neural gun.
- Various experiments with single-layer perceptrons and small MLPs.
  None broke into top-10 LiteRumble.

### Our Edge: Offline Cross-Opponent Training

Traditional Robocode ML is online (learn during battle, 35 rounds).
Our approach is **offline-first:**
1. Train on 1944 battles across 50 bots (100k+ waves, millions of ticks)
2. Learn cross-opponent patterns (firing signatures, movement archetypes)
3. Distill to compact Java models (GBM trees, MLP weights)
4. Start the battle with strong priors, blend with online VCS

This gives us data that single-battle ML bots never have: knowledge of
how *each bot family* behaves, pre-computed GF distributions, and
name-hash-based opponent identification from the first scan.

---

## Segmentation Dimensions (Top-Bot Consensus)

From analysis of DrussGT, Diamond, Dookious, Shadow, Gilgalad, Phoenix:

| Dimension | Used by | Our feature |
|---|---|---|
| Distance | All 6 | `DISTANCE` |
| Lateral velocity | All 6 | `OPPONENT_LATERAL_VELOCITY` |
| Advancing velocity | 4/6 | `OPPONENT_ADVANCING_VELOCITY` |
| Time since direction change | 5/6 | `OPPONENT_TIME_SINCE_DIRECTION_CHANGE` |
| Wall distance (opponent) | 4/6 | `OPPONENT_DIST_TO_WALL_MIN` |
| Acceleration / deceleration | 5/6 | `OPPONENT_VELOCITY_DELTA`, `OPPONENT_IS_DECELERATING` |
| Bullet flight time | 3/6 | `OUR_BULLET_TRAVEL_TIME` |
| Opponent wall-ahead | 2/6 | `OPPONENT_WALL_AHEAD_DISTANCE` |

These are the "must-have" features for any competitive targeting system.
All are implemented in our Feature enum.

---

## Wave Stacking (Research Finding)

From notebooks 12 and 13:

- **No bot in the pool** does deliberate wave stacking
- 96.3% of fires happen with 2+ waves already in flight (incidental)
- Near-simultaneous arrivals (≤5 tick gap): only 1.1% of wave pairs
- Wave convergence does NOT improve hit rate (p=0.60)
- Under tight multi-wave pressure, opponent GF spread narrows 17%

**Conclusion:** Wave stacking is a niche anti-mid-tier tactic. Prioritize
multi-wave defense (damage-weighted surfing) over offensive stacking.
Wave stacking plans were explored and removed as dead code.

---

## Movement Archetypes (From Clustering)

K=5 clustering on movement features identifies:

| Cluster | Bots | Behavior |
|---|---|---|
| 0 | Most bots | Standard orbital/oscillation |
| 1 | Random movers | High velocity variability |
| 2 | Wall huggers | High wall proximity |
| 3 | BeepBoop, DrussGT, Seraphim | Smooth, deliberate wave surfing |
| 4 | Stationary/disabled | Very low movement |

Cluster 3 (top-tier surfers) is the most distinctive and hardest to hit.

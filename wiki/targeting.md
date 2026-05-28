# Targeting — Comprehensive Reference

*Distilled from RoboWiki, top-bot analysis, and competitive Robocode knowledge.*

## Fundamentals

Targeting is a **classification problem**: given the game state at fire time,
predict the angular offset (GuessFactor) where the opponent will be when the
bullet arrives. The opponent cannot see your gun angle or bullets in flight
(only detects energy drops when you fire).

### Constraints
- Very small CPU budget per tick (~4ms on reference hardware)
- Dataset grows continuously (0 → 105k ticks over 35 rounds)
- Must perform well at all data quantities (cold start → saturated)
- Opponent adapts between rounds (non-stationary target)

---

## GuessFactor (GF)

The universal normalized firing angle in competitive Robocode.

**Definition:** `GF = lateralDirection × bearingOffset / maxEscapeAngle`

- **GF = 0.0** → Head-on (directly at opponent's current position)
- **GF = +1.0** → Maximum escape in opponent's current lateral direction
- **GF = −1.0** → Maximum escape in reverse direction

### Why GF Works
1. **Direction-invariant:** Clockwise and counterclockwise movement map to same bins
2. **Distance-invariant:** Normalized by MEA, so bins mean the same thing at 200px and 800px
3. **Compact representation:** All targeting data fits in [-1, +1] range

### Calculation
```java
double absBearing = getHeadingRadians() + e.getBearingRadians();
double lateralVelocity = e.getVelocity() * Math.sin(e.getHeadingRadians() - absBearing);
int lateralDirection = (lateralVelocity >= 0) ? 1 : -1;

// At wave break time:
double bearingOffset = Utils.normalRelativeAngle(actualAngle - absBearing);
double gf = lateralDirection * bearingOffset / maxEscapeAngle;
```

---

## Maximum Escape Angle (MEA)

The largest angular offset from head-on that could possibly hit an enemy.

### Classical Formula
```
MEA = arcsin(8.0 / bulletSpeed)
```
where `bulletSpeed = 20 - 3 × power`.

| Power | Speed | Classical MEA |
|-------|-------|---------------|
| 0.1   | 19.7  | 23.9°         |
| 1.0   | 17.0  | 28.1°         |
| 2.0   | 14.0  | 34.8°         |
| 3.0   | 11.0  | 46.7°         |

### Precise MEA (Movement Simulation)
Classical MEA assumes a point robot at max velocity perpendicular to the
bearing line. In reality:
- Robots can't instantly reach max speed (8 ticks to accelerate 0→8)
- Walls constrain escape (opponent near wall has reduced MEA)
- Robot hitbox has width (36×36 px)
- Deceleration is 2× faster than acceleration

**Precise MEA** uses tick-by-tick simulation of the opponent's maximum
movement to find actual escape envelope. Used by Diamond, DrussGT, etc.

### Effective MEA (Hitbox-Adjusted)
```
MEA_eff = arcsin((8.0 + hitbox_half_cross_section) / bulletSpeed)
```
At 45° angle the cross-section is 36√2 ≈ 50.9px (41% larger than 36px).

---

## Waves

A wave represents all possible locations of a bullet at a given time.

### Wave Properties
- **Origin:** Firer's position at fire tick
- **Speed:** `20 - 3 × power` px/tick
- **Radius at time t:** `speed × (t - fireTick)`
- **Break condition:** `radius ≥ distance_to_target`

### Wave Creation (Fire Detection)
Detect opponent fires via energy drop:
```java
double energyDrop = prevEnergy - currentEnergy;
if (energyDrop >= 0.1 && energyDrop <= 3.0) {
    // Opponent fired with power = energyDrop
    createWave(opponentPosition, energyDrop, currentTick);
}
```

**Corrections needed:**
- Subtract our bullet damage delivered this tick
- Subtract ram damage
- Add energy gained from opponent hitting us (3 × power)

### Wave Break
When wave radius reaches our position:
```java
double distTraveled = wave.speed * (currentTick - wave.fireTick);
if (distTraveled >= wave.origin.distance(myPosition)) {
    // Wave breaks — record GF observation
    double angle = absoluteBearing(wave.origin, myPosition);
    double offset = Utils.normalRelativeAngle(angle - wave.firingAngle);
    double gf = wave.lateralDirection * offset / wave.maxEscapeAngle;
    recordHit(wave.segmentKey, gf);
}
```

---

## Targeting Strategies (by complexity)

### 1. Head-On Targeting
Fire at opponent's current position. Zero learning, zero effectiveness
against anything that moves laterally.

### 2. Linear Targeting
Assume constant velocity + heading:
```
futureX = x + velocity × sin(heading) × flightTime
futureY = y + velocity × cos(heading) × flightTime
```
Effective against: constant-speed orbital bots.

### 3. Circular Targeting
Assume constant velocity + turn rate:
```
for each tick in flightTime:
    heading += turnRate
    x += velocity × sin(heading)
    y += velocity × cos(heading)
```
Effective against: simple orbital movement.

### 4. GuessFactor Targeting (Visit Count Stats / VCS)

The workhorse of competitive Robocode. An online histogram of GF observations.

**Implementation:**
1. Allocate histogram: `double[] stats = new double[NUM_BINS]` (typically 31–63 bins)
2. On wave break: increment `stats[gfToBin(observedGF)]`
3. On fire: shoot at `binToAngle(argmax(stats))`

**Bin smoothing:** Apply Gaussian kernel to observations:
```java
for (int i = 0; i < NUM_BINS; i++) {
    double distance = Math.abs(i - hitBin);
    stats[i] += Math.exp(-0.5 * distance * distance / bandwidth);
}
```

**Effective against:** Patterned movement, predictable bots.
**Weak against:** Random movement, flatteners, anti-GF movement.

### 5. Segmented GF Targeting

Partition firing situations into segments, each with its own VCS histogram.

**Common segmentation dimensions** (consensus across top-6 bots):

| Dimension | Typical Segments | Why |
|-----------|------------------|-----|
| Distance | 5 (200px each) | Closer = less time to dodge |
| Lateral velocity | 5 | Speed affects GF distribution |
| Advancing velocity | 3–5 | Approaching vs retreating |
| Acceleration | 3 (accel/cruise/decel) | Predicts direction changes |
| Time since direction change | 4–5 | Fresh reverse vs established path |
| Wall distance (opponent) | 3–4 | Constrained escape near walls |
| Bullet flight time | 3–4 | Longer flight = more uncertainty |

**Tradeoff:** More dimensions = more precision but exponentially slower
learning. A 5×5×5×5×5 = 3125-segment space needs ~100k observations
to populate meaningfully.

### 6. Dynamic Clustering (KNN on waves)

Log every wave observation as a feature vector + GF outcome. At fire time,
find K nearest neighbors in feature space → build GF distribution from their
outcomes.

**Equivalent to:** k-NN classification with weighted voting on GF bins.

**Advantages over segmented VCS:**
- No hard segment boundaries (smooth interpolation)
- Adapts granularity automatically (dense areas get more resolution)
- Can use many more dimensions without combinatorial explosion

**Disadvantages:**
- Slower query time (O(n log n) with kd-tree, O(n) naive)
- Memory grows linearly with observations
- Hyperparameter tuning (K, distance weights)

**Top bots using DC:** Shadow, DrussGT (gun only), Tron, DCBot.

### 7. Pattern Matching

Match recent enemy movement sequence against historical sequences.
Find most similar past window → "play it forward" to predict future position.

**Implementation:**
1. Record movement log: `[velocity, turnRate]` per tick
2. At fire time: extract recent window (e.g., last 30 ticks)
3. Search log for most similar past window (lowest distance)
4. Simulate future movement from the match point forward
5. Fire at predicted future position

**Distance metrics:** Euclidean on normalized sequences, cosine similarity,
or symbolic encoding (direction+speed discretized to characters → string
matching with longest substring).

**Effective against:** Bots with periodic/repetitive movement.
**Weak against:** Random movement, flat-profile surfers.

### 8. Anti-Surfer Guns

Specifically designed to defeat wave-surfing movement:
- Predict where the surfer will move to (they go to min-danger GF)
- Fire at the GF the surfer is likely to surf *toward*
- Requires modeling the opponent's danger function

Used by top bots as a secondary gun mode against known surfers.

---

## Virtual Guns

Run multiple targeting algorithms in parallel, track their hypothetical
hit rates, select the best performer for each opponent.

### Implementation
1. Each "virtual gun" records what angle it *would* fire at each opportunity
2. On wave break: check if each virtual gun's predicted angle was correct
3. Fire with the gun that has the highest hit rate

### Selection Strategies
- **Raw hit rate:** Count hits / attempts
- **Rolling window:** Last N waves only (adapts to changing opponents)
- **Regret-based:** Minimize cumulative regret vs best arm (bandit problem)
- **Weighted blend:** Fire at weighted average of all guns' predictions

### Common Virtual Gun Sets
| Gun | Effective Against |
|-----|-------------------|
| Head-on | Stationary, oscillators |
| Linear | Constant-speed movers |
| Circular | Orbital bots |
| GF/VCS | Patterned movers |
| Dynamic Clustering | Complex movers |
| Pattern Matching | Repetitive movers |
| Anti-Surfer | Wave surfers |

---

## Bullet Flight Time

```java
int flightTime = (int)Math.ceil(distance / bulletSpeed);
```

**Critical physics notes:**
- Bullet collision check happens AFTER bullets advance, BEFORE robots advance
- Effective dodge time = flightTime - 1 ticks
- Bullet fires from your position on the SAME tick as `setFire()`
- Gun turning on the fire tick happens AFTER the bullet is created

---

## Bullet Shadow

When your bullet intersects an opponent's wave, the GF range covered by
that intersection is **guaranteed safe** (can't be hit there because the
opponent's bullet would have collided with yours first).

### Passive Bullet Shadow
- Fire normally at the opponent
- Track your bullets + opponent waves
- When bullet intersects wave, mark that GF range as danger=0 in surfing

### Active Bullet Shadow
- Deliberately fire to create large shadow regions
- Then move into those shadows
- Used by Diamond, DrussGT, Nene, BeepBoop

### Shadow Size
```
shadow_angle ≈ 2 × arctan(bullet_diameter / (2 × distance_from_wave_origin))
```
Bigger shadow for: closer waves, slower bullets (bigger effective cross-section).

---

## Energy Management

### Bullet Power Selection
- **Distance-based:** `power = max(1.0, min(3.0, (400 - distance) / 100 + 1))`
- **Energy-based:** Don't fire power > opponent's remaining energy / 4
- **Hit-rate scaled:** Higher power only when HR > threshold
- **Survival-aware:** Conserve energy when low, fire max when opponent is low

### When to Fire
- Don't fire if `gunHeat > 0` (can't fire anyway)
- Don't fire if `distance > 600` and hit rate < 10% (waste of energy)
- Always fire if opponent energy < 4 × power (guaranteed kill)
- Consider not firing to deny opponent energy-gain from hits

---

## Firing Angle Representation

| Method | Description | Best For |
|--------|-------------|----------|
| **GuessFactor** | Normalized angle [-1, +1] relative to MEA | Most targeting |
| **Bearing Offset** | Raw angle from head-on (radians) | Simple guns |
| **Play It Forward** | Predict future (x,y) position | Pattern matching |
| **Displacement Vector** | Predict displacement from current position | DC guns |

---

## Key Performance Metrics

### Hit Rate
```
hitRate = bulletsHit / bulletsFired
```
- **Head-on gun:** 5–15% against movers
- **Good GF gun:** 15–25% against mid-tier
- **Top guns (DC/PM):** 20–35% against all
- **Against top surfers:** 8–15% (hard ceiling)

### Targeting Challenge References
- DrussGT gun: ~27% across LiteRumble
- Diamond gun: ~25%
- Best neural guns historically: ~20% (couldn't beat VCS/DC)

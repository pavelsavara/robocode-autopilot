# Movement — Comprehensive Reference

*Distilled from RoboWiki, top-bot analysis, and competitive Robocode knowledge.*

## Fundamentals

Getting hit depletes energy, increases opponent's score and energy (3 × power
returned on hit). Movement must minimize bullet damage taken while maintaining
offensive capability (stable radar, good firing distance).

### Movement Profile
The set of GF values at which you actually are when each opponent wave breaks.
- **Flat profile:** Uniform distribution → minimizes opponent's best possible HR
- **Peaked profile:** Predictable → opponent VCS will find you
- **Goal:** Be as unpredictable as possible (flat) while also dodging specific dangers

---

## Wave Surfing

The dominant movement paradigm since 2004. Used by all top-40 duelists.

### How It Works
1. **Detect opponent fires** (energy drop → create wave)
2. **Build danger model** (where has opponent aimed before? → VCS on opponent's gun)
3. **Precise prediction** (simulate own movement tick-by-tick → reachable positions)
4. **Choose movement** (minimize danger at reachable wave-break GF positions)

### Wave Detection
```java
double energyDrop = previousOpponentEnergy - currentOpponentEnergy;
if (energyDrop >= 0.1 && energyDrop <= 3.0) {
    Wave wave = new Wave();
    wave.origin = opponentPosition; // at time of fire
    wave.power = energyDrop;
    wave.speed = 20 - 3 * energyDrop;
    wave.fireTick = currentTick;
    wave.maxAngle = Math.asin(8.0 / wave.speed);
    waves.add(wave);
}
```

### Precise Prediction
Tick-by-tick simulation of own robot using Robocode physics:
```java
// For each candidate direction (forward, stop, reverse):
double simX = myX, simY = myY;
double simVel = myVelocity, simHeading = myHeading;
for (int t = 0; t < timeToWaveBreak; t++) {
    // Apply Robocode movement rules:
    double maxTurn = Math.toRadians(10 - 0.75 * Math.abs(simVel));
    simHeading += clamp(desiredTurn, -maxTurn, maxTurn);
    simVel += acceleration; // +1 or -2
    simVel = clamp(simVel, -8, 8);
    simX += simVel * Math.sin(simHeading);
    simY += simVel * Math.cos(simHeading);
    // Wall collision...
}
```

### Danger Function
For each reachable GF position, compute danger:
```java
double danger = 0;
int bin = gfToBin(reachableGF);
// Weight by opponent's historical targeting distribution:
danger += opponentVCS[bin] * botWeight(wave.power);
// Consider multiple waves:
for (Wave w : activeWaves) {
    danger += getDangerAtPosition(position, w);
}
```

### True Surfing vs GoTo Surfing

| Style | Decision | Used By |
|-------|----------|---------|
| **True Surfing** | Each tick: forward/stop/reverse | Diamond, Shadow, BasicSurfer |
| **GoTo Surfing** | Calculate safest point, navigate there | DrussGT |

**True Surfing** is more responsive but can oscillate.
**GoTo Surfing** is smoother but commits to a destination.

### Multi-Wave Surfing
Surf multiple concurrent waves (damage-weighted):
- Weight each wave by `4 × power` (power ≤ 1) or `6 × power - 2` (power > 1)
- Power 3.0 wave = 16 damage → 40× more dangerous than power 0.1
- **96% of engagements have 2+ concurrent waves** (must handle multi-wave)

---

## Flattener (Anti-Statistics Movement)

Deliberately move to create a flat GF profile (uniform distribution).
Makes opponent's VCS/DC gun ineffective because no bin stands out.

### Implementation
Track own visit-count stats (where waves break on you) and
bias movement toward under-visited bins:
```java
double[] myProfile = getMyVisitCounts();
// Move toward the GF with lowest visit count:
int targetBin = argmin(myProfile);
// Blend: dangerWeight × opponentDanger + flattenWeight × (1/myProfile[bin])
```

### When to Flatten
- Against strong statistical guns (DrussGT, Dookious)
- When hit rate against you is rising (opponent is adapting)
- **NOT** against simple guns (linear/circular) — surfing is better

### Adaptive Flattening
Switch between pure surfing and flattening based on opponent's gun type:
- Track hit-by-bullet events
- If hits cluster at specific GFs → opponent has statistical gun → flatten
- If hits are random → opponent has simple gun → just surf

---

## Orbital Movement

The baseline movement for most bots. Circle the opponent at desired distance.

### Basic Orbit
```java
double bearing = opponent.getBearingRadians();
double perpendicular = bearing + Math.PI/2 * direction; // ±1
setTurnRightRadians(Utils.normalRelativeAngle(perpendicular - getHeadingRadians()));
setAhead(150 * direction);
```

### Distance Control
```java
double targetDistance = 400; // px
double distanceError = currentDistance - targetDistance;
double inwardAngle = Math.atan2(distanceError, 100); // smooth approach
perpendicular -= inwardAngle * direction;
```

### Random Direction Changes
Periodic reversals to add unpredictability:
- Fixed interval (every 20-40 ticks) — simple but somewhat predictable
- Random interval (15-45 ticks) — better
- On bullet detection (when opponent fires) — intelligent but reactive

---

## Stop-and-Go

Exploit simple targeters by stopping when opponent fires.

### Mechanism
1. Move at full speed normally
2. When opponent fires (energy drop detected): stop immediately
3. Bullet was aimed at where you were going → misses
4. Resume movement after bullet passes

### Effective Against
- Head-on targeting (always misses when you stop)
- Linear targeting (extrapolates your velocity → misses)
- Circular targeting (same reason)

### Ineffective Against
- GF targeting (records where you actually end up → adapts)
- Pattern matching (learns the stop pattern)

---

## Wall Smoothing

Avoid getting trapped in corners where escape angle is reduced.

### Why Walls Matter
- Near wall → reduced MEA → easier to hit
- Corner = two walls → severely constrained movement
- Top bots avoid corners and maintain distance from walls

### Implementation
```java
// When heading toward wall:
double wallDistance = distanceToWall(myPosition, myHeading);
if (wallDistance < smoothingDistance) {
    // Turn parallel to wall
    double wallAngle = getWallAngle(nearestWall);
    setTurnRightRadians(Utils.normalRelativeAngle(wallAngle - getHeadingRadians()));
}
```

### Stick-to-Wall Smoothing (Competitive)
Project future position along intended path. If it hits wall, iteratively
adjust angle until path is clear:
```java
double testAngle = desiredAngle;
for (int i = 0; i < 100; i++) {
    Point2D test = project(myPosition, testAngle, moveDistance);
    if (fieldRect.contains(test)) break;
    testAngle += direction * 0.02; // 1° increments
}
```

---

## Dive Protection

Prevent opponent from closing distance rapidly (diving) which reduces
your dodge time and increases their hit probability.

### Strategy
- If opponent approaches rapidly (`advancingVelocity > threshold`):
  - Increase perpendicular movement speed
  - Consider backing away
  - Fire higher-power bullets (more damage per hit at close range)
- Maintain minimum distance (typically 200px)

---

## Distancing

Optimal distance depends on:
- **Your gun accuracy:** Better accuracy → closer is better (higher HR, more damage)
- **Your dodge ability:** Better dodge → closer is fine
- **Opponent gun accuracy:** Worse enemy gun → closer (they miss more anyway)

### Common Distances
| Strategy | Distance | Rationale |
|----------|----------|-----------|
| Aggressive | 200-300px | High-confidence gun, force engagement |
| Standard | 350-500px | Balanced dodge time + accuracy |
| Defensive | 500-700px | Maximize dodge time, conserve energy |
| Disengaged | 700+ px | Avoid engagement entirely (melee) |

---

## Movement Archetypes (From Our Data)

K=5 clustering on movement features across 50-bot pool:

| Cluster | Bots | Key Characteristics |
|---------|------|---------------------|
| 0 | Most bots | Standard orbital/oscillation, moderate speed |
| 1 | Random movers | High velocity variance, unpredictable |
| 2 | Wall huggers | High wall proximity, constrained paths |
| 3 | BeepBoop, DrussGT, Seraphim | Smooth deliberate surfing, optimal positioning |
| 4 | Stationary/disabled | Very low movement (early death, bugs) |

**Cluster 3 (top surfers)** is the hardest to target because:
- No oscillation patterns to exploit
- Smooth velocity (no sharp reversals = hard to pattern-match)
- Actively dodging based on wave data
- Nearly flat GF profile

---

## Bullet Shadow Integration (Movement)

### Passive Bullet Shadow in Surfing
```java
for (Wave wave : opponentWaves) {
    for (Bullet myBullet : myBulletsInFlight) {
        double[] shadowRange = computeShadow(myBullet, wave);
        if (shadowRange != null) {
            // GF range [shadowRange[0], shadowRange[1]] is guaranteed safe
            for (int bin = lo; bin <= hi; bin++) {
                waveDanger[bin] = 0; // Cannot be hit here
            }
        }
    }
}
```

### Active Shadow Strategy
- Fire specifically to create shadows where you plan to move
- Requires coordinating gun aim with movement intent
- Used by Diamond, DrussGT, Nene, BeepBoop, ScalarR

---

## Key Performance Metrics (Movement)

### Hit Rate Against (Lower = Better)
- **No movement:** ~80% HR against any gun
- **Simple orbit:** 20-35% against GF guns
- **Random movement:** 12-20% (flat profile but wastes energy)
- **Wave surfing:** 8-15% against top guns
- **Top surfers + flattening:** 6-10%

### Survival Contribution
In 35-round battle with scoring:
- 50 points per round survived (bonus)
- Surviving means total-damage-taken < initial-energy (100)
- Even small HR improvements compound over 35 rounds

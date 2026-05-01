---
tags: [projects, robocode, ML, reference]
created: 2026-04-12
updated: 2026-04-12
sources: 1
---

# Robocode: Game Physics & Known Competitive Ideas

A reference document capturing the ground truth of the Robocode engine and the known competitive strategies, derived from the actual source code (`robocode.battle`, `robocode.api`) and the RoboWiki.

---

## Section 1: Game Physics (Exact Formulas)

### 1.1 Coordinate System

- **Origin (0, 0)** is at the **bottom-left** corner of the battlefield.
- **Heading convention:** Clockwise from north. 0° / 360° = North, 90° = East, 180° = South, 270° = West.
- Internally all angles are stored in **radians**.
- Position and distance use **double-precision** floating point. 1 distance unit ≈ 1 pixel (except when the UI scales down).

### 1.2 Time

- Time is measured in **ticks** (also called **turns**). Each robot gets to execute once per tick.
- `currentTime` increments by 1 each tick (see `BaseBattle.runTurn()`).

### 1.3 Robot Dimensions

- Robot bounding box: **36 × 36 pixels** (`RobotPeer.WIDTH = 36, HEIGHT = 36`).
- Half-size offset: 18 pixels from center to edge.

### 1.4 Initial State (per round)

| Property | Value | Source |
|----------|-------|--------|
| Energy | 100 | `RobotPeer.java:736` |
| Energy (Sentry) | 100 + 400 = 500 | `RobotPeer.java:738` |
| Energy (Team Leader) | 100 + 100 = 200 | `RobotPeer.java:741` |
| Energy (Droid) | 100 + 20 = 120 | `RobotPeer.java:744` |
| Gun Heat | 3.0 | `RobotPeer.java:748` |
| Velocity | 0. | `RobotPeer.java:735` |
| Gun/Radar Heading | Same as body heading | `RobotPeer.java:725` |

### 1.5 Turn Execution Order

The engine's `runTurn()` in `Battle.java` executes these steps in exact order:

```
1. time++                          (BaseBattle.runTurn — time incremented)
2. loadCommands()                  (robots' queued commands are loaded, bullets are created)
3. updateBullets()                 (all bullets move, then check wall/robot/bullet collisions)
4. updateRobots()
   a. performMove() for each robot (in random order):
      i.   updateGunHeat()         (gun heat decreases by cooling rate)
      ii.  updateHeading()         (body turns — gun and radar turn with it by default)
      iii. updateGunHeading()      (gun turns — radar turns with it by default)
      iv.  updateRadarHeading()    (radar turns)
      v.   updateMovement()        (acceleration/velocity/position update)
      vi.  checkWallCollision()    (bounce off walls, take damage)
      vii. checkRobotCollision()   (bounce off other robots, take damage)
   b. performScan() for each robot (in random order):
      i.   Execute the radar scan arc
      ii.  Dispatch team messages
5. handleDeadRobots()              (score deaths, notify survivors)
6. publishStatuses()               (send RobotStatus to each robot)
7. wakeupRobots()                  (robots resume execution and take new actions)
```

**Key insight:** Bullets move BEFORE robots move in each tick. A bullet fired in tick N appears in the bullet list at tick N and first moves at tick N+1.

**Fairness:** The order in which individual robots are processed within each step is **randomized** every tick (`getRobotsAtRandom()`).

### 1.6 Movement Physics

#### Acceleration

| Parameter | Value | Constant |
|-----------|-------|----------|
| Acceleration | +1 pixel/turn² | `Rules.ACCELERATION = 1` |
| Deceleration (braking) | −2 pixels/turn² | `Rules.DECELERATION = 2` |
| Max Velocity | 8 pixels/turn | `Rules.MAX_VELOCITY = 8` |

The engine uses the **optimal velocity algorithm** (Nat Pavasant / Voidious / Skilgannon):

```java
// Simplified from RobotPeer.getNewVelocity():
newVelocity = clamp(
    velocity - DECELERATION,     // min: max braking
    velocity + ACCELERATION,     // max: max acceleration
    goalVelocity                 // target: min(maxVelocity(distance), commandedMaxVel)
)
```

The `goalVelocity` for a given remaining distance is computed so the robot decelerates to stop exactly at the target distance. The quadratic formula used:

```
decelTime = ceil( (sqrt(4 * 2/DECELERATION * distance + 1) - 1) / 2 )
decelDist = (decelTime / 2) * (decelTime - 1) * DECELERATION
maxVel = (decelTime - 1) * DECELERATION + (distance - decelDist) / decelTime
```

**Over-driving:** When a robot is set to move a short distance but is already going fast, it will overshoot, then reverse to come back.

#### Position Update

```java
x += velocity * sin(bodyHeading);
y += velocity * cos(bodyHeading);
```

Note: `sin(heading)` for x, `cos(heading)` for y — because heading 0 = North = +y direction.

### 1.7 Rotation

#### Body Turn Rate

```
maxTurnRate = 10 - 0.75 * |velocity|    (degrees/turn)
```

Source: `Rules.getTurnRate(velocity)`. The faster you move, the slower you turn. At max velocity (8), turn rate = 10 − 6 = **4°/turn**. Stopped, it's **10°/turn**.

**Source code detail:** The actual turn rate calculation in `updateHeading()` uses a slightly different formula with a coefficient:

```java
turnRate = min(commandedMaxTurnRate,
    (0.4 + 0.6 * (1 - abs(velocity) / MAX_VELOCITY)) * MAX_TURN_RATE_RADIANS)
```

This is equivalent to `(10 - 0.75 * |v|)` in degrees when `commandedMaxTurnRate >= MAX_TURN_RATE`.

#### Gun Turn Rate

- **20°/turn** maximum (`Rules.GUN_TURN_RATE = 20`).
- By default, the gun heading matches the body heading turn. If `setAdjustGunForRobotTurn(true)` is called, the gun turns independently (relative to the screen), and the body turn is subtracted from the gun turn remaining.

#### Radar Turn Rate

- **45°/turn** maximum (`Rules.RADAR_TURN_RATE = 45`).
- By default, radar heading matches the gun heading turn. If `setAdjustRadarForGunTurn(true)` is called, radar turns independently. Similarly for `setAdjustRadarForRobotTurn(true)`.

#### Rotation Coupling Chain

By default: **Body turn → carries Gun → carries Radar.**

With `adjustGunForBodyTurn(true)`: Body turn decoupled from Gun.
With `adjustRadarForGunTurn(true)`: Gun turn decoupled from Radar.

### 1.8 Bullet Physics

#### Bullet Speed

```
bulletSpeed = 20 - 3 * power
```

| Power | Speed (px/turn) |
|-------|-----------------|
| 0.1 | 19.7 |
| 1.0 | 17.0 |
| 2.0 | 14.0 |
| 3.0 | 11.0 |

Source: `Rules.getBulletSpeed()`. Power is clamped to `[0.1, 3.0]`.

#### Bullet Damage

```
damage = 4 * power                       (if power <= 1)
damage = 4 * power + 2 * (power - 1)     (if power > 1)
```

Simplified for power > 1: `damage = 6 * power - 2`.

| Power | Damage |
|-------|--------|
| 0.1 | 0.4 |
| 1.0 | 4.0 |
| 2.0 | 10.0 |
| 3.0 | 16.0 |

Source: `Rules.getBulletDamage()`.

#### Energy Cost and Return

- **Firing cost:** Energy decreases by `firePower` (clamped to `[MIN_BULLET_POWER, min(energy, MAX_BULLET_POWER)]`).
- **Hit bonus:** On hitting an opponent, the firer **gains** `3 * power` energy.
- **Net energy on hit:** `+3 * power - power = +2 * power` (net gain).
- **Net energy on miss:** `-power` (loss).

Source: `Rules.getBulletHitBonus()`, `BulletPeer.checkRobotCollision()`.

#### Gun Heat

- **Heat generated by firing:** `1 + power / 5`.
- **Cooling rate:** Default `0.1` per tick (configurable via `BattleProperties.gunCoolingRate`).
- **Cannot fire** while `gunHeat > 0`.
- **Initial gun heat:** `3.0` → takes `3.0 / 0.1 = 30` ticks to cool down at default rate.

| Power | Heat Generated | Ticks to Cool (at 0.1/tick) |
|-------|----------------|------------------------------|
| 0.1 | 1.02 | ~11 |
| 1.0 | 1.20 | 12 |
| 2.0 | 1.40 | 14 |
| 3.0 | 1.60 | 16 |

Source: `Rules.getGunHeat()`, `RobotPeer.updateGunHeat()`.

#### Bullet Movement

Bullets are **line segments** (not points). Each tick, a bullet moves from `(lastX, lastY)` to `(x, y)`:

```java
x += velocity * sin(heading);
y += velocity * cos(heading);
boundingLine.setLine(lastX, lastY, x, y);
```

Collision detection uses **line-line intersection** (bullet-bullet) or **line-rectangle intersection** (bullet-robot).

#### Bullet State Machine

```
FIRED → MOVING → HIT_VICTIM / HIT_WALL / HIT_BULLET → INACTIVE
                                  ↓
                          (explosion animation for EXPLOSION_LENGTH=17 ticks)
                                  ↓
                              INACTIVE
```

#### Bullet-Bullet Collision

Two bullets collide when their bounding lines (last displacement vectors) intersect. Both bullets are destroyed. Each owner receives a `BulletHitBulletEvent`. This is the basis for **Bullet Shielding**.

### 1.9 Energy Model

| Event | Energy Change |
|-------|---------------|
| Fire bullet | −power |
| Hit enemy with bullet | +3 × power |
| Get hit by bullet | −(4 × power) if power ≤ 1, else −(6 × power − 2) |
| Robot-robot collision | −0.6 (both robots) |
| Ram bonus (active rammer) | Score points, no extra energy change |
| Wall collision | −max(|velocity| / 2 − 1, 0) |
| Inactivity zap | −0.1/tick (after inactivity time exceeded) |

Source: `Rules.ROBOT_HIT_DAMAGE = 0.6`, `Rules.getWallHitDamage()`.

### 1.10 Scan Mechanics

- **Radar scan radius:** 1200 pixels (`Rules.RADAR_SCAN_RADIUS`).
- **Scan arc:** The area swept between the radar's previous heading and current heading forms a pie-shaped arc.
- A robot is scanned if its 36×36 bounding box **intersects** the scan arc.
- A scan occurs automatically whenever any of (heading, gunHeading, radarHeading, x, y) change. Manual `scan()` can also be called.
- The scan event (`ScannedRobotEvent`) is generated during `performScan()` — AFTER all movement for that tick.
- **Droid robots** cannot scan (no radar).

### 1.11 Scoring

| Category | Points |
|----------|--------|
| Survival | +50 per enemy death while alive |
| Last Survivor Bonus | +10 per robot that died before you |
| Bullet Damage | 1 point per 1 energy damage dealt |
| Bullet Kill Bonus | +20% of all bullet damage dealt to killed robot |
| Ram Damage | 2 points per 1 energy ram damage dealt |
| Ram Kill Bonus | +30% of all ram damage dealt to killed robot |

Source: `Robocode/Scoring` on RoboWiki.

**Critical insight:** Survival is NOT enough. Aggressive bullet damage scoring means a robot that sits back and survives will lose to one that hits consistently.

### 1.12 Firing Pitfall

Bullets are created during `loadCommands()` (step 2) using the **current gun heading at command load time.** The gun turn requested in the same tick happens LATER in `updateGunHeading()` (step 4a-iii). This means:

> `setFire()` uses the gun heading from the PREVIOUS tick's final position, not the heading after this tick's turn.

To fire accurately: check that `getGunTurnRemaining() == 0` before calling `setFire()`, or pre-aim one tick ahead.

---

## Section 2: Observation Model

### 2.1 What a Robot Can Observe

**ScannedRobotEvent** fields:
| Field | Type | Description |
|-------|------|-------------|
| `name` | String | Enemy robot name |
| `energy` | double | Enemy's current energy |
| `bearing` | double | Bearing to enemy relative to our heading (radians) |
| `distance` | double | Distance to enemy (center-to-center) |
| `heading` | double | Enemy's body heading (radians) |
| `velocity` | double | Enemy's velocity (signed: positive=forward) |
| `isSentryRobot` | boolean | Whether enemy is a sentry |

**Other events providing information:**
- `HitByBulletEvent` — bullet heading, power, name of shooter
- `BulletHitEvent` — confirms our bullet hit, gives victim's remaining energy
- `BulletHitBulletEvent` — both bullets' details
- `BulletMissedEvent` — our bullet hit a wall
- `HitWallEvent` — we hit a wall
- `HitRobotEvent` — collision with another robot, bearing, energy, name
- `RobotDeathEvent` — an enemy died
- `RobotStatus` — our own full state (x, y, heading, gun heading, radar heading, velocity, gun heat, energy, etc.)

### 2.2 What a Robot CANNOT See

| Hidden Information | Consequence |
|-------------------|-------------|
| **Enemy gun heading** | Cannot directly see where they're aiming |
| **Enemy radar heading** | Cannot see their scan direction |
| **Enemy gun heat** | Cannot directly know when they can fire |
| **Bullets in the air** | Bullets are invisible; must be inferred |
| **Enemy's distance remaining** | Don't know their movement commands |
| **Enemy's turn remaining** | Don't know their rotation commands |
| **Other robots' scan events** | No eavesdropping |

### 2.3 The Timing Delay

The observation-action cycle introduces a **2-tick delay**:

```
Tick N:     Opponent acts (moves, fires)
Tick N+1:   We scan → receive ScannedRobotEvent with opponent's state after tick N's actions
Tick N+2:   Our response takes effect (our bullet fires, our movement happens)
```

This means we're always reacting to information that's 1-2 ticks old. For a bullet traveling at 11 px/turn (power 3), that's 11-22 pixels of travel — significant at close range.

### 2.4 Energy Drop Detection (The Key Inference)

Since bullets are invisible, the primary way to detect enemy fire is **energy monitoring**:

```
energyDrop = previousEnemyEnergy - currentEnemyEnergy
if (0.1 <= energyDrop <= 3.0):
    # Enemy likely fired a bullet with power = energyDrop
    bulletSpeed = 20 - 3 * energyDrop
```

**Confounders:**
- Wall collision damage matches `max(|v|/2 - 1, 0)` — could overlap with small fire powers
- Robot-robot collision damage is always 0.6 — distinguishable
- Inactivity zap is 0.1/tick — matches minimum fire power exactly
- Energy can drop from being hit by another robot's bullet — check if we or someone else hit them

**Limitations:**
- Energy drop detection has a 1-tick delay (we see the post-fire energy on the next scan)
- If the enemy fires AND gets hit in the same tick, the energy changes compound
- Multiple opponents in melee make attribution ambiguous

### 2.5 Gun Heat Inference

Although we can't see enemy gun heat directly, we can **estimate** it:

```
If enemy fired with power P at tick T:
    gunHeat = 1 + P/5
    ticksUntilNextFire = ceil(gunHeat / coolingRate)
    
    At default 0.1 cooling: ticksUntilNextFire = ceil((1 + P/5) / 0.1)
```

This gives a **minimum time** before the enemy can fire again. Sophisticated bots like DrussGT use "gun heat waves" — tracking the earliest tick an enemy could fire again.

---

## Section 3: Known Competitive Concepts

### 3.1 Waves

A **wave** is an expanding circle radiating from the point where a bullet was fired, traveling at bullet speed. It represents all possible positions a bullet could be at time `t`:

```
waveRadius = bulletSpeed * (currentTime - fireTime)
```

When the wave reaches the target (radius ≥ distance to target), we can determine what **firing angle offset** (from head-on) would have hit. This is used for:
- **Targeting data collection** — record which offsets would have hit
- **Wave surfing** — dodge based on predicted danger of each angle

### 3.2 GuessFactor

A **GuessFactor** (GF) normalizes firing angles to the range **[-1, +1]**:
- GF = 0: Head-on targeting (fire directly at current position)
- GF = +1: Maximum escape angle forward
- GF = −1: Maximum escape angle backward

```
guessFactor = actualBearingOffset / maxEscapeAngle
```

This normalization allows collecting statistics independent of distance and bullet speed.

### 3.3 Maximum Escape Angle (MEA)

The theoretical maximum angle a robot can be offset from head-on when the bullet arrives:

```
MEA = asin(maxRobotVelocity / bulletSpeed)
    = asin(8 / (20 - 3 * power))
```

| Power | Bullet Speed | MEA (degrees) |
|-------|-------------|---------------|
| 0.1 | 19.7 | 23.9° |
| 1.0 | 17.0 | 28.1° |
| 2.0 | 14.0 | 34.8° |
| 3.0 | 11.0 | 46.7° |

**Precise MEA** is smaller in practice because:
- Robots have finite acceleration (can't instantly reach max speed)
- Walls limit movement range
- Must account for current velocity and heading
- Precise prediction via actual movement simulation gives the real MEA

### 3.4 Visit Count Stats (VCS)

A **histogram** of GuessFactor bins. Each time a wave "breaks" (reaches the target), increment the bin corresponding to the GF where the target actually was:

```
bins[gfToBin(guessFactor)]++
```

To fire: choose the bin with the highest count and fire at that GF.

**Segmentation:** Separate VCS buffers for different situations:
- Distance to target (close/medium/far)
- Target lateral velocity
- Target advancing velocity
- Time since velocity change
- Wall proximity
- Acceleration state

### 3.5 GuessFactor Targeting

The combination of **GuessFactors + Segmented Visit Count Stats**. The dominant targeting paradigm in competitive Robocode:

1. When firing, create a wave with source position, bullet speed, and bearing to target
2. When wave reaches target, compute the GF that would have hit
3. Increment VCS bin in the appropriate segment
4. To fire next time: look up current segment, find peak bin, convert to firing angle:
   ```
   firingAngle = headOnBearing + peakGF * MEA
   ```

### 3.6 Wave Surfing

The **state-of-the-art movement** strategy. Invented by ABC for Shadow (2004). Used by virtually all top 1v1 bots.

**Algorithm:**
1. **Detect energy drop** → enemy fired → create an enemy wave
2. **Collect danger data** from `onHitByBullet` / `onBulletHitBullet`, matching to the correct wave
3. **For the nearest enemy wave:** use **precise prediction** to simulate all positions our robot could reach
4. **Evaluate danger** at each reachable GF position on the wave
5. **Move** toward the safest reachable position

**Styles:**
- **True Surfing** — each tick, decide: accelerate forward, backward, or stop. Most common (Diamond, Shadow, BasicSurfer).
- **GoTo Surfing** — calculate safest point on wave, navigate directly there (DrussGT).
- **Melee Surfing** — surf waves from multiple opponents simultaneously (Neuromancer).

### 3.7 Precise Prediction

Simulating the exact movement physics (acceleration, deceleration, wall collisions, turning) to predict where a robot can be N ticks in the future. Essential for:
- Wave surfing (where CAN I be when the wave arrives?)
- Precise MEA calculation
- Advanced targeting

### 3.8 Targeting Strategies

#### Head-On Targeting
Fire directly at the enemy's current position. Only works against stationary targets.

#### Linear Targeting
Assume the enemy continues at the same velocity and heading:
```
predictedX = enemyX + enemyVelocity * sin(enemyHeading) * t
predictedY = enemyY + enemyVelocity * cos(enemyHeading) * t
```
Iterate until `bulletSpeed * t ≈ distance(myPos, predictedPos)`.

**Exact non-iterative solution** using the law of sines:
```
firingAngle = headOnBearing + asin(enemyVelocity / bulletSpeed * sin(enemyHeading - headOnBearing))
```

#### Circular Targeting
Assume the enemy continues at the same velocity AND turn rate:
```
for each tick t:
    predictedX += sin(enemyHeading) * enemyVelocity
    predictedY += cos(enemyHeading) * enemyVelocity
    enemyHeading += enemyHeadingChange
```

#### Pattern Matching
Record sequences of enemy movements (velocity, heading changes). Search for matches in history and replay the matched future:
- **Symbolic Pattern Matching** — encode movements as discrete symbols
- **Folded Pattern Matching** — match patterns with rotation/mirror invariance

#### Dynamic Clustering (KNN)
Store movement situations as feature vectors. When firing, find the K nearest neighbors in situation-space and use their outcomes to predict the current situation's GF.

#### Anti-Surfer Targeting
Detect that the enemy is wave surfing and fire at the angle they'll dodge INTO, rather than where they currently are.

### 3.9 Movement Strategies

#### Random Movement
Move unpredictably. Change direction at random intervals. Effective against simple targeting but vulnerable to statistical targeting that tracks actual distribution.

#### Oscillator Movement
Move back and forth. Change direction at varying intervals to avoid predictability.

#### Stop And Go
Stop when the enemy fires (detected via energy drop), dodge after the bullet passes. Exploits the facts that aimed bullets target your movement, and the bullet takes time to arrive. Vulnerable to Head-On Targeting (which hits stopped targets).

#### Orbital Movement
Move in a circle around the enemy at a fixed or varying distance. Often combined with random direction changes.

#### Anti-Gravity Movement
Place virtual "gravity points" at walls, corners, and enemy positions. The robot moves away from danger points:
```
force = strength / distance²
```
Used mainly in melee to maintain distance from all threats.

#### Minimum Risk Movement
Evaluate candidate destinations by a risk function considering:
- Distance to all enemies
- Distance to walls/corners
- Angle relative to enemies (avoiding being cornered)
- Historical safety of positions

Select the lowest-risk destination and navigate there.

#### Wall Smoothing
When movement hits a wall, redirect along the wall surface rather than stopping. Maintains velocity and avoids wall-hit damage. Several implementations:
- **Stick method** — project a "stick" from the robot and adjust angle if it would exit the battlefield
- **Non-iterative** — compute the exact tangent angle to the wall boundary

### 3.10 Bullet Shielding

Fire **low-power bullets (0.1)** to intercept enemy bullets. The enemy's high-power bullets are neutralized by your cheap 0.1-power bullets, draining the enemy's energy much faster:

**Algorithm:**
1. Detect enemy fire via energy drop
2. Estimate bullet heading (assume it targets you)
3. Lead-fire at the incoming bullet (it's a "target" moving at bullet speed)
4. Use power 0.1 (cheapest) and adjust for midpoint intersection
5. Recalculate and fire

**Counter**: Bullet shielding fails against enemies that don't shoot, or that use very low power themselves.

### 3.11 Virtual Guns

Maintain multiple targeting algorithms simultaneously. For each, track a virtual wave — when it reaches the target, check if that gun's angle would have hit. Keep score for each gun. Use the gun with the highest hit rate for actual firing.

### 3.12 Flattener

When your movement's GF profile is being learned by the enemy, deliberately **flatten** your profile — move to less-visited GFs to make all bins equally likely, making statistical targeting useless.

### 3.13 Energy Management / Fire Power Selection

| Situation | Recommended Power | Reasoning |
|-----------|-------------------|-----------|
| Close range (< 150) | 3.0 | High damage, easy to hit, net energy gain on hit |
| Medium range (150-400) | 1.5-2.0 | Balance damage and hit probability |
| Long range (> 400) | 0.5-1.0 | Lower power = faster bullet = more likely to hit |
| Low energy | 0.1-0.5 | Conserve energy |
| Enemy low energy | Just enough to kill | Don't waste energy |
| Bullet shielding mode | 0.1 | Cheapest interception |

**Net energy on hit:** `3 * power - power = 2 * power` (always positive).
**Net energy on miss:** `-power` (always negative).

Higher power: more damage dealt, but slower bullet (easier to dodge), more energy risked.

---

## Section 4: What Can Be Learned / Predicted (The 5 Domains)

### Domain A: Opponent Movement Prediction

**Observable features:**
- Current position (from bearing + distance)
- Current velocity (signed, from ScannedRobotEvent)
- Current heading (from ScannedRobotEvent)
- Heading change (delta between consecutive scans)
- Acceleration (velocity delta)
- Wall proximity
- Distance to us
- Time since last direction change
- Lateral velocity relative to us
- Advancing velocity relative to us

**Predictable via:**
- Linear extrapolation (constant velocity + heading)
- Circular extrapolation (constant velocity + turn rate)
- Pattern matching on movement sequences
- GuessFactor statistics over segmented situations
- Neural networks / KNN on feature vectors
- Precise prediction with physics simulation

**Key constraint:** Prediction degrades with:
- Distance (more time for opponent to change course)
- Opponent skill (better movement = more unpredictable)
- Number of segments observed (cold-start problem)

### Domain B: Opponent Firing Detection

**Observable:**
- Energy drops between scans (primary signal)
- HitByBullet events (confirmed hits, too late to dodge that bullet but useful for learning)
- BulletHitBullet events (confirms bullet existed)
- Timing patterns (bots tend to fire on regular intervals)

**Inferrable:**
- Bullet power from energy drop amount
- Bullet speed from power
- Approximate bullet heading (assume aimed at us, or use enemy gun heading — which we can't see)
- Earliest next fire time from gun heat model
- Enemy's targeting tendency (what GFs they favor) by tracking hits and matching to waves

**Gun heat waves:** Track when the enemy CAN fire based on estimated gun heat. Creates "virtual waves" at every possible fire time.

### Domain C: Our Firing Optimization

**Decision variables:**
- Firing angle (continuous)
- Bullet power (continuous, 0.1 to 3.0)
- Whether to fire at all (wait for better opportunity?)

**Inputs for aiming:**
- Opponent movement history (segmented VCS, pattern match, clustering)
- Current opponent state
- Distance and bearing
- Our gun heading and remaining turn
- Bullet flight time estimate

**Key tradeoffs:**
- Power vs. speed vs. dodge-ability
- Accuracy of current targeting model vs. exploration
- Energy expenditure vs. expected return
- Firing immediately vs. waiting for better gun alignment

### Domain D: Our Movement Optimization (Dodging)

**Goal:** Minimize probability of being hit.

**Inputs:**
- Detected enemy waves (from energy drops)
- Enemy firing pattern (which GFs they aim at)
- Our current position and velocity
- Wall constraints
- Reachable positions when wave arrives (precise prediction)

**Strategies ranked by sophistication:**
1. **Random movement** — baseline, doesn't use wave data
2. **Stop And Go** — exploits timing but vulnerable to head-on
3. **Basic Wave Surfing** — dodge the nearest wave to lowest-danger GF
4. **Multi-wave Surfing** — consider 2+ closest waves simultaneously
5. **Flattening** — actively equalize GF visit distribution
6. **Bullet Shielding** — intercept bullets directly

### Domain E: Strategy Selection (Meta-Game)

**Adapt behavior to the game state:**

| State | Strategy |
|-------|----------|
| Winning on energy | Conservative fire power, maintain distance |
| Losing on energy | Aggressive low-power fire, close distance for easier hits |
| Opponent is predictable | Exploit with segmented GF targeting |
| Opponent is surfing | Switch to anti-surfer / flattened targeting |
| Opponent is shielding | Stop firing (starve them of bullets to block) or fire very low power |
| Early rounds | Explore, gather data |
| Late rounds | Exploit learned model |
| Melee (>2 bots) | Minimum risk positioning, fire on closest/weakest |

**Virtual Guns** serve as an automatic strategy selector for targeting — the best-performing gun is used automatically.

---

## Section 5: Advanced Movement Concepts

### 5.1 Precise Prediction (Tick-by-Tick Simulation)

The foundation of all top-tier movement. Rather than estimating where you'll be with simple geometry, simulate the EXACT Robocode physics tick-by-tick:

```
for each future tick t:
    1. Compute desired move angle (perpendicular to wave source, wall-smoothed)
    2. Determine if moving forward or backward (back-as-front optimization)
    3. Apply max turning constraint: maxTurn = PI/720 * (40 - 3*|velocity|) radians
    4. Clamp heading change to [-maxTurn, maxTurn]
    5. Update heading
    6. Apply acceleration (+1) or deceleration (-2) based on move direction
    7. Clamp velocity to [-8, 8]
    8. Update position: x += velocity * sin(heading), y += velocity * cos(heading)
    9. Check: has the wave (expanding at bulletSpeed) reached this position?
    10. If yes: return this position as the predicted intercept point
```

**Why it matters:** Simple estimates (e.g., "I can be anywhere within MEA") are inaccurate because:
- You can't instantly reach max speed (acceleration = 1 per tick)
- Your turn rate depends on current velocity
- Walls constrain reachable positions
- Deceleration is 2x faster than acceleration, creating asymmetric options

**Implementations:**
- Albert's FuturePosition classes — one of the first
- rozu's Apollon code — used in the Wave Surfing Tutorial (BasicSurfer)
- Nat's Movement Predictor — supports latest deceleration rules

### 5.2 Wall Smoothing Algorithms

A method for avoiding wall collisions **without reversing direction**. Instead of bouncing off walls (predictable), the robot turns to glide along the wall surface.

**The Blind Man's Stick Method (iterative):**
```java
public double wallSmoothing(Point2D.Double botLocation, double angle, int orientation) {
    while (!fieldRect.contains(project(botLocation, angle, WALL_STICK))) {
        angle += orientation * 0.05;  // nudge angle until stick fits inside field
    }
    return angle;
}
```

The "wall stick" is an imaginary line (120-160 pixels) extending from the robot. If it would exit the battlefield, the desired angle is rotated until it stays inside.

**Why it matters for surfing:** When predicting reachable positions, the precise prediction loop must also wall-smooth. Otherwise, the prediction diverges from what really happens (the bot actually wall-smooths), making danger calculations wrong.

**Corner handling:** Near corners, the smoothing angle becomes steep — the robot drives toward the enemy rather than perpendicular. This creates a vulnerability. Top bots add "dive protection" — refusing to close distance beyond a threshold even when wall smoothing demands it.

**Melee wall smoothing** is more complex — there's no single enemy to orbit around. Some bots pick points far from walls to avoid the problem; others adapt the orbital smoothing to the direction toward the chosen destination.

### 5.3 Orbital / Perpendicular Movement

Moving roughly perpendicular to the line-of-sight to the enemy (orbiting around them). This is the backbone of 1v1 movement:

**Why perpendicular?**
- Maximizes angular velocity relative to the enemy (hardest to aim at)
- Maintains roughly constant distance
- Works naturally with wave surfing (surfing = choosing WHICH direction to orbit)

**Back-as-front:** Robots can move backward just as fast as forward. Smart bots treat both directions equivalently — if the desired orbit direction is behind the robot, they set `ahead(-100)` instead of turning around. This eliminates the vulnerable turning-in-place phase.

**Distancing:** Pure perpendicular movement maintains constant distance. Top bots add a slight inward/outward bias to approach a preferred combat distance (typically 400-600 pixels). This is done by adjusting the orbit angle from exactly π/2 to slightly less or more.

### 5.4 Stop And Go Movement (Detailed)

Exploits a fundamental timing gap in simple targeting:

**How it works:**
1. Monitor enemy energy for drops (fire detection)
2. When the enemy fires while you're stopped → **start moving** (the bullet was aimed at your stationary position)
3. When the enemy fires while you're moving → **stop** (the bullet was aimed at your moving trajectory)

**Two flavors:**
- **Ares-style:** Move briefly after fire detection, then stop again before the next bullet. Dodges each bullet individually.
- **GrubbmGrb-style:** Toggle state on each fire. Move on odd bullets, stop on even (or vice versa).

**Effective against:** Head-on targeting, linear targeting, circular targeting — any gun that aims at where you ARE (or are heading), since you change state after the bullet is already fired.

**Countered by:** Any gun that logs your movement profile. Pattern matching, GuessFactor targeting with velocity segmentation, or even simple "mean targeting" will learn the oscillation. **Most bots near the top of the MicroRumble use Stop-And-Go**, but full-size competitive bots need more.

**Multi-Mode integration:** Start a battle with Stop-And-Go (cheap, effective against many bots). If hit rate is too high, switch to wave surfing. This is the standard pattern for size-restricted bots.

### 5.5 Flattening (Adaptive Movement Profile Equalization)

A proactive defense against statistical targeting. The robot tracks its OWN movement profile (just like an enemy's gun would) and deliberately avoids overused GuessFactor bins.

**Implementation:**
1. Maintain a "self-shadow" VCS buffer using the same segmentation the enemy might use
2. Every time a wave passes (not just hits), record which GF you ended up at
3. When choosing where to surf, add a penalty proportional to how often you've been at that GF
4. The combined danger = enemy's aiming profile × your visit profile

**The effect:** Your GF distribution becomes flat — no spike for the enemy to exploit. The enemy's VCS converges to a uniform distribution, making every firing angle equally (un)likely.

**Tradeoff:** Flattening can HURT against simple targeters. A stopped bot is safe against linear/circular targeting — but a flattener would avoid staying stopped (because that bin fills up). Top bots like Phoenix activate flattening selectively, based on whether they detect the enemy is learning.

**Data saved between matches:** Some bots (Phoenix) save flattener-enabling decisions between matches — if the enemy was identified as a learner in previous matches, flattening is enabled from round 1 of the next match.

### 5.6 Minimum Risk Movement (Melee Detail)

The dominant melee movement strategy. Two components:

**Risk Function — what makes a point dangerous:**
- Proximity to enemies (weighted by energy, inverse square)
- Being the closest bot to any enemy (likely to be targeted)
- Distance to walls/corners
- Advancing angle toward enemies (perpendicular = safe, head-on = dangerous)
- Staying in the same area too long
- Moving too far in one step (exposed during transit)

**Point Generation — where to consider going:**
- HawkOnFire: points at regular angular offsets around the robot, at random distances
- Tron: 4 points (up, down, left, right) at uniform distance
- FloodHT: divide-and-conquer — split battlefield into 16 rectangles, rate centers, subdivide the best

**Used by:** Diamond, Shadow, HawkOnFire, FloodHT, Tron, Firestarter, Phoenix (in melee mode).

### 5.7 Anti-Gravity Movement (Legacy/Melee)

A potential-field approach borrowed from robotics (called "potential field motion planning" in AI literature):

**Standard implementation:**
```
For each enemy/wall/point:
    force = strength / distance²
    Break into X and Y components
    Sum all force vectors
    Move in the direction of the resultant
```

**Extensions:**
- **WindPoints:** Emit force in one fixed direction regardless of bearing
- **SwivelPoints:** Emit force perpendicular to bearing (orbital push)
- **PulsePoints:** Force pulsates with time (harder to predict)
- **Distance factor tuning:** `1/distance^DF` — set DF=0 for uniform force at any range

**Limitation vs. Minimum Risk:** Anti-gravity movement tends to converge at local minima ("happy spots"). Minimum Risk evaluates discrete candidate points, avoiding this trap. Most top melee bots prefer Minimum Risk.

### 5.8 Random Movement (Competitive)

More sophisticated than "go in a random direction":

**Random destinations:** Calculate escape area (where you CAN be when the bullet arrives), pick a random angle within that escape area that stays perpendicular to the enemy. This ensures you're either always at max escape angle or randomly distributed within your reachable zone.

**Random velocity:** Change velocity every 5-20 ticks (not every tick — acceleration/deceleration limits mean per-tick randomization doesn't actually randomize much). Use range [2, 12] instead of [0, 8] — values above 8 are clamped, creating a bias toward full speed.

**Marshmallow's approach:** Calculate escape area, choose random angle within it, and track which angles bullets have been aimed at (using virtual bullets). Favor GF segments with lower historical frequency — a form of implicit flattening.

---

## Section 6: Advanced Targeting Concepts

### 6.1 GuessFactor Targeting In Detail

The dominant targeting paradigm. Components:

**The GF Array (bins):**
- Typically 31-47 bins spanning GF [-1, +1]
- Middle bin = GF 0 (head-on)
- Each side: (bins-1)/2 bins covering the escape angle range
- Bin index from GF: `index = (gf * (BINS-1)/2) + (BINS-1)/2`

**The Wave Lifecycle:**
1. **Fire:** Create a wave with: source position, bullet speed, direct angle to target, target's lateral direction, segmentation attributes
2. **Travel:** Wave expands at bullet speed each tick. `radius = bulletSpeed * (currentTime - fireTime)`
3. **Break:** When radius ≥ distance to target's current position, the wave "breaks"
4. **Record:** Compute the GF where the target actually was: `gf = bearingOffset / MEA * direction`
5. **Bin smoothing:** Don't just increment the exact bin — smooth: `bins[i] += 1.0 / (pow(hitIndex - i, 2) + 1)`

**Firing decision:**
1. Look up the current segment
2. Find the bin with the highest value
3. Convert to firing angle: `angle = directAngle + peakGF * MEA * direction`

**Rolling averages / decay:** To adapt to changing enemy movement, use rolling averages — only keep the last N data points, or decay old data. Low rolling depth (~20-50 waves) adapts quickly but is noisier. CassiusClay-style bots make rolling depth inversely proportional to distance.

### 6.2 Pattern Matching

Record sequences of enemy velocity and heading changes. When firing, search history for the closest match to the current sequence and "replay" the matched continuation:

**Symbolic Pattern Matching:**
- Encode each tick as a symbol: e.g., velocity quantized to {-8,-4,0,4,8}, heading change to {-10,-5,0,5,10}
- Build a string of symbols from recent history
- Search for the longest match in the full history log
- Project the enemy's future positions by replaying the symbols that followed the match

**Folded Pattern Matching:**
- Mirror-invariant: treat clockwise and counter-clockwise movement as equivalent
- Rotation-invariant: normalize by relative bearing so patterns transfer across positions

**Advantages over GF targeting:**
- Can exploit very specific movement patterns (oscillators, wall-followers)
- Doesn't require segmentation — the pattern IS the context

**Disadvantages:**
- Requires much more data to build history
- Fails against truly random movement
- Memory and CPU intensive for long histories

**Dynamic Clustering (TronsGun/KNN alternative):**
Instead of bins, store every wave situation as a feature vector. At fire time, find K nearest neighbors in situation-space, then use kernel density estimation among those neighbors to choose the firing angle. Used by Shadow, Diamond, DrussGT.

### 6.3 Virtual Bullets / Virtual Guns

**Virtual Bullets:** Simulated bullets that aren't actually fired. Used to test "what if I had aimed this way?"

**Virtual Guns System:**
1. Maintain multiple targeting algorithms (e.g., linear, circular, GF with different segmentations)
2. Each algorithm fires a virtual wave on every opportunity
3. When each virtual wave reaches the target, check if it would have hit
4. Accumulate hit rates per algorithm
5. Use the algorithm with the highest virtual hit rate for actual firing

**Largely superseded by Waves** for data collection — but Virtual Guns for GUN SELECTION remains standard practice. Top bots like Phoenix run multiple GF guns with different parameters and let the VG system choose the best.

### 6.4 Kernel Density Estimation (KDE) for Targeting

Instead of discrete bins (VCS), use a continuous probability distribution:

**Approach:**
1. Store a list of observed GuessFactor values (not binned)
2. At fire time, for each historical GF observation, place a Gaussian kernel centered at that GF
3. Sum all kernels to get a smooth density curve
4. Fire at the peak of the density curve

**Advantages:**
- No bin resolution problem (continuous)
- Natural smoothing without explicit bin smoothing
- Can use variable bandwidth — tighter kernels for nearby situations, broader for far ones

**Used by:** Diamond uses kernel density estimation in its Dynamic Clustering gun. Gilgalad was the first to use variable bandwidth KDE in surfing.

### 6.5 Anti-Surfer Targeting

Detect that the enemy is wave surfing and exploit it:

**Core idea:** A wave surfer moves to the SAFEST position on a wave. If you can predict which position they think is safest, fire where they'll ARRIVE, not where they are.

**Implementation:** Run the enemy's likely surfing algorithm (mirror image of your own movement), predict where they'll dodge to, and aim there.

**Phoenix** pioneered anti-surfer targeting with dedicated Virtual Guns — one general-purpose gun and one anti-surfer gun, with the VG system choosing which performs better.

---

## Section 7: Segmentation

### 7.1 Why Segmentation Matters

A target's movement profile changes drastically based on context. Against the wall, a bot can only escape in one direction — its GF profile shifts. At close range, acceleration matters more; at long range, position matters more. Segmentation separates statistics by context so each segment captures a meaningful, exploitable pattern.

### 7.2 Common Segmentation Dimensions

| Dimension | Description | Why it Matters |
|-----------|-------------|----------------|
| **Distance** | Range to target at fire time | Close = high angular velocity, long = high bullet travel time. GF profiles differ dramatically |
| **Bullet flight time** | `distance / bulletSpeed` | More direct than distance alone — captures power choice too |
| **Lateral velocity** | `velocity * sin(heading - bearing)` | High lateral velocity = likely moving perpendicular = wider GF spread |
| **Advancing velocity** | `velocity * -cos(heading - bearing)` | Positive = closing, negative = retreating. Changes escape geometry |
| **Acceleration** | `velocity - previousVelocity` | Accelerating, constant, or decelerating — each creates different profiles |
| **Time since velocity change** | Ticks since velocity was last 0, or since it last changed | Oscillators and Stop-And-Go have short values; steady movers have long ones |
| **Near wall** | Whether forward/reverse escape is wall-limited | Most impactful segment. Near walls, one GF direction is cut off entirely |
| **Bullet power** | Power of the bullet being fired | Wave surfers weight danger by power — their response varies |
| **Robots remaining** | Number of live bots (melee) | Movement changes as enemies die |

### 7.3 Segmentation for Movement (Surfing)

The SAME segmentation dimensions apply in reverse: when surfing enemy waves, segment your danger stats on the attributes the enemy might use:

- **Key difference:** In targeting, you record where the enemy WAS when the wave broke. In surfing, you record where YOU got hit — and then avoid similar positions in similar situations.
- Surfing segmentation should approximate what the enemy's gun does. If the enemy segments on distance + lateral velocity, your surfing stats should too.
- **Over-segmentation danger:** Too many dimensions with too few data points → sparse data → poor danger estimates. Typical successful configs use 2-4 dimensions with 3-5 bins each.

### 7.4 Advanced Segmentation Techniques

**Dynamic segmentation:** Create different numbers of GF bins for different distance segments. At close range, fewer bins suffice (small angular difference); at long range, more bins capture finer angle resolution.

**Multiple VCS buffers:** Maintain several buffer sets with different segmentation schemes. Sum their values (or weight by recency) when making decisions. This adds robustness — if one scheme has sparse data, others contribute.

**Bin smoothing:** When recording a hit at bin index i, don't just increment bins[i] — spread the increment to neighboring bins with a kernel:
```
bins[j] += 1.0 / (pow(i - j, 2) + 1)
```
This smooths the profile and reduces noise from limited data.

---

## Section 8: Top Bot Design Patterns

### 8.1 DrussGT (by Skilgannon) — #1 RoboRumble

**Movement:** GoTo-style Wave Surfing. Instead of "true surfing" (orbit clockwise/counter-clockwise/stop), it calculates the safest spot on the wave and navigates directly to it. Uses segmented logs simulating Visit Count Stats. Pioneered bullet shielding as an initial strategy (falls back to surfing if it takes damage). Uses bullet shadows and gun heat waves.

**Targeting:** Dynamic Clustering with GuessFactors. KNN search using kd-trees (Rednaxela's implementation) for fast neighbor lookup. Multiple data analysis buffers.

**Key innovations:**
- GoTo surfing (computes optimal destination, not just direction)
- Bullet shadows in danger calculations
- Gun heat waves (tracks WHEN enemy can fire, not just when they do fire)
- Initial bullet shielding mode

### 8.2 Diamond (by Voidious) — #2 1v1, #3 Melee

**1v1 Movement:** Wave Surfing with Dynamic Clustering for danger estimation. Predicts positions for clockwise, counter-clockwise, and stop on the nearest 2 waves. For each predicted spot on wave 1, branches and predicts options on wave 2. Weights wave dangers by time-to-impact and bullet power. Uses bullet shadows (pioneered this technique). Adds distancing factor.

**1v1 Targeting:** Dynamic Clustering (KNN + kernel density estimation). Uses kd-trees for fast lookup. GuessFactors with precise MEA. Precise wave intersection with interpolated scan data.

**Melee Movement:** Minimum Risk Movement — evaluates many candidate points with risk based on distance to enemies, perpendicularity, whether it would be closest to any enemy, energy-weighted threat, and some randomization.

**Melee Targeting:** Displacement vectors + firing from alternate wave sources. Aims at all enemies simultaneously (Shadow's melee gun approach).

**Architecture:** Completely separate 1v1 and melee strategies, switched based on `getOthers()`. Open source (zlib license) — excellent study resource.

### 8.3 Shadow (by ABC) — First Wave Surfer

**Movement:** 1v1: Wave Surfing (invented here in 2004). Melee: Minimum Risk Movement with HOT-avoidance.

**Targeting:** Dynamic Clustering + Play-It-Forward gun (originally called "Tron's Gun"). Pioneered the "melee gun" concept — aim at ALL enemies simultaneously, firing waves from each enemy's perspective.

**Historical significance:** Shadow demonstrated that wave surfing was so effective that it transformed the entire competitive landscape. By 2010, the top 40 1v1 bots all used wave surfing variants.

### 8.4 Gilgalad (by AW) — #5 RoboRumble

**Movement:** Wave Surfing. First bot to use variable bandwidth kernel density estimation in surfing. Uses Precise Positional MEA calculations. Multiple classification schemes for movement data ("strength in numbers").

**Targeting:** First to use Precise Positional MEA in targeting. Custom kd-tree implementation (does not use Rednaxela's).

**Innovation:** Boosting algorithm concepts applied to movement — multiple weak classifiers combined for robust dodging.

### 8.5 Phoenix (by David Alves) — #10 RoboRumble

**Movement:** 1v1: Wave Surfing. Melee: Minimum Risk Movement.

**Targeting:** GuessFactor Targeting + Virtual Guns system with dedicated anti-surfer gun and general-purpose gun. The VG system automatically picks whichever performs better against the current opponent.

**Meta-game:** Saves Virtual Guns scores and flattener-enabling decisions between MATCHES (not just rounds). If a previous match showed the enemy adapts, flattening is enabled from round 1 next time.

### 8.6 Common Architectural Pattern

Nearly all top bots follow this template:

```
1v1 Architecture:
├── Radar: Tight lock (1v1 infinity lock)
├── Movement: Wave Surfing
│   ├── Energy drop detection → create enemy waves
│   ├── Precise prediction → reachable positions on wave
│   ├── Danger estimation (VCS, DC, or KDE)
│   ├── Segmentation (distance, lateral vel, wall proximity, ...)
│   ├── Flattener (optional, adaptive)
│   └── Bullet shadows (optional)
├── Targeting: GF + VCS or Dynamic Clustering
│   ├── Wave-based data collection
│   ├── Segmented stats / KNN search
│   ├── Precise MEA calculation
│   └── Virtual Guns (multiple targeting algorithms)
└── Fire Power: Adaptive based on distance, energy, hit rate

Melee Architecture:
├── Radar: Wide scan (spinning or priority scanning)
├── Movement: Minimum Risk
│   ├── Point generation (angular offsets, grid, random)
│   ├── Risk function (distance, energy, angle, history)
│   └── Wall smoothing
├── Targeting: Displacement Vectors / DC / Shadow's Melee Gun
│   └── Fire at all enemies, choose best opportunity
└── Fire Power: Conservative (preserve energy in N-way fights)
```

---

## Section 9: ML and Data-Relevant Insights

### 9.1 Data Requirements for Replicating Top Strategies

| Strategy | Required Data per Enemy | Data Structure |
|----------|------------------------|----------------|
| GF Targeting (VCS) | ~100-500 wave observations to fill segments | Multi-dimensional int/double arrays, ~10KB |
| Dynamic Clustering (KNN) | 500-5000 situation vectors | kd-tree of float vectors, ~50-200KB |
| Pattern Matching | Full movement history (thousands of ticks) | Circular buffer of velocity/heading deltas, ~10-50KB |
| Wave Surfing stats | ~50-200 bullet observations (hit/miss) | Segmented GF bins, ~5-20KB |
| Flattener profile | Every wave pass (hundreds per round) | Self-shadow VCS bins, ~2-5KB |

**Cold start problem:** In the first few rounds, there's very little data. Top bots use:
- Rolling averages with fast decay (20-50 wave depth)
- Multiple VCS buffers of different granularity (fine segments + coarse fallback)
- Unsegmented fallback for the first ~30 waves, then switch to segmented

### 9.2 What ML Could Do Better Than Hand-Crafted

| Capability | Hand-Crafted | ML Potential |
|-----------|--------------|-------------|
| **Segmentation selection** | Author picks 3-5 dimensions, fixed bin sizes | Learn optimal dimensions and resolution automatically |
| **Cross-battle learning** | Limited by 200KB disk storage per bot | Could pre-train on thousands of battles offline |
| **High-dimensional patterns** | KNN with ~5-10 features | Neural nets could handle 20+ features without curse of dimensionality |
| **Opponent classification** | Virtual Guns (pick from N strategies) | Cluster opponents into archetypes, transfer knowledge instantly |
| **Adaptive segmentation** | Fixed segments chosen at design time | Online feature selection — add/remove dimensions based on observed utility |
| **Movement optimization** | Surf to lowest-danger GF (greedy) | RL could optimize multi-wave trajectories globally |
| **Power selection** | Simple heuristics (distance-based) | Optimal power as function of full game state (energies, hit rates, time) |

### 9.3 Key Features for an ML Model

**For targeting (predicting enemy GF):**
- Distance to target (continuous)
- Lateral velocity (continuous, signed)
- Advancing velocity (continuous, signed)
- Acceleration state (categorical: accel/decel/constant)
- Time since velocity change (integer)
- Time since direction change (integer)
- Forward wall distance (continuous)
- Reverse wall distance (continuous)
- Bullet flight time (continuous)
- Enemy heading relative to bearing (continuous)
- Previous GF outcomes at similar situations (historical hit distribution)

**For movement (predicting enemy aim):**
- Same features as above, but computed for SELF relative to enemy
- Enemy's observed GF distribution from past waves
- Enemy's current gun heat (estimated)
- Our velocity and heading at wave fire time
- Number of ticks since wave was fired

### 9.4 Challenges for ML in Robocode

1. **Tiny data budgets:** A 35-round battle generates ~35 × 200 = 7000 ticks of data per opponent. Each round gives maybe 10-15 waves. ML models must learn from dozens, not millions, of examples.

2. **Non-stationarity:** The opponent adapts too. A learned model of round 1 may not predict round 10. Rolling windows and rapid adaptation are essential.

3. **Deterministic physics:** The game physics are perfectly known. There's no sensor noise or world uncertainty — only opponent policy uncertainty. This makes simulation-based approaches (like the precise prediction used in surfing) extremely powerful and hard to beat with black-box ML.

4. **CPU constraints:** Each tick has a time limit. Complex neural network inference may be too slow for real-time decisions. kd-trees and simple array lookups are fast enough; deep models might not be.

5. **Codesize limits (for restricted categories):** MiniBot (1500 bytes), MicroBot (750 bytes), NanoBot (250 bytes) — these categories force extreme compression. ML models have trouble fitting.

6. **The "flat movement" ceiling:** If your movement is perfectly flat (uniform GF distribution), no statistical targeting can do better than random. This theoretical limit means the marginal value of better ML movement decreases as you approach it.

### 9.5 Promising ML Approaches

- **Offline pre-training:** Train targeting and movement models on thousands of recorded battles, then load learned weights at battle start. Requires file I/O (200KB limit per saved file).
- **Opponent fingerprinting:** Use the first 10-20 waves to classify the opponent into a known archetype, then load pre-computed strategy parameters for that archetype.
- **Neural GF estimation:** Replace VCS bins with a small neural network that maps (situation features) → GF probability distribution. Could generalize better from few examples.
- **Reinforcement learning for movement:** Train a policy that maps (wave state, position, velocity) → movement command. Could learn multi-wave optimization that greedy surfing misses.
- **Ensemble of simple models:** Multiple decision trees or linear models, each specialized to a segment, combined via a voting or averaging scheme — closer to how top bots already work (multiple VCS buffers + Virtual Guns).

## Appendix: Key Numbers Quick Reference

| Constant | Value |
|----------|-------|
| Max velocity | 8 px/turn |
| Acceleration | +1 px/turn² |
| Deceleration | −2 px/turn² |
| Max body turn (stopped) | 10°/turn |
| Max body turn (full speed) | 4°/turn |
| Gun turn rate | 20°/turn |
| Radar turn rate | 45°/turn |
| Radar scan radius | 1200 px |
| Robot size | 36 × 36 px |
| Min bullet power | 0.1 |
| Max bullet power | 3.0 |
| Bullet speed | 20 − 3×power |
| Bullet damage (power ≤ 1) | 4 × power |
| Bullet damage (power > 1) | 6 × power − 2 |
| Hit energy return | 3 × power |
| Gun heat on fire | 1 + power/5 |
| Default gun cooling rate | 0.1/turn |
| Initial gun heat | 3.0 |
| Initial energy | 100 |
| Wall hit damage | max(|v|/2 − 1, 0) |
| Robot collision damage | 0.6 each |
| Ram bonus | 1.2 (score points) |
| Inactivity zap | 0.1/turn |
| Default inactivity time | 450 ticks |
| Default battlefield | 800 × 600 |

---

*Sources: Robocode source code (robocode.api `Rules.java`, robocode.battle `RobotPeer.java`, `BulletPeer.java`, `Battle.java`), RoboWiki (robowiki.net), RoboWiki game physics page. All formulas verified against source code.*

---

## Section 5: How Top Bots Actually Work

Research from RoboWiki bot pages, sub-pages (Understanding DrussGT, DookiLightning, DookiCape, Gilgalad/movementStrategy, Gilgalad/targetingStrategy), technique pages (Segmentation, Dynamic Clustering, Bin Smoothing, Play It Forward, Wave Surfing, Bullet Shadow, Shadow/Melee Gun, Visit Count Stats). Where specifics aren't publicly documented, that is noted.

### 5.1 DrussGT (by Skilgannon) — #1 in RoboRumble since 2008

**Architecture:** KNN Dynamic Clustering gun + GoTo Wave Surfing movement. Open source. Extremely detailed architecture documented at [DrussGT/Understanding DrussGT](https://robowiki.net/wiki/DrussGT/Understanding_DrussGT).

**Movement — GoTo Wave Surfing:**

DrussGT uses a GoTo-style surfer (as opposed to True Surfing): it generates candidate destination points via precise prediction, then simulates navigating to each one to calculate where the enemy wave would intersect the robot on that path. This is more computationally expensive but gives more movement options.

Key innovations:
- **100+ parallel VCS buffers** with randomly generated attribute/slice combinations (plus 50 flattener buffers and 20 tick-flattener buffers). Each buffer uses only 5 of the available attributes. Some hand-tuned low-attribute buffers are added as fallbacks.
- **171 bins** per buffer — very high resolution.
- **Very low rolling averages (0.5–1.5)** — stats adapt almost instantly to enemy targeting changes. Only the last 2–3 hits in each segment have meaningful influence.
- **Gun heat waves** — begins surfing a predicted wave 2 ticks before detecting an energy drop, using estimated enemy bullet power. This buys crucial reaction time at close range.
- **Three flattener layers:** (1) standard flattener logging every wave pass, (2) tick-flattener generating a wave every single tick, (3) anti-bullet-shadow flattener adding danger where enemy likely exploited bullet shadows. All disabled until enemy hitrate exceeds 9%.
- **Bullet shadows** — standard implementation, sets danger to 0 in wave regions where own bullets will intercept enemy bullets.
- **Second-wave speed optimization** — sorts first-wave dangers lowest-to-highest, then skips second-wave computation once first-wave danger already exceeds the current best total.

**Movement surfing attributes (the segmentation dimensions for movement stats):**

| # | Attribute | Notes |
|---|-----------|-------|
| 1 | Lateral Velocity | Velocity perpendicular to bearing to enemy |
| 2 | Advancing Velocity | Velocity towards/away from enemy |
| 3 | Bullet Flight Time | Distance / bullet speed |
| 4 | Time Since Direction Change | Ticks since lateral direction reversed |
| 5 | Time Since Deceleration | Ticks since velocity decreased |
| 6 | Acceleration | Current acceleration state |
| 7 | Lateral Distance Last 10 Ticks | abs(sum of lateral velocity for previous 10 ticks) |
| 8 | Forward Wall Distance | Distance to wall in current movement direction |
| 9 | Reverse Wall Distance | Distance to wall in reverse direction |

Each buffer randomly selects 5 of these 9, with random slice granularity ({empty, coarse, regular, fine}) per attribute. Slices are pre-selected at compile time so every runtime instance behaves identically.

**Targeting — KNN Dynamic Clustering with GuessFactors:**

Two separately tuned KNN guns compete via Virtual Guns:
- **Anti-static gun** — low decay rate, for non-adaptive enemies
- **Anti-adaptive gun** — high decay rate, for wave surfers
- **Random fallback gun** — fires between min and max precise GF

**Gun attributes (KNN dimensions):**

| # | Attribute | Normalization |
|---|-----------|---------------|
| 1 | Lateral Velocity | |
| 2 | Advancing Velocity | |
| 3 | Distance | |
| 4 | Acceleration | Direction = acceleration direction, not movement direction |
| 5 | Time Since Direction Change | f(x) = 1/(1+K*x), since unbounded values need normalization |
| 6 | Time Since Deceleration | f(x) = 1/(1+K*x) |
| 7 | Distance Last 10 Ticks | Cumulative lateral displacement over 10 ticks |
| 8 | Forward Wall Distance | Calculated using precise max GF prediction |
| 9 | Reverse Wall Distance | Calculated using precise min GF prediction |
| 10 | Current GF | The GF position enemy is currently at in our targeting framework |
| 11 | Expected Mirror Rotation at Bullet Hit Time | Predicts how enemy orbit angle will change |
| 12 | Number of Bullets Shot | Normalized with fast early growth, slow later growth |

KD-Tree: Custom implementation (Skilgannon's KDTree) with memory management optimized for cache locality. Distance metric: **Manhattan distance** — found to significantly outperform Euclidean in practice (author notes this may be tied to attribute scaling choices).

KDE: Gaussian-rolloff weighting, with 50% weight normalization set at the average distance of the first P neighbors. Uses precise intersection data — each neighbor contributes a range (min GF, max GF) rather than a single point. The GF range overlaps are integrated to find the densest region. GuessFactor scaling uses precise positional MEA rather than the standard asin(8/bulletVel).

**Bullet power:** Complex algorithm. Base power 1.95 (or 2.95 near/high-hitrate). Scaled by own energy vs. projected enemy energy. Attempts to undercut enemy bullet power. Rounded to exploit a bug in BasicSurfer-derived bots.

**Enemy bullet power prediction:** A separate KD-Tree on {enemyEnergy, myEnergy, distance} with KDE. Used for gun heat waves and movement planning.

---

### 5.2 Diamond (by Voidious) — #2 in 1v1, former #1 Melee

**Architecture:** DC-KDE gun + DC Wave Surfing movement. Open source (zlib license, [GitHub](https://github.com/Voidious/Diamond)). Uses kd-trees extensively (Rednaxela's implementation).

**Movement:**

*1v1 — Dynamic Clustering Wave Surfing (True Surfing style):*
- Predicts three movement options per wave: orbit clockwise, orbit counter-clockwise, stop (slam brakes).
- For each predicted intersection point on the first wave, branches and predicts CW/CCW/stop on the second wave. Takes the minimum second-wave danger.
- Weights wave dangers by time-to-impact and bullet power.
- Multiplies in a distancing factor.
- Chooses the safest of the 3 first-wave options.
- **Pioneered using Bullet Shadows** in wave surfing — marks wave regions where own bullets will intercept enemy bullets as safe.

*Melee — Minimum Risk Movement:*
- Evaluates candidate points around itself.
- Factors: distance to enemies, perpendicularity, avoiding being closest to any enemy, energy-weighted risk, randomization from past locations.

**Targeting:**

*1v1:*
- **Dynamic Clustering (kNN) with Kernel Density Estimation** — not traditional VCS bins.
- Stores firing data as feature vectors in kd-trees. At fire time, finds K nearest neighbors and applies KDE to find the peak firing angle.
- Uses **GuessFactors with precise MEA** (per-situation via precise prediction).
- Uses **precise intersection** and interpolates missed scans.
- Virtual Guns system selects between targeting modes.

*Melee:*
- Uses **Displacement Vectors** (not GuessFactors) — makes more sense because enemies aren't necessarily moving relative to the firing bot.
- **Adopted Shadow's Melee Gun concept:** computes firing solutions for ALL enemies simultaneously, fires at the angle most likely to hit someone somewhere on the field. Fires waves from alternate source positions.

**Segmentation dimensions:** Diamond's exact kNN dimensions are not individually listed on the wiki page. Diamond's code (open source on GitHub) would reveal them. Given that DrussGT was partly inspired by Diamond and Gilgalad's weights are "based on Diamond," we can infer similar core dimensions: lateral velocity, advancing velocity, distance, acceleration, wall distance, time since velocity change. However, since Diamond uses DC/kNN rather than segmented VCS, these are continuous feature dimensions rather than discrete segment slices.

**Between rounds:** Saves all targeting and movement data (kd-trees + displacement vector maps). Between matches: nothing.

---

### 5.3 Dookious (by Voidious) — former #1 (Apr 2006 – Mar 2008), now ~#9

**Architecture:** Two-gun VirtualGuns GF targeting + True Wave Surfing movement. Open source (RWPCL).

**Movement — DookiCape (True Wave Surfing):**
- Uses Precise Prediction (Albert's FuturePosition code).
- **Two-wave recursive surfing:** For the first (closest) wave, tests danger at precise intersection points for CW orbit, CCW orbit, and stop. For each of those, makes a recursive call to find danger on the second wave. Takes minimum second-wave danger, adds to first-wave danger, weighted by wave distance and distancing factors.
- Originally a quick hack that only tested max GF, min GF, and stop GF on the second wave — worked consistently better than checking the full range, for reasons the author "cannot explain."

**Targeting — DookiLightning (Segmented GuessFactor VCS + Virtual Guns):**

Two guns compete via VirtualGuns with RollingAverage scores (depth ~20 rounds):

| Gun | Purpose | Non-firing wave weight | Rolling depth | Notes |
|-----|---------|----------------------|---------------|-------|
| Main Gun | General RoboRumble opponents | 1/5 | Very high (no effective decay) | Designed for non-adaptive movement |
| AntiSurfer Gun | Wave surfers / adaptive movement | 1/10 | Very low (currently 1) | Logs negative hits on bullet-hit to simulate enemy adaptation |

Selection: bias against the AntiSurfer gun unless the Main Gun's rating is below a threshold.

**Segmentation:** DookiLightning's exact segment dimensions are not individually listed on the wiki. The author credits CassiusClay's Bee gun for teaching him segmentation methods, "especially wall distance." From context and the general Segmentation page, the gun likely segments on some subset of: distance, lateral velocity, advancing velocity, acceleration, wall proximity, time since velocity change, bullet flight time.

**Between matches:** Saves best GF bin for all well-visited segments (SuperNodes — a technique for inter-match learning).

---

### 5.4 Shadow (by ABC) — Inventor of Wave Surfing, first DC-PIF bot

**Architecture:** DC-PIF gun + Wave Surfing movement. Closed source. Historically legendary — introduced two of the most important techniques in Robocode history.

**Movement:**
- **First robot to implement Wave Surfing** (mid-2004). All modern top bots descend from this innovation.
- 1v1: Wave Surfing (details not public since closed source).
- Melee: Minimum Risk Movement with HOT-avoidance system (v3.84).

**Targeting — Dynamic Clustering + Play-It-Forward:**
- Originally described as "Tron's Gun" (a "forward pattern matcher").
- Records enemy states (heading, velocity, position). Finds similar past states via kNN. Replays matched future movements forward in time to predict where the enemy will be.
- PIF differs from pure GuessFactor in that it reconstructs actual predicted positions rather than mapping to a normalized GF bin.
- **Melee innovation (Shadow/Melee Gun):** Computes firing solutions for ALL enemies simultaneously. Selects the angle with the highest probability of hitting anyone, weighted by inverse distance. Diamond adopted this concept.

**Segmentation dimensions:** Not publicly documented (closed source). The DC-PIF approach uses situation-state vectors for kNN lookup rather than discrete segmentation bins. The wiki notes the common DC dimensions are lateral velocity, advancing velocity, and distance, but Shadow's specific dimensions are unknown.

---

### 5.5 Gilgalad (by AW) — #5, best bot without Rednaxela's kD-Tree

**Architecture:** Two KNN-GF guns + KNN GoTo Wave Surfing. Open source (FreeBSD license).

**Movement — KNN GoTo Wave Surfing:**
- Similar to DrussGT's GoTo surfing: generates candidate paths via precise prediction, evaluates danger using kNN algorithm.
- **14 classification schemes** (kNN feature sets) running in parallel. Various schemes are enabled at certain hit rates.
- **First robot to use variable bandwidth** in surfing — the KDE bandwidth adapts based on data density.
- Uses `atan()` (integral of 1/(x²+1)) for danger calculations — allows precise analytical integration, easily adapted for bullet shadows.
- Bullet Shadows: yes.
- Falls back to Minimum Risk Movement when no waves are active.
- GoTo surfing: most computation happens in one turn (risk of skipped turns), mitigated by limiting second-wave evaluation to the 20 best first-wave points.

**Targeting — Two KNN-GF guns:**
- Weights and code structure based on Diamond.
- **Precise Positional MEA** — a new type of Maximum Escape Angle calculation (Gilgalad was the first to use this). Instead of using the formula asin(8/bulletVel), computes the actual max escape position considering the specific battlefield geometry and robot state.
- Four wall dimensions (replacing imprecise MEA with four directional wall measurements).
- Handles virtual waves exactly (v1.99.4): after a real wave fires, adjusts virtuality and bullet power for virtual waves using weighted averages of surrounding real waves.

**Segmentation dimensions:** Not individually listed on the wiki. The targeting strategy page mentions 4 wall dimensions and that "many weights" are based on Diamond. Movement uses 14 classification schemes (feature sets) — specifics not documented.

---

### 5.6 Phoenix (by David Alves) — #10, closed source

**Architecture:** GF gun with Virtual Guns + Wave Surfing movement. Closed source (older version released as PhoenixOS).

**Movement:** Wave Surfing (1v1), Minimum Risk Movement (melee).

**Targeting:**
- GuessFactor Targeting with Virtual Guns.
- Anti-Surfer gun + general purpose gun.
- Saves Virtual Guns scores and flattener-enabling decisions between matches.

**Segmentation dimensions:** Not publicly documented (closed source).

---

### 5.7 Komarious (by Voidious) — #2 MiniBot, top-50 general

**Architecture:** GF targeting + Wave Surfing, squeezed into MiniBot codesize limits. Open source (RWPCL).

**Movement:** Wave Surfing with Precise Prediction (from Apollon). Segmented on:
- Lateral velocity
- Distance

**Targeting:** GuessFactor Targeting (evolved from RaikoMicro gun). The MiniBot constraint limits the number of possible segments.

**Notes:** The [Wave Surfing Tutorial](https://robowiki.net/wiki/Wave_Surfing_Tutorial) on RoboWiki was based on the author's experience building Komarious. This bot demonstrates that even with minimal segments (just lateral velocity + distance), wave surfing is highly effective.

---

## Section 6: Common Segmentation Dimensions Across Top Bots

### 6.1 Dimension Comparison Table

What each top bot segments/clusters on, based on publicly available information. "✓" = confirmed on wiki, "~" = likely based on architecture/credits but not individually documented, "?" = unknown (closed source or undocumented), "—" = not used or N/A.

**Gun (Targeting) Dimensions:**

| Dimension | DrussGT | Diamond | Dookious | Shadow | Gilgalad | Phoenix | Komarious |
|-----------|---------|---------|----------|--------|----------|---------|-----------|
| Lateral Velocity | ✓ | ~ | ~ | ~ | ~ | ? | ✓ (implied via GF) |
| Advancing Velocity | ✓ | ~ | ~ | ~ | ~ | ? | — |
| Distance | ✓ | ~ | ~ | ~ | ~ | ? | ✓ |
| Acceleration | ✓ | ~ | ~ | ~ | ~ | ? | — |
| Time Since Direction Change | ✓ | ~ | ~ | ? | ~ | ? | — |
| Time Since Deceleration | ✓ | ~ | ~ | ? | ~ | ? | — |
| Lateral Dist Last N Ticks | ✓ | ~ | ? | ? | ? | ? | — |
| Forward Wall Distance | ✓ | ~ | ~ | ? | ✓ (4 walls) | ? | — |
| Reverse Wall Distance | ✓ | ~ | ~ | ? | ✓ (4 walls) | ? | — |
| Current GF Position | ✓ | ? | ? | ? | ? | ? | — |
| Mirror Rotation at Hit Time | ✓ | ? | ? | ? | ? | ? | — |
| Bullets Shot (round progress) | ✓ | ? | ? | ? | ? | ? | — |
| Bullet Power / BFT | ~ | ~ | ~ | ? | ~ | ? | — |

**Movement (Surfing Stats) Dimensions:**

| Dimension | DrussGT | Diamond | Dookious | Shadow | Gilgalad | Phoenix |
|-----------|---------|---------|----------|--------|----------|---------|
| Lateral Velocity | ✓ | ~ | ~ | ? | ~ | ? |
| Advancing Velocity | ✓ | ~ | ~ | ? | ~ | ? |
| Bullet Flight Time | ✓ | ~ | ~ | ? | ~ | ? |
| Time Since Direction Change | ✓ | ~ | ~ | ? | ~ | ? |
| Time Since Deceleration | ✓ | ~ | ~ | ? | ~ | ? |
| Acceleration | ✓ | ~ | ~ | ? | ~ | ? |
| Lateral Dist Last 10 Ticks | ✓ | ? | ? | ? | ? | ? |
| Forward Wall Distance | ✓ | ~ | ~ | ? | ~ | ? |
| Reverse Wall Distance | ✓ | ~ | ~ | ? | ~ | ? |

### 6.2 Universal Dimensions (appear across virtually all documented top bots)

These are the **consensus "must-have" features** that every serious Robocode targeting or movement system uses in some form:

1. **Lateral Velocity** — The single most important targeting dimension. It tells you how fast the enemy is moving perpendicular to your line of fire. Every GF gun and every surfer uses this.
2. **Distance** — Longer distances mean more time for the enemy to change course, and the GF range narrows or widens. Always present.
3. **Wall Distance (forward and/or reverse)** — Near-wall enemies have constrained movement options, producing predictable GF spikes. The Segmentation page calls this "probably the most useful" dimension. DrussGT uses precise-prediction-based wall distance; Gilgalad uses 4 directional wall measurements.
4. **Acceleration / Velocity Change** — Whether the enemy is speeding up, constant, or decelerating. Critical for detecting stop-and-go and oscillator movements.

### 6.3 High-Value Dimensions (used by the best bots, confirmed to matter)

5. **Advancing Velocity** — Velocity towards/away from you. Matters for head-on and retreat-style movements.
6. **Time Since Direction Change** — Ticks since lateral direction reversed. Captures oscillation timing.
7. **Time Since Deceleration** — Ticks since velocity decreased. Captures stop-and-go patterns.
8. **Bullet Flight Time** — Distance / bullet speed. Separate from raw distance because different bullet powers change the time horizon.

### 6.4 Advanced / Unique Dimensions (used by DrussGT specifically, pushing the envelope)

9. **Lateral Distance Last N Ticks** — Cumulative lateral displacement over recent history (DrussGT uses 10 ticks). Captures how much ground the enemy has actually covered laterally, rather than instantaneous velocity.
10. **Current GF Position** — Where the enemy currently sits in GF-space relative to your targeting. A meta-feature: it segments targeting based on YOUR aiming history at THEM (useful for anti-surfer targeting).
11. **Expected Mirror Rotation at Bullet Hit Time** — Predicts orbital angle change. A forward-looking feature.
12. **Number of Bullets Shot** — A proxy for round progress and data maturity. Normalized with fast early growth, slow later growth, so the gun can behave differently in early vs. late round.

### 6.5 Common Segmentation Dimensions from the RoboWiki Segmentation Page

The RoboWiki [Segmentation](https://robowiki.net/wiki/Segmentation) page lists these general dimensions:

| RoboWiki Dimension | Used by Top Bots? | Notes |
|--------------------|-------------------|-------|
| GuessFactor (VCS bins) | All VCS-based bots | The target variable, not a feature |
| Distance | ✓ Universal | |
| Bullet Flight Time | ✓ High-value | Correlated with distance but power-dependent |
| Bullet Power | ~ Some bots | Useful against surfers that weight danger by power |
| Target Velocity | ✓ Universal (as lateral/advancing) | |
| Target Heading (change) | ✓ Via acceleration dimension | |
| Target Lateral Velocity | ✓ Universal | |
| Target Advancing Velocity | ✓ High-value | |
| Target Acceleration | ✓ Universal | |
| Near Wall | ✓ Universal | Implementations vary — simple vs. precise |
| Move Times (time since vel=0, since vel changed, since vel=max) | ✓ High-value | DrussGT: time-since-direction-change, time-since-decel |
| Wave Type (real vs. virtual bullet) | ~ Some bots | Dookious weights non-firing waves differently |
| Antigravity Force (melee) | Melee-only | |
| Robots Remaining (melee) | Melee-only | |

---

## Section 7: Implications for ML Feature Engineering

### 7.1 Features We MUST Include (Consensus from Top Bots)

Any ML-based targeting or movement system should at minimum include the features that 20 years of competitive hand-tuning have proven essential:

| Feature | Why It Matters |
|---------|---------------|
| Lateral velocity | THE primary predictor of GF offset. Every bot uses it. |
| Distance to target | Controls bullet flight time, escape angle range, prediction uncertainty. |
| Forward wall distance | Constrains forward escape options. Produces strong GF distribution spikes. |
| Reverse wall distance | Constrains backward escape options. Mirror of forward wall distance. |
| Acceleration state | Distinguishes accelerating/decelerating/constant — critical for stop-and-go detection. |
| Time since direction change | Captures oscillation timing and wave-surfing rhythm. |
| Time since deceleration | Captures braking patterns, stop-and-go. |
| Advancing velocity | Distinguishes retreating from advancing movement — different GF distributions. |
| Bullet flight time | Distance normalized by bullet speed. Different from raw distance because power varies. |

### 7.2 Features We Should Strongly Consider (Used by #1 Bot)

DrussGT's gun dimensions that go beyond the basics — these are the ones that pushed it to #1:

| Feature | Rationale |
|---------|-----------|
| Lateral displacement over last N ticks | Captures actual traveled distance, not just instantaneous velocity. Smoother signal. |
| Current GF position | A meta-feature — what GF the enemy is currently at. Useful for detecting surfers who dodge to specific GFs. |
| Number of bullets fired (round progress) | The enemy's behavior often changes as the round progresses. Bots may be more random early vs. predictable late, or vice versa. |
| Expected orbital angle change at hit time | Forward-looking rotational prediction. |
| Enemy energy | Both DrussGT's bullet power prediction and the Segmentation page suggest energy matters. |
| Our energy | Affects our ideal bullet power, which affects bullet speed, which changes the whole prediction horizon. |

### 7.3 What ML Could Discover That Hand-Crafted Segmentation Cannot

This is where an ML approach has a genuine structural advantage over hand-tuned VCS and even kNN:

1. **Non-linear feature interactions.** VCS segments dimensions independently (each bin boundary is an axis-aligned cut). Even kNN weights dimensions linearly in its distance metric. A neural network can learn that *low lateral velocity + near wall + recently decelerated* together predict a specific escape pattern that none of those dimensions alone reveal. DrussGT works around this with 100+ random buffer combinations, but ML could find the optimal combinations automatically.

2. **Continuous dimensions without arbitrary binning.** VCS must discretize every dimension into bins, and the bin boundaries are critical tuning parameters (DrussGT uses {empty, coarse, regular, fine} slices per attribute). ML operates on continuous inputs natively.

3. **Temporal patterns.** Top bots summarize time with derived features (time-since-X, distance-last-10-ticks). An LSTM or transformer could directly process the last N ticks of raw movement data and learn whatever temporal patterns matter, including ones nobody has thought to hand-engineer.

4. **Cross-opponent generalization.** Every top bot starts from zero each match (or saves minimal inter-match data like SuperNodes). An ML model trained on thousands of opponents could have a strong prior about movement archetypes from tick 1, rather than needing 30+ rounds to build statistics.

5. **The normalization problem.** DrussGT's author explicitly notes that attribute normalization functions (like 1/(1+Kx) for time-since dimensions) were found via genetic algorithms. ML can learn optimal feature transforms end-to-end.

6. **Adaptive feature weighting.** DrussGT uses genetically-optimized fixed weights for its kNN distance metric. An attention-based model could dynamically weight features based on the current game state — weighting wall distance heavily when the enemy IS near a wall, and ignoring it when they're in open field.

### 7.4 What ML Probably Cannot Beat (Honest Assessment)

1. **Precise prediction physics.** Wave surfing and precise MEA calculations are deterministic simulation of game physics. No amount of ML training will improve on an exact physics simulation. These should remain hard-coded.

2. **Bullet shadow computation.** Another purely physics-derived calculation. ML won't improve on exact geometry.

3. **Data efficiency.** DrussGT's 100+ buffers with rolling average of 1.0 effectively "learn" from just 2-3 observations per segment. With only 35 rounds × ~200 ticks × ~0.5 scan rate ≈ 3,500 observations per match, ML training data is extremely limited. Overfitting is the central risk.

4. **The 9-attribute problem is already "solved."** DrussGT's 9 movement attributes and 12 gun attributes, tuned by genetic algorithms, represent a nearly exhaustive search of the useful feature space for single-tick state. ML's advantage is in temporal features and cross-opponent transfer, not in finding new single-tick features.

### 7.5 Key Architectural Insight

The top bots converge on a common architecture:

```
Input: Per-tick enemy state observations
   ↓
Feature Engineering: Derive ~10 dimensions from raw state
   ↓
Similarity Search: Find past situations most like the current one
   (VCS bins = axis-aligned partitioning, KNN = distance-based lookup)
   ↓
Outcome Reconstruction: From matched past situations, predict where enemy will be
   (VCS = peak bin, KNN+KDE = density estimation, PIF = replay forward)
   ↓
Fire at predicted position
```

This is fundamentally a **nearest-neighbor regression** problem. The entire art of competitive Robocode lies in:
1. What features define "similar situations" (the dimensions)
2. How to weight and normalize those features (the distance metric)
3. How to combine matched outcomes into a firing angle (KDE, bin smoothing, PIF)
4. How to decay/forget old data (rolling averages)

All four of these are things ML can learn. The question is whether ~3,500 in-match data points are enough, or whether cross-match transfer learning can compensate.

---

*Sources: robowiki.net — pages for DrussGT, DrussGT/Understanding DrussGT, Diamond, Dookious, Dookious/DookiLightning, Dookious/DookiCape, Shadow, Shadow/Melee Gun, Gilgalad, Gilgalad/movementStrategy, Gilgalad/targetingStrategy, Phoenix, Komarious, Segmentation, Dynamic Clustering, Play It Forward, Wave Surfing, Bullet Shadow, GuessFactor Targeting, Visit Count Stats, Bin Smoothing. All claims attributed to their wiki sources. Where information is unavailable (closed source bots, undocumented details), this is explicitly flagged.*

---

## Section 5: Machine Learning in Robocode — Prior Art and Opportunities

### 5.1 What's Been Tried

Machine learning in Robocode has a 20+ year history, mostly concentrated in **neural network targeting**. Despite persistent effort, ML bots have never reached the very top of the rankings. The RoboWiki categorizes neural targeting under "Heuristics" — alongside fuzzy logic — rather than among the dominant "Statistical" or "Log-Based" methods.

#### Neural Network Targeting — Timeline

| Year | Bot | Author | Approach | Peak Rating | Notes |
|------|-----|--------|----------|-------------|-------|
| 2002 | XBot | Qohnil | NN (details unknown) | — | First known NN bot. Little documentation survives. |
| 2003 | ScruchiPu | Albert | NN predicting enemy speed + turn rate, iterating one tick at a time | — | Essentially pattern matching via neural net. Sparked RoboWiki interest in NNs. |
| 2006 | **Engineer** | Wcsv | **Self-Organizing Map** (SOM) for both targeting and wave surfing | ~2030 (rank 39) | First NN bot to break 2000. Used waves + GuessFactors as training signal. SOM replaced traditional array-based stat collection. |
| 2007 | "Prototype" | Chase-san | NN gun attached to DrussGT movement | ~2005 | Demonstrated that good movement can carry a mediocre NN gun to respectability. |
| 2008–2009 | **Gaff** | Darkcanuck | **Dual multi-layer perceptrons** with Radial Basis Functions, 61 GF outputs each | ~1689 (rank 95) | Most sophisticated NN implementation. Best anti-surfer gun of any kind per challenge results. Two networks trained differently: one for rapid adaptation (anti-surfer), one for general learning (anti-random). 333 iterations tested. |

**Gaff's architecture is worth studying in detail** (see [robowiki.net/wiki/Gaff/Targeting](https://robowiki.net/wiki/Gaff/Targeting)):
- Two MLPs with no hidden layers — Darkcanuck found hidden layers "only slowed down learning without adding significant improvement"
- 61 outputs representing GuessFactor probabilities (classification, not regression — this dramatically improved performance)
- Radial Basis Function encoding of inputs: lateral velocity, bullet flight time, acceleration, approach velocity, distance traveled, ticks since velocity/direction change, wall proximity, current GF
- Network #1: high learning rate, encourages catastrophic interference — learns the enemy's *current* movement fast (anti-surfer)
- Network #2: 200-wave replay buffer, random sampling — learns general movement patterns (anti-random)
- Final aim: sum both networks' outputs, fire at best combined GF

#### Dynamic Clustering — ML in Disguise

The most successful "ML-adjacent" technique in competitive Robocode is **Dynamic Clustering**, which is really just **K-Nearest-Neighbors** under a misnomer that stuck. Used by top bots including Shadow, Tron, and DrussGT.

How it works: store each observed enemy situation as a feature vector (lateral velocity, distance, acceleration, wall proximity, etc.) paired with the resulting GuessFactor. At fire time, find the K nearest neighbors to the current situation, use their GFs to aim.

This is essentially **instance-based learning** — no parametric model, just a growing database of experience. It works well because:
- No training phase or hyperparameter tuning beyond distance weights
- Naturally adapts as more data arrives
- KD-trees make lookup fast (O(log n))
- No catastrophic forgetting

**Critical insight:** Dynamic Clustering works *within a single battle*. Each match starts from scratch. This is the gap that cross-battle ML could exploit.

#### Genetic Algorithms and Reinforcement Learning

The RoboWiki pages for `Genetic_Algorithm` and `Reinforcement_Learning` return 404 — these approaches apparently never gained enough traction to warrant dedicated wiki pages. A "Temporal Difference Learning" project was linked from the Neural Targeting page (now a dead geocities link), and an `EANN` (Evolving Neural Network using Genetic Algorithm) library was referenced but its wiki page is a redlink.

**Speculation:** GAs and RL are better suited to offline optimization (parameter tuning, strategy evolution) than to online in-battle adaptation. The Robocode community gravitated to methods that learn *during* a match, where GAs are too slow and RL's exploration cost is too high (every bad action costs real energy).

### 5.2 Why ML Bots Haven't Dominated

The competitive hierarchy tells the story. Top-10 bots (DrussGT, Diamond, Shadow, etc.) all use **hand-crafted statistical methods** — segmented GuessFactor targeting and wave surfing. The best NN bot (Engineer) peaked at rank 39. Why?

**1. The cold start problem is brutal.** A battle is typically 10 rounds × ~1000 ticks. That's only ~10,000 data points total, with maybe 100–300 waves completing. Neural networks need more data to converge than histogram-based methods. Segmented VCS starts being useful after just 5–10 wave observations per segment.

**2. Dimensional curse vs. human intuition.** The best human bot authors already know which variables matter (lateral velocity, bullet flight time, acceleration, wall proximity, time since direction change). They manually segment on these known-important dimensions with hand-tuned bin sizes. An ML system has to *discover* this structure from data — a harder problem, solved less efficiently in 10 rounds.

**3. Non-stationarity.** Opponents change behavior when hit (wave surfers adapt). A targeting model that was accurate 3 rounds ago may be wrong now. Hand-crafted systems handle this with explicit decay rates and anti-surfer modes. NNs need careful architecture to forget fast enough (Gaff's Network #1 deliberately encourages catastrophic interference for this reason).

**4. Interpretability and debugging.** Darkcanuck spent hundreds of iterations over a year developing Gaff's NN system. A VCS bug is easy to spot; a NN weight issue can go "undetected for a long, long time" (Darkcanuck's own words). The engineering cost per marginal improvement is higher.

**5. Computation constraints.** Robocode enforces CPU time limits per tick. Complex neural networks or deep architectures risk skipping turns.

### 5.3 The Cross-Battle Learning Opportunity

Here is the fundamental asymmetry that nobody has successfully exploited:

**Current bots start every match from zero.** Engineer saves NN weights between rounds but not between matches. Gaff does the same. DrussGT's Dynamic Clustering starts with an empty KD-tree each match. Every bot re-learns every opponent from scratch, every time they meet.

**An ML bot that persists knowledge across battles starts with an advantage that compounds.** After 1000 battles against the RoboRumble field, it would have dense behavioral profiles of every opponent it's ever faced.

#### Robocode's Data Saving Rules

The API provides `getDataDirectory()` and `getDataFile()` methods for file persistence. Key constraints:

| Rule | Value | Source |
|------|-------|--------|
| Max disk space | **200 KB** per robot | Robocode FAQ |
| Save between rounds | Yes (use `static` variables — each robot gets its own classloader) | Robocode FAQ |
| Save between matches | Yes (write to `getDataDirectory()`) | Robocode API |
| RoboRumble data clearing | **Data directory is NOT preserved between RoboRumble pairings** | RoboRumble client behavior |

**The catch:** In RoboRumble, each pairing runs on a different volunteer's machine. Your saved data doesn't follow you. The 200 KB limit would also constrain model size even in local battles.

**However:** For a self-hosted rumble (like the LiteRumble successor we're building), we control the runtime environment. We could:
- Allow larger data directories
- Ensure data persists between matches against all opponents
- Even provide a shared data service (opponent profiles accessible via a sidecar)

This is where our fork has a genuine architectural opportunity that classic RoboRumble never allowed.

### 5.4 What ML Could Discover That Humans Can't

Hand-crafted bots implicitly assume certain feature interactions don't matter. A well-trained ML system could find:

**1. High-dimensional interaction effects.** Maybe lateral velocity combined with wall distance combined with time-since-last-reversal has a non-obvious three-way interaction that predicts a specific GF peak. Human segmentation schemes use maybe 4–6 dimensions; NNs naturally capture higher-order interactions.

**2. Opponent-specific behavioral fingerprints.** Every bot has quirks in its movement profile — specific GF distributions under specific conditions. With cross-battle data, you could recognize an opponent's movement "signature" within the first few waves and immediately load the correct targeting profile.

**3. Temporal patterns invisible to histogram methods.** VCS captures *what* GFs are visited but not *when* or *in what sequence*. An RNN or attention-based model could capture temporal dependencies: "this bot always reverses direction two ticks after being shot at, but only when near a wall."

**4. Transfer learning between similar opponents.** Many bots share code heritage (Diamond variants, DrussGT forks, WaveSerpent-based bots). Learning to cluster opponents by behavior and transfer knowledge between similar ones could dramatically reduce cold-start time.

### 5.5 Practical Obstacles

**Training data volume.** A meaningful targeting model needs thousands of wave observations per opponent. At ~30 waves per round and 10 rounds per match, that's ~300 waves per match. Getting to 10,000+ observations requires 30+ matches — feasible in a continuous rumble, but slow.

**Overfitting to specific opponents.** A model trained on 100 battles against DrussGT might generalize poorly to DrussGT version N+1, or to a different bot using similar but not identical movement. Regularization and ensemble methods needed.

**The 200 KB constraint** (in standard Robocode). A serious ML model — even a small one — easily exceeds this. Quantization, pruning, or encoding tricks could help. A sparse model with 1000 float32 weights = 4 KB; you could store ~50 per-opponent models in 200 KB.

**Generalization vs. memorization.** The ideal system should:
- Recognize known opponents quickly (memorization)
- Handle unknown opponents with reasonable defaults (generalization)
- Adapt within a match when an opponent's behavior doesn't match stored profile (online learning)

This is a classic explore/exploit tradeoff, and getting the balance wrong in any direction loses battles.

**CPU time constraints.** Neural network inference must complete within Robocode's per-tick time limit. Simple architectures (linear models, small MLPs) are fine; transformers or deep networks are not.

---

## Section 6: The Cross-Opponent Learning Thesis

### 6.1 The Core Insight

Hand-crafted bots solve the cold start problem through **domain expertise**: start with reasonable defaults (orbit, change direction randomly, fire at GF 0), then adapt within the match using statistical methods. This works because brilliant humans encoded 20 years of competitive knowledge into the initial behavior.

An ML approach solves it through **data**: start with knowledge of *this specific opponent* from prior encounters, or with knowledge of *opponents like this one* from battles against similar bots. No human domain knowledge required for the initial behavior — it emerges from data.

**The thesis:** In a persistent-data environment (self-hosted rumble), an ML bot that has faced every RoboRumble participant 50+ times should outperform hand-crafted bots in the first 2–3 rounds of each match, because it starts with an opponent-specific behavioral model rather than generic defaults. Whether it can maintain that advantage in later rounds — when hand-crafted bots have adapted too — is an open question.

### 6.2 Per-Opponent Models

The simplest architecture: one small model per named opponent.

```
OpponentModel:
  name: "voidious.Diamond 1.8.28"
  gf_distribution: float[61]           // average GF profile
  gf_by_distance: float[3][61]         // near/mid/far
  gf_by_lateral_vel: float[5][61]      // velocity buckets
  reversal_tendency: float             // how often they reverse
  preferred_distance: float            // typical orbit distance
  fire_power_distribution: float[6]    // histogram of fire powers used
  anti_surfer_score: float             // how well they hit surfers
  wall_proximity_modifier: float[61]   // GF shift near walls
  last_updated: timestamp
  confidence: int                      // number of waves observed
```

At ~2 KB per opponent, you could store 100 opponent profiles in 200 KB. With controlled infrastructure, no limit.

**On match start:** look up `scannedRobot.getName()`, load stored model, immediately use it for targeting and movement danger assessment. Fall back to generic defaults for unknown opponents.

**Per-round update:** after each round, update the stored model with new observations. Weight recent data more heavily (opponent may have changed versions).

### 6.3 Opponent Archetype Classifier

Instead of (or in addition to) per-opponent models, classify opponents into behavioral archetypes and transfer learned patterns between similar ones.

**Possible archetypes** (speculative, would need to be discovered from data):

| Archetype | Movement Pattern | Targeting Pattern | Examples (guesses) |
|-----------|-----------------|-------------------|-------------------|
| **Surfer** | Wave surfing, flattening | GF targeting, anti-surfer | Diamond, DrussGT |
| **Random Orbiter** | Random direction changes, orbital | VCS or DC targeting | Shadow, Tron |
| **Stop-and-Go** | Stops on energy drop, dodges between waves | Linear/circular targeting | Some nanos |
| **Rammer** | Charges at opponent | Point-blank power 3 | Walls, SpinBot |
| **Pattern Mover** | Repeating movement sequences | Head-on or linear | Many sample bots |
| **Oscillator** | Back-and-forth on fixed axis | Various | Simple bots |

**Classification method:** Observe the first 2–3 rounds of opponent behavior. Extract features:
- GF distribution shape (flat = surfer/random, peaked = predictable)
- Average distance maintained
- Velocity change frequency
- Correlation between our fire and their direction change (surfer signal)
- Fire power distribution

Feed into a small classifier (even logistic regression or decision tree). Once classified, load the archetype's targeting profile as a warm start.

### 6.4 Behavioral Clustering and Transfer Learning

The most ambitious approach: don't predefine archetypes — discover them.

**Offline pipeline** (run between rumble seasons):
1. Collect wave-hit data from all battles
2. For each opponent, compute a behavioral embedding (distribution of GFs by situation)
3. Cluster opponents by embedding similarity (k-means, DBSCAN, or hierarchical)
4. For each cluster, train a shared meta-model that captures the cluster's typical movement patterns
5. Store cluster centroids + per-opponent cluster assignments

**Online use:**
1. Match starts. Opponent name known → load per-opponent model if available.
2. If unknown opponent: observe first 50 waves, compute embedding, find nearest cluster, load cluster model.
3. Fine-tune with in-battle data.

**The key question:** Do opponents cluster meaningfully? If 200 bots turn out to have essentially unique movement profiles, clustering doesn't help. But given that many bots share codebases (Diamond forks, DrussGT variants, BasicSurfer-based bots), clusters should emerge.

### 6.5 What Would Success Look Like?

An ML bot using cross-battle learning would be competitive if:

1. **Against known opponents** (re-matches): It achieves higher hit rates in rounds 1–3 than any bot that starts from scratch. The hand-crafted bot catches up by round 5–6, but the ML bot has already built an energy advantage.

2. **Against unknown opponents**: It classifies the archetype within 2 rounds and performs comparably to a top hand-crafted bot in rounds 3–10.

3. **Against new versions of known opponents**: It detects behavioral drift (stored model doesn't match observations), discards stale data, and falls back to archetype-level knowledge.

4. **In aggregate ranking**: Over hundreds of RoboRumble pairings, the early-round advantage translates to a consistent 1–2% rating improvement — enough to move up 10–20 places in the rankings.

**The honest assessment:** This is a viable research direction, not a guaranteed win. The Robocode community's 20-year conclusion — that hand-crafted statistical methods beat neural approaches in this domain — deserves respect. But that conclusion was reached under the constraint of ephemeral data (no cross-battle persistence). Removing that constraint changes the game. Whether it changes it *enough* is the experiment.

*Sources: RoboWiki — [Neural Targeting](https://robowiki.net/wiki/Neural_Targeting), [Gaff/Targeting](https://robowiki.net/wiki/Gaff/Targeting), [Engineer](https://robowiki.net/wiki/Engineer), [Dynamic Clustering](https://robowiki.net/wiki/Dynamic_Clustering), [Anti-Surfer Targeting](https://robowiki.net/wiki/Anti-Surfer_Targeting), [Adaptive Movement](https://robowiki.net/wiki/Adaptive_Movement), [Virtual Guns](https://robowiki.net/wiki/Virtual_Guns), [Robocode FAQ](https://robowiki.net/wiki/Robocode/FAQ). Speculation is marked; factual claims are sourced.*

---

## Section 7: Transfer Learning and Knowledge Distillation for In-Game Use

### 7.1 The Runtime Constraint Problem

Stage 4 trains large ML models offline on thousands of battles. Stage 5 needs those models to run **inside a Robocode robot in real-time**. These are the hard constraints:

| Constraint | Value | Source |
|------------|-------|--------|
| Per-tick time budget | ~10 ms (enforced by engine skip-turn penalty) | Robocode engine `Battle.java` |
| Robot runtime | Java class inside Robocode's JVM (single thread per robot) | Robocode architecture |
| JAR size | Up to ~800 KB practical limit (Saguaro precedent) | RoboRumble norms |
| File I/O at runtime | Forbidden in RoboRumble; allowed in self-hosted rumble | RoboRumble client behavior |
| Data directory limit | 200 KB per robot (standard Robocode) | Robocode FAQ |
| Libraries | No external JARs beyond the robot's own JAR | Robocode classloader sandboxing |

**What has to happen every tick:**
1. Receive scan data (`ScannedRobotEvent`: bearing, distance, energy, heading, velocity)
2. Compute features from scan + history (Stage 2 feature extraction, reused)
3. Feed features into prediction model → get output (predicted GuessFactor or dodge direction)
4. Convert prediction to robot command (`setTurnRight`, `setAhead`, `fire`)
5. ALL of steps 1–4 in under ~10 ms

Step 2 is lightweight — it's the same feature computation that hand-crafted bots already do every tick (lateral velocity, wall distance, time-since-direction-change, etc.). The bottleneck is step 3: model inference.

### 7.2 Model Options Ranked by Speed vs. Expressiveness

| Model Type | Inference Time (estimate) | Memory | JAR Impact | Expressiveness | Practical? |
|------------|--------------------------|--------|------------|----------------|------------|
| **Lookup tables** | O(1), ~0.01 ms | Proportional to discretization | 10–500 KB depending on dimensions | Low — limited by discretization granularity | ✓ Already proven (VCS) |
| **Linear models** | O(n) for n features, ~0.01 ms | ~100 bytes per model | Negligible | Low — can't capture nonlinear patterns | ✓ Trivially fast |
| **Decision trees / Random forests** | O(depth) per tree, ~0.05 ms for ensemble of 20 trees | ~1–50 KB per forest | 5–100 KB as generated if-else | Medium — captures axis-aligned interactions | ✓ Fast, code-generatable |
| **Polynomial approximations** | O(terms), ~0.05 ms for degree-3 with 10 features | ~1 KB per polynomial | Negligible | Medium — smooth surfaces only | ✓ Fast, compact |
| **Small neural nets** (2–3 layers, 50–100 neurons) | O(weights), ~0.1–1 ms | 10–400 KB for weights | Weights as float[] arrays | High — universal approximator | ✓ Feasible if kept small |
| **K-nearest-neighbors** (pre-selected exemplars) | O(K × features) with small K, or O(log n) with kd-tree | Proportional to exemplar count | 10–200 KB for exemplar data | High — nonparametric | ✓ Already proven (DC) |
| **Gradient Boosted Trees** (XGBoost, LightGBM) | O(trees × depth), ~0.1–0.5 ms for 100 trees | 50–500 KB | Code-generated if-else chains | High — state-of-the-art tabular | ✓ If code-generated |
| **Transformers / Deep nets** | O(n² × d), >10 ms | >1 MB | Far too large | Very high | ✗ Too slow and too large |

### 7.3 Back-of-Envelope Calculations

#### How many weights fit in 800 KB?

```
800 KB = 819,200 bytes
float32: 4 bytes per weight → 204,800 weights
float16: 2 bytes per weight → 409,600 weights
int8 (quantized): 1 byte per weight → 819,200 weights
```

A 3-layer MLP with architecture [12 inputs → 100 hidden → 100 hidden → 61 outputs]:
- Layer 1: 12 × 100 + 100 bias = 1,300 weights
- Layer 2: 100 × 100 + 100 bias = 10,100 weights
- Layer 3: 100 × 61 + 61 bias = 6,161 weights
- **Total: 17,561 weights = ~69 KB as float32**

That leaves ~730 KB for robot code, multiple models, and per-opponent lookup tables. Comfortably within budget.

#### How fast is a 100-neuron matrix multiply in Java?

A single layer forward pass for 100×100 weights:
```
100 × 100 = 10,000 multiply-add operations
Modern JVM on a 3 GHz CPU: ~1 ns per multiply-add (with JIT optimization)
10,000 × 1 ns = ~10 μs per layer
3 layers ≈ 30 μs total
```

That's **0.03 ms** — well under the 10 ms budget. Even a 500-neuron network (250,000 operations per layer) would take ~0.75 ms for 3 layers. Neural net inference is not the bottleneck.

The real concern is **memory allocation and GC pressure**. Pre-allocating all activation arrays and reusing them avoids GC pauses. No `new float[]` inside the tick loop.

#### How large is a decision tree ensemble as Java code?

A single decision tree with depth 10 generates ~1024 leaf nodes, each needing one comparison and one branch:
```java
if (lateralVelocity < 3.5) {
    if (distance < 400.0) {
        if (wallDist < 0.3) {
            return GF_BINS[14]; // pre-computed GF distribution
        } else { ... }
    } else { ... }
} else { ... }
```

~2 KB of compiled bytecode per depth-10 tree. An ensemble of 50 trees ≈ 100 KB of bytecode. The JAR compresses this well (if-else chains have high redundancy). Runtime: one branch prediction per level × 10 levels × 50 trees = 500 comparisons ≈ 0.001 ms.

### 7.4 The Distillation Pipeline

The core mechanism for getting offline ML intelligence into a real-time robot:

```
┌─────────────────────────────────────────────────────────────────┐
│ OFFLINE (Stage 4 — no runtime constraints)                      │
│                                                                 │
│  1. Collect data: thousands of battles → wave observations      │
│  2. Train TEACHER model: deep NN, GBM ensemble, or transformer  │
│     - Can be arbitrarily large and slow                         │
│     - Input: 12+ features per wave observation                  │
│     - Output: GuessFactor probability distribution (61 bins)    │
│  3. Teacher achieves best possible accuracy on held-out data    │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                    Knowledge Distillation
                           │
┌──────────────────────────▼──────────────────────────────────────┐
│ DISTILLATION (offline, but targeting runtime constraints)        │
│                                                                 │
│  4. Train STUDENT model that mimics teacher's outputs:           │
│     - Small MLP (12→100→61), or                                 │
│     - Gradient boosted trees (50 trees, depth 8), or            │
│     - Lookup table (discretized feature space)                  │
│  5. Student trains on teacher's SOFT outputs (probability       │
│     distributions), not just hard labels — preserves more info  │
│  6. Evaluate: student accuracy vs. teacher accuracy             │
│     - Acceptable if student retains >90% of teacher's           │
│       improvement over the uniform-GF baseline                  │
│  7. Compress: quantize weights to int8/int16 if needed          │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                    Code Generation
                           │
┌──────────────────────────▼──────────────────────────────────────┐
│ CODE GENERATION (build step)                                    │
│                                                                 │
│  8. Convert student model to pure Java source code:             │
│     - NN: weights as static final float[] arrays,               │
│       forward() as matrix multiply loops                        │
│     - Trees: nested if-else chains                              │
│     - Lookup tables: multi-dimensional arrays with index math   │
│  9. No external dependencies — just arithmetic on arrays        │
│ 10. Compile into robot JAR alongside game logic                 │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                      Deploy to Robot
                           │
┌──────────────────────────▼──────────────────────────────────────┐
│ RUNTIME (Stage 5 — inside Robocode JVM, <10 ms per tick)        │
│                                                                 │
│ 11. Robot receives ScannedRobotEvent                            │
│ 12. Feature extraction: same code as Stage 2 (shared codebase)  │
│ 13. Model inference: call generated predict(features) method    │
│ 14. Output: GF probability distribution → fire at peak GF      │
│ 15. For movement: same pipeline but predicting enemy aim        │
│                                                                 │
│ Optional online adaptation:                                     │
│ 16. Blend model predictions with in-battle VCS observations     │
│     using Bayesian weighting (model = prior, VCS = likelihood)  │
│ 17. As battle progresses, VCS dominates (more recent data)      │
│ 18. Model provides warm-start advantage in rounds 1–3           │
└─────────────────────────────────────────────────────────────────┘
```

### 7.5 Architecture Compatibility: The Shared Feature Space

The offline model and the in-game model **must compute identical features from the same game state**. This is a non-negotiable invariant:

```
Stage 2 (offline data pipeline):
  raw battle replay → parse events → compute features → store as training data

Stage 5 (in-game robot):
  ScannedRobotEvent → compute features → feed to distilled model → act
```

If the offline pipeline computes `lateralVelocity = velocity * sin(heading - absoluteBearing)` but the in-game code computes `lateralVelocity = velocity * sin(heading - relativeBearing)`, the model trains on one feature space and predicts in another. Results will be garbage. This is why Stage 2 code must be **reusable** — the same Java methods for feature extraction must work both in the offline data processing pipeline and in the live robot.

**Practical enforcement:**
- Feature extraction code lives in a shared library (shared between the data pipeline and the robot JAR)
- Unit tests verify that offline feature computation and online feature computation produce identical outputs for the same game state inputs
- Features are documented with exact formulas in [features.md](features.md)

### 7.6 Distillation Strategies by Model Type

#### Strategy A: Neural Net Distillation (Recommended Starting Point)

**Teacher:** Deep MLP with 3–5 hidden layers, 256+ neurons each, trained on all battle data. Or a transformer operating on sequences of recent ticks.

**Student:** MLP with 2 hidden layers, ~100 neurons each. Architecture example:
```
Input (12 features) → Dense(100, ReLU) → Dense(100, ReLU) → Dense(61, Softmax) → GF distribution
```

**Distillation loss:** KL divergence between teacher's output distribution and student's output distribution, with temperature scaling:
```
Loss = KL(softmax(teacher_logits / T), softmax(student_logits / T)) * T²
```
Temperature T > 1 softens the distributions, exposing more information about the teacher's uncertainty.

**Code generation:** Straightforward — weights become `static final float[]` arrays, forward pass is three nested loops (matrix multiply + bias + activation). Total generated code: ~100 lines of Java.

**Gaff precedent:** Darkcanuck's Gaff already ran a dual-MLP system in real-time inside Robocode. Two networks with 84 inputs (via RBF features) and 61 outputs each. No hidden layers — but his finding that hidden layers "didn't help" may have been because his training set was too small (single-battle online learning). With offline pre-training on thousands of battles, hidden layers should have enough data to learn useful representations.

##### Architecture Rationale

**Input dimensionality (12).** The 12 features map directly to the consensus segmentation dimensions from Section 6:

| # | Feature | Source |
|---|---------|--------|
| 1 | Lateral velocity | `|velocity × sin(heading − bearing)|` — how fast the target moves perpendicular to the aiming line |
| 2 | Advancing velocity | `velocity × cos(heading − bearing)` — how fast the target approaches or retreats |
| 3 | Distance | Euclidean distance between firer and target |
| 4 | Acceleration | Change in speed since last tick: `currentSpeed − previousSpeed` |
| 5 | Wall distance forward | How far (in ticks or 0–1 normalized) the target can travel in its current orbit direction before hitting a wall |
| 6 | Wall distance reverse | Same, but in the reverse orbit direction |
| 7 | Time since direction change | Ticks since the target last reversed or changed heading |
| 8 | Time since deceleration | Ticks since the target last decelerated (speed decreased) |
| 9 | Bullet flight time | `distance / bulletSpeed` — how long the bullet will travel, determining how much the target can dodge |
| 10 | Lateral distance last 10 ticks | Cumulative lateral displacement over the previous ~10 ticks — captures recent movement commitment |
| 11 | Current GuessFactor | The GF predicted by simple linear targeting — provides an anchor for the distribution |
| 12 | Bullets fired (normalized) | Shot count or time into the round — proxies for how much the opponent has adapted |

These 12 are the minimal set that top bots like DrussGT, Diamond, and ScalarBot converge on. Every eliminated feature loses discriminative power; every added feature dilutes training signal per bin.

**Output dimensionality (61).** GuessFactor ranges from −1 to +1. Discretizing into 61 bins gives bin width ≈ 0.033, which at a typical distance of 400 pixels corresponds to ~5 pixels of lateral displacement — fine enough that prediction and reality align within one robot width (36 px). Wider bins lose resolution; narrower bins (DrussGT uses 171) require proportionally more training data to populate. At 3M wave observations, 61 bins get ~50K samples/bin on average — ample for smooth distributions. With 171 bins that drops to ~18K/bin, still viable but more prone to noise in underrepresented feature regions. Start with 61; experiment with 91 or 121 once the pipeline is running.

**Hidden layer sizing (100 neurons).** The universal approximation theorem guarantees that a single hidden layer of sufficient width can approximate any continuous function. With 12 inputs and 61 outputs, the function being learned maps a region of ℝ¹² → a probability simplex in ℝ⁶¹. Two hidden layers of 100 neurons each give:

```
Layer 1: 12 × 100 + 100 = 1,300 parameters
Layer 2: 100 × 100 + 100 = 10,100 parameters
Output:  100 × 61 + 61  = 6,161 parameters
Total:   17,561 parameters
```

With ~3M training samples, the ratio of samples to parameters is ~170:1, comfortably above the 10:1 minimum needed to avoid overfitting with standard regularization (dropout 0.1–0.2, weight decay 1e-4). The 100-neuron width provides enough capacity for nonlinear feature interactions (e.g., "high lateral velocity + close distance + wall ahead" requiring a different GF distribution than any two of those conditions alone) without being so large that training becomes unstable.

**Teacher architecture (256+ neurons, 3–5 layers).** The teacher has no runtime constraint — it runs offline. A larger model captures higher-order feature interactions and subtler patterns:

```
Teacher: 12 → 256 → 256 → 256 → 61
Parameters: ~200K
```

With millions of wave observations, this model is still heavily overparameterized relative to training data, but modern regularization (dropout, batch normalization, AdamW weight decay) handles this well. The teacher's job is to produce the best possible soft probability distributions. Five layers can learn progressively more abstract representations: raw features → interaction features → movement patterns → opponent archetypes → GF prediction.

**Determining the right sizes in practice:** Don't treat these numbers as fixed. Run hyperparameter optimization:

1. Start with a minimal student `[12 → 50 → 61]` (~3.4K params). Train. Evaluate.
2. Scale to `[12 → 100 → 100 → 61]` (~17.5K params). Does validation loss drop meaningfully?
3. Try `[12 → 200 → 200 → 61]` (~53K params). If the improvement is marginal, the 100-neuron model is sufficient.
4. For the teacher, use Bayesian optimization (Optuna or Ray Tune) over depth ∈ {3,4,5}, width ∈ {128,256,512}, dropout ∈ {0.0, 0.1, 0.2, 0.3}.
5. The signal that a model is too small: training loss and validation loss are both high (underfitting). Too large: training loss is low but validation loss plateaus or rises (overfitting).

##### Temporal Modeling

**The problem.** The current MLP treats each wave observation as independent: feature vector at wave-fire time → GF distribution. But Robocode movement is inherently temporal. A robot reversing direction follows a predictable deceleration-stop-accelerate sequence spanning 5–10 ticks. A robot doing oscillating wall-avoidance has patterns spanning 20–50 ticks. Hand-crafted features like "time since direction change" and "lateral distance last 10 ticks" are ad-hoc temporal summaries that discard most of the sequential structure.

What information is lost? Consider two scenarios with identical current features but different histories:
- Robot has been moving at constant velocity for 40 ticks (will likely continue)
- Robot has been oscillating every 15 ticks and is at tick 14 (will likely reverse next tick)

These produce the same "lateral velocity = 6.0" and "time since direction change = 14" but have very different GF distributions. The hand-crafted features can't distinguish them. A sequence model can.

**Option 1: Temporal features as MLP input (current approach).** This is the simplest and what top Robocode bots do today. The 12 features include temporal summaries ("time since direction change", "lateral distance last 10 ticks") that capture the most discriminative historical signals. Advantages: fast inference, small model, proven in practice. Disadvantage: can only exploit temporal patterns that a human designed a feature for.

**Option 2: RNN/LSTM/GRU on raw state sequences.** Feed the last N ticks of raw state:

```
Per tick: [velocity, headingDelta, lateralVelocity, advancingVelocity, acceleration, wallDistFwd]
= 6 raw features per tick
× 20 ticks of history
= 120 input values per sequence step
```

Architecture:
```
Input (20 × 6) → LSTM(64 hidden, 2 layers) → Dense(61, Softmax)
```
Parameters: 2 × 4 × (6 × 64 + 64 × 64 + 64) = ~67K (LSTM) + 64 × 61 + 61 = ~4K (output) ≈ 71K total.

Inference cost: 20 sequential LSTM steps, each involving 4 matrix multiplies of dimension 64. On JVM with optimized array ops: ~0.3–0.5 ms. This is tight — a Robocode tick is 1 ms wall-clock on standard hardware during a battle, and the robot has other work to do. Feasible if the LSTM is the only heavy computation.

Advantage: the LSTM can discover temporal patterns that humans didn't encode (e.g., subtle acceleration oscillation patterns, or correlations between movement and gun heat timing). Disadvantage: 4× larger than the MLP, sequential computation that's harder to optimize in Java without SIMD.

**Option 3: 1D-CNN over tick sequences.** Temporal convolutions are parallelizable (unlike RNNs) and effective for short sequences:

```
Input (20 × 6) → Conv1D(32 filters, kernel=5, ReLU) → Conv1D(32, kernel=3, ReLU) → GlobalAvgPool → Dense(61, Softmax)
```
Parameters: (5 × 6 × 32 + 32) + (3 × 32 × 32 + 32) + (32 × 61 + 61) = ~5K + ~3K + ~2K ≈ 10K total.

Inference: two convolution passes over 20 time steps — effectively parallel dot products. ~0.1 ms on CPU. Significantly faster than LSTM. The kernel sizes (5, 3) capture patterns spanning 5–7 ticks, which covers direction reversals and acceleration phases. For longer patterns (20+ ticks), add dilated convolutions or increase kernel size.

Advantage: fast, parallelizable, small. Disadvantage: limited receptive field means it may miss long-range temporal dependencies.

**Option 4: Transformer (teacher only).** Self-attention over movement sequences:

```
Input (50 × 6) → Positional Encoding → 2 Transformer layers (4 heads, dim=32) → [CLS] token → Dense(61, Softmax)
```
Parameters: ~25K. Inference: attention matrices are O(N²) in sequence length — for N=50 this is 2,500 attention weights per head, perfectly manageable. But the constant factor (softmax, multi-head concatenation, layer norms) makes this ~1–2 ms in Java. Too slow for in-game student use, but fine for the offline teacher.

Transformers excel at learning which parts of the movement history are most relevant (via attention weights). After training, the attention patterns reveal what the model "looks at" — this can inform which temporal features to design for the student MLP.

**Recommended approach:**

1. **Teacher:** LSTM or Transformer operating on raw 20–50 tick sequences. This teacher sees the full temporal structure.
2. **Student:** MLP with 12 hand-crafted features (current design) + 2–4 additional temporal features derived from analyzing the teacher's learned representations. For example:
   - If the teacher's attention frequently focuses on ticks 3–7 ago → add "velocity change over last 5 ticks" feature
   - If the LSTM's hidden state correlates with oscillation frequency → add "dominant movement frequency" (computed via simple autocorrelation)
3. **Distillation process:** The teacher's soft probability outputs become the student's training labels. The temporal knowledge is implicitly transferred — the student learns that certain feature combinations (which summarize temporal patterns) predict certain GF distributions, because the teacher used the full sequence to arrive at those distributions.

This hybrid approach gets the best of both worlds: temporal awareness from the teacher, fast inference from the student MLP.

##### Learning Algorithms

**For targeting: Supervised Learning (strongly recommended).**

Targeting is a prediction problem with clear ground truth. At wave-fire time, record the feature vector. When the wave reaches the opponent, record the actual GuessFactor where the opponent was. This is a labeled dataset — supervised learning is the natural choice.

Training setup:
- **Input:** 12-feature vector at wave-fire time
- **Label:** actual GF where the wave intersected the opponent (one-hot or smoothed Gaussian around the true GF bin)
- **Loss function:** KL divergence or cross-entropy between predicted and target GF distributions
  ```
  L = −Σᵢ target[i] × log(predicted[i])    (cross-entropy)
  L = Σᵢ target[i] × log(target[i] / predicted[i])    (KL divergence)
  ```
  Using a Gaussian-smoothed target (σ ≈ 1.5 bins) rather than one-hot gives the network credit for being close, which stabilizes training.
- **Optimizer:** AdamW (Adam with decoupled weight decay). Learning rate 3e-4 with cosine annealing schedule. Weight decay 1e-4. This is the standard recipe for neural network training in 2024–2026.
- **Regularization:** Dropout 0.1–0.2 between hidden layers. Early stopping based on validation loss (hold out 10% of battles as validation set).
- **Batch size:** 512–2048. Larger batches are fine with the cosine schedule.
- **Epochs:** 50–100. With 3M samples, each epoch is ~6K batches of size 512.

For the distillation phase (student learning from teacher):
- **Teacher output:** Soft probability distributions (61-dim vectors) with temperature T=3–5
- **Student loss:** KL(teacher_soft, student_soft) × T², where both are computed with temperature scaling
- **Optionally add:** α × CrossEntropy(true_label, student_hard) to ground the student in reality, not just the teacher's imperfections

**For movement: Imitation Learning → Reinforcement Learning.**

Movement is harder because the "correct" action is ambiguous — there's no single right answer for dodge direction. Two approaches:

*Imitation learning (bootstrap):* Train a movement model to mimic what top bots do. Input: game state features. Output: probability of each movement action (CW orbit, CCW orbit, stop/reverse). Label: what the reference bot actually did. This gives a reasonable starting policy.

*RL fine-tuning with PPO:* After bootstrapping with imitation learning, fine-tune with Proximal Policy Optimization (PPO). PPO is the default algorithm for game RL in 2026 — stable, sample-efficient, and works with both continuous and discrete action spaces. Setup:
- **State:** Game features (position, velocity, enemy position, detected bullets, wall distances)
- **Action:** Movement command (orbit direction, target velocity)
- **Reward:** `−damage_taken + 0.1 × survival_ticks`. The sparse reward problem (not knowing if a dodge succeeded until the bullet passes) is mitigated by PPO's advantage estimation (GAE-λ), which propagates reward signal backwards through time.
- **Training:** Self-play or play against a diverse population of bots. PPO with GAE-λ=0.95, clip ratio 0.2, value function coefficient 0.5.

*Modern alternatives to Q-learning:*
- **PPO:** On-policy, stable, the workhorse of game RL. Used in OpenAI Five (Dota 2), AlphaStar, and most modern game AI. Best default choice.
- **SAC (Soft Actor-Critic):** Off-policy, better sample efficiency than PPO, entropy regularization encourages exploration. Better when training data is expensive to generate. Good fit for Robocode since battles are slow.
- **DQN with prioritized experience replay:** For discrete action spaces. Classic but largely superseded by PPO/SAC for continuous or high-dimensional action spaces.
- **MCTS + neural value/policy network (AlphaZero-style):** Powerful but requires search at decision time. Robocode's 1ms-per-tick constraint makes tree search infeasible during battle. Could work for offline strategy planning.

*Why not pure RL for targeting?* The reward signal for targeting is extremely delayed and sparse: you fire at tick T, the bullet travels for 15–40 ticks, and you get +1 (hit) or +0 (miss). Credit assignment across that gap is very hard for RL. Supervised learning, where you directly observe the GF where the wave arrived, is dramatically more sample-efficient.

**For strategy-level decisions: Contextual Bandits.**

Fire power selection (0.1–3.0) and mode switching (aggressive/defensive) are low-dimensional decisions with relatively quick feedback (damage dealt/taken over the last 50–100 ticks). A contextual bandit approach works well:
- **Context:** Energy delta, hit rate last N shots, opponent type classification
- **Arms:** Fire power levels {0.1, 1.0, 2.0, 3.0} or {aggressive, balanced, defensive} modes
- **Reward:** Damage efficiency = (damage dealt − damage taken) per tick
- **Algorithm:** LinUCB or Thompson sampling. Simple, fast, low-regret.

**Self-play and population-based training.** For maximum strength, train against a diverse population including copies of the bot at different training stages (à la AlphaStar's league training). This prevents overfitting to a fixed set of opponents. Implementation: maintain a pool of 10–20 checkpoint versions, sample opponents from the pool with probability weighted toward recent versions. This is a Stage 3+ concern — get the basic pipeline working first.

##### Compute Requirements

**Training data generation (the expensive part).**

The bottleneck is not ML training — it's running thousands of Robocode battles to generate training data.

Estimate for comprehensive data collection:
- 435 pairings (top 30 bots round-robin: C(30,2) = 435)
- 35 rounds per pairing
- ~200 waves per round
- Total: **~3M wave observations**
- Each observation: ~50 floats × 4 bytes = 200 bytes
- Total dataset: **~600 MB** on disk (compressed: ~150 MB)

Battle runtime: ~2 seconds per round × 35 rounds × 435 pairings = ~8.5 hours on a single core. Parallelizable across GitHub Actions runners — with 10 concurrent runners, ~1 hour wall-clock time. This is the real compute cost, and it's dominated by CI minutes, not GPU time.

**ML training cost (the cheap part).**

| Model | Parameters | Hardware | Time | Cloud Cost |
|-------|-----------|----------|------|------------|
| MLP teacher [12→256→256→256→61] | ~200K | CPU (any modern machine) | ~10 hours | Free |
| MLP teacher [12→256→256→256→61] | ~200K | Single GPU (T4/A10) | ~30 min | $0.50–$1.00 |
| LSTM teacher [20×6→LSTM(64,2)→61] | ~71K | CPU | ~24–48 hours | Free |
| LSTM teacher [20×6→LSTM(64,2)→61] | ~71K | Single GPU | ~2–4 hours | $2–$6 |
| Transformer teacher [50×6→2-layer→61] | ~25K | Single GPU | ~4–8 hours | $4–$12 |
| MLP student [12→100→100→61] | ~17.5K | CPU | ~2 hours | Free |
| Hyperparameter search (50 trials) | varies | Single GPU | ~12–24 hours | $12–$36 |

**GPU requirements:** A single mid-range GPU is more than sufficient. NVIDIA T4 (16 GB, available on GCP at ~$0.35/hr) or A10 (24 GB, ~$1.00/hr) handles all of the above. No multi-GPU setup or cluster needed — the models are small by modern ML standards (200K parameters vs. billions in LLMs).

Cloud options:
- **Google Colab (free tier):** T4 GPU, limited hours/day but enough for one-off training
- **Lambda Labs:** A10 GPUs at $0.75/hr, on-demand
- **AWS Spot instances:** p3.2xlarge (V100) at ~$0.90/hr spot
- **GitHub Actions GPU runners:** Available for public repos, NVIDIA T4, included in free tier minutes

**Local alternative:** Any machine with an NVIDIA GPU from the last 5 years (GTX 1060 or newer) can train these models. A MacBook with M1/M2/M3 can train the MLP models via PyTorch MPS backend in reasonable time (~1–2 hours for the teacher).

**Bottom line:** The total compute cost for a full training pipeline — data generation through hyperparameter search — is under $50 in cloud GPU time. The overwhelming majority of the compute budget goes to running Robocode battles for training data, not to ML training itself. Battle generation on GitHub Actions (Stage 1) is where CI minutes are consumed.

#### Strategy B: Tree Ensemble → Code Generation

**Teacher:** Same as Strategy A.

**Student:** Gradient boosted decision trees (50 trees, max depth 8). Train the student to predict the teacher's soft output for each of the 61 GF bins independently, or train 61 binary classifiers (one per bin).

**Code generation:** Each tree becomes a nested if-else chain. A code generator walks the tree structure and emits Java:
```java
// Tree 0 of 50, predicting GF bin 15 probability
static float tree0_bin15(float latVel, float dist, float wallF, ...) {
    if (latVel < 3.5f) {
        if (dist < 400.0f) {
            return 0.0823f;
        } else {
            if (wallF < 0.25f) { return 0.1247f; }
            else { return 0.0412f; }
        }
    } else { ... }
}
```

**Advantage:** Trees naturally handle the kind of axis-aligned splits that Robocode's segmentation already exploits. A tree that splits on `lateralVelocity < 3.5` then `wallDistance < 0.3` is doing exactly what DrussGT's segmented VCS buffers do — but the split thresholds are learned optimally rather than hand-tuned.

**Disadvantage:** Generating 61 bins × 50 trees = 3,050 tree functions. At ~2 KB bytecode each, that's ~6 MB of bytecode before compression. Could be too large. Mitigation: reduce to 10 trees, or use a single multi-output tree per ensemble member.

##### Variable-Depth Trees and ROI Optimization

Pavel's observation: "Not all branches of a decision tree need to be the same depth. There are diminishing ROI (score vs. size)." This is correct — and well-understood in the ML literature. Here's how to exploit it.

**1. Variable-depth pruning.**

Decision trees naturally produce variable-depth branches. A split is only worthwhile if the information gain (reduction in entropy or Gini impurity) exceeds a threshold. In the GF prediction context:

- **Dense regions** of feature space (common situations — enemy at medium distance, moderate lateral velocity, away from walls) have lots of training data → splits are statistically significant → the tree goes deeper (8–12 levels).
- **Sparse regions** (rare situations — enemy cornered at high speed while we're also near a wall) have few data points → splits are unreliable → the tree stops early (2–4 levels) and outputs a broad, uncertain GF distribution.

This is exactly the right behavior. You don't want the tree wasting bytecode on situations that occur 0.1% of the time. A shallow branch with a generic prediction ("fire at GF 0") is fine for rare cases; the deep branches handle the high-frequency situations where precision pays off.

**2. Cost-complexity pruning (CCP).**

The standard technique for controlling tree size. Define total cost as:

```
Cost(tree) = prediction_error(tree) + α × |leaves(tree)|
```

Where:
- `prediction_error` = sum of misclassification or cross-entropy loss across all leaves
- `|leaves(tree)|` = number of leaf nodes (proxy for tree size / bytecode)
- `α` = the complexity parameter controlling the tradeoff

Higher α → more pruning → smaller tree → more prediction error.
Lower α → less pruning → larger tree → less prediction error.

This directly implements Pavel's "diminishing ROI" insight. Each additional leaf buys some prediction accuracy, but the marginal gain decreases. CCP finds the Pareto-optimal tradeoff: for any given α, the resulting tree is the smallest tree that achieves that accuracy level.

**How to choose α:** Cross-validate. Train a full tree (α = 0), then sweep α values and evaluate accuracy on held-out waves. Plot accuracy vs. tree size (number of leaves). The curve will show a sharp initial accuracy gain that flattens out. Pick the "elbow" — the α where adding more leaves stops helping meaningfully.

Scikit-learn implements this as `DecisionTreeClassifier(ccp_alpha=...)`. The offline training pipeline (Python) can sweep α automatically.

**3. Size-constrained optimization.**

For Robocode, the real constraint is JAR size (bytecode), not abstract tree complexity. We can optimize directly for the budget:

1. Train a deep tree (depth 20+, no pruning). This overfits but captures every possible pattern.
2. Compute the information gain contributed by each internal node.
3. Sort nodes by information gain (ascending — least useful first).
4. Prune nodes one at a time, starting from the least useful, collapsing each pruned node into its parent's prediction.
5. After each prune, estimate the resulting Java bytecode size (each remaining node ≈ 15–30 bytes of bytecode for comparison + branch).
6. Stop when the estimated bytecode hits the budget (e.g., 5 KB per tree).

This gives the **optimal tree for a given size budget** — better than fixed-depth or fixed-α pruning because it directly targets the constraint that matters.

**4. Back-of-envelope sizing.**

A depth-8 tree has at most $2^8 = 256$ leaves, but after CCP pruning with α tuned for Robocode, typically 50–100 leaves survive (the rest are pruned as uninformative). A depth-15 tree might also have ~100 leaves if most branches are pruned early — the variable-depth tree focuses its depth budget where it matters most.

Bytecode per node: one `if` comparison + branch ≈ 20 bytes. 100 nodes ≈ 2 KB per tree. 50 leaves × 61 GF floats × 4 bytes = 12.2 KB for leaf data per tree (stored as `static final float[][]`). Total per tree: ~14 KB.

An ensemble of 20 such trees: 20 × 14 KB = **~280 KB**. Fits easily in the 800 KB JAR budget with room for robot logic.

**5. Multi-output trees.**

Instead of training 61 separate trees (one per GF bin), use a **single tree where each leaf stores a full 61-element GF distribution**. This is a massive size reduction:

- **61 separate trees:** Each tree has its own branch structure. If each tree has 100 internal nodes, that's 61 × 100 = 6,100 nodes of branching overhead, plus 61 × 100 × 1 leaf value = 6,100 floats for predictions. The branching structure itself dominates size.
- **1 multi-output tree:** 1 × 100 internal nodes for branching, plus 100 leaves × 61 floats = 6,100 floats for leaf distributions. The branching overhead is 61× smaller while storing the same total information.

Size comparison for a 20-tree ensemble:
```
Multi-output approach:
  20 trees × 100 nodes × 20 bytes/node = 40 KB (branch structure bytecode)
  20 trees × 100 leaves × 61 bins × 4 bytes = 488 KB (leaf GF distributions)
  Total: ~528 KB — fits in JAR

61 per-bin trees approach:
  61 bins × 20 trees × 100 nodes × 20 bytes = 2,440 KB (branch structure alone)
  Exceeds JAR budget before even counting leaf data.
```

Multi-output trees are supported natively by scikit-learn (`DecisionTreeClassifier` with multi-label output) and XGBoost (`multi:softprob` objective). The offline pipeline trains them; the code generator emits them.

**Leaf data compression:** The 488 KB of leaf distributions can be further reduced. Most leaves have peaked distributions (5–10 bins with significant probability mass). Store only the top-K bins per leaf with int16 probabilities:
```
20 trees × 100 leaves × 10 non-zero bins × (1 byte index + 2 bytes probability) = 60 KB
```

Total with sparse leaves: 40 KB branches + 60 KB leaves = **~100 KB**. Very comfortable.

**6. What variable-depth looks like as generated Java code.**

A variable-depth tree produces asymmetric `if-else` chains — some paths go 3 levels deep, others go 12:

```java
static float[] predict(float latVel, float dist, float wallF, float wallR,
                       float accel, float timeSinceDirChange) {
    if (dist < 350.0f) {
        // Dense region: enemy is close. Go deep — lots of training data here.
        if (latVel < 4.2f) {
            if (wallF < 0.15f) {
                // Near wall AND close AND slow lateral: very specific situation.
                // 7 more levels of splitting here...
                if (accel < 0.5f) {
                    if (timeSinceDirChange < 5.5f) {
                        return LEAF_47;  // peaked distribution at GF -0.3
                    } else {
                        return LEAF_48;  // broad distribution centered at GF 0
                    }
                } else {
                    return LEAF_49;  // accelerating near wall: distribution at GF +0.6
                }
            } else {
                // Close, slow lateral, but away from wall:
                // only 2 more levels — moderate data density
                if (accel < 0.0f) {
                    return LEAF_50;  // decelerating: stopped or stopping
                } else {
                    return LEAF_51;  // steady/accel: moving perpendicular
                }
            }
        } else {
            // Close, fast lateral: broad split only
            return LEAF_52;  // high lateral velocity at close range: wide GF spread
        }
    } else {
        // Far away: less training data, shallower branches.
        if (latVel < 5.0f) {
            return LEAF_53;  // generic far + slow: fire at GF 0
        } else {
            return LEAF_54;  // generic far + fast: fire at slight lead
        }
    }
}
```

The close-range branch goes 9 levels deep (lots of data, fine-grained splitting pays off). The far-range branch goes only 2 levels deep (sparse data, coarse prediction is all we can support). This is exactly the "diminishing ROI" principle in action — the tree allocates its complexity budget where it earns the most predictive accuracy.

#### Strategy C: Pre-Computed Lookup Tables

**Teacher:** Same as Strategy A.

**Student:** None — the teacher's predictions are pre-computed for a discretized feature grid and stored as a multi-dimensional array.

**Discretization example:**
```
lateralVelocity: 9 bins (0–8 in steps of 1)
distance: 6 bins (100–700 in steps of 100)
wallDistFwd: 4 bins (0–1 in steps of 0.25)
wallDistRev: 4 bins
acceleration: 3 bins (-1, 0, +1)
timeSinceDirChange: 5 bins (0, 1–3, 4–10, 11–30, 30+)
```

Total cells: 9 × 6 × 4 × 4 × 3 × 5 = **12,960 cells**
Each cell stores 61 GF probabilities as float16: 12,960 × 61 × 2 bytes = **1.58 MB**

**Problem:** 1.58 MB exceeds the 800 KB JAR budget. Mitigations:
- Reduce bins (e.g., 5 × 4 × 3 × 3 × 3 × 4 = 2,160 cells → 264 KB). But coarser bins lose the advantage over hand-crafted VCS.
- Store only the top 5 GF bins per cell instead of all 61: 12,960 × 5 × 3 bytes = 194 KB. At fire time, interpolate between top bins.
- Use int8 probabilities instead of float16: halves the size.

**Advantage:** O(1) lookup — absolutely the fastest possible inference. No computation at all, just array indexing.

**Disadvantage:** The curse of dimensionality limits this to 4–5 features before the table becomes too large. This is exactly the same limitation as hand-crafted VCS buffers — which means a lookup table distillation doesn't gain much over what DrussGT already does with 100+ random buffers. The value of the offline training is limited to optimizing the bin boundaries and consolidating the buffers into a single optimal table.

##### Compression, Sparse Arrays, and Runtime Decompression

Pavel's question: "Could we compress sub-cubes and decompress them during tick? Into pre-allocated array? Does Java offer fast decompression? Would sparse arrays help?"

All three are viable. Here are the options with exact Java APIs and arithmetic.

**1. Java compression: `java.util.zip.Inflater` / `Deflater` (ZLIB).**

Standard Java, no external dependencies. Available in the Robocode sandbox — `java.util.zip` is in the allowed package list (robots use it for JAR operations internally; Robocode's `RobotClassLoader` does not restrict it).

- **Compression ratio for GF float arrays:** Typically 2–4× for GF distributions. These arrays have lots of near-zero values and smooth gradients between adjacent bins — both properties ZLIB exploits well (Huffman coding assigns short codes to repeated byte patterns; smooth float sequences produce repeated high bytes).
- **Decompression speed:** ~200–400 MB/s on modern JVM (JIT-compiled `Inflater.inflate()` delegates to native zlib). A 1 KB compressed block decompresses in ~3–5 μs.
- **Pre-allocate the output buffer once, reuse every tick:**

```java
// Initialized once at robot startup
private final Inflater inflater = new Inflater();
private final byte[] decompressedBlock = new byte[BLOCK_SIZE]; // e.g. 4096 bytes

// Called per tick on cache miss (see LRU cache below)
void decompressSubCube(byte[] compressed, int offset, int length) {
    inflater.reset();
    inflater.setInput(compressed, offset, length);
    try {
        inflater.inflate(decompressedBlock);
    } catch (DataFormatException e) {
        // Should never happen with valid pre-compressed data
    }
}
```

No allocation in the hot path. `inflater.reset()` reuses the internal native buffer. The `decompressedBlock` byte array is allocated once and overwritten on every call.

**2. LZ4 (java-lz4 library).**

Faster decompression (~1–2 GB/s, roughly 3–5× faster than ZLIB) but requires bundling the `lz4-java` JAR. Robocode's classloader **only loads classes from the robot's own JAR**, and there is no restriction on bundling library code inside that JAR. So LZ4 is technically usable — but it adds ~60 KB to JAR size, and ZLIB's 3–5 μs decompression is already far below the 10 ms tick budget. Not worth the JAR space.

**3. Custom RLE (Run-Length Encoding) for sparse GF distributions.**

GF distributions are often sparse — many bins have near-zero probability mass. A dead-simple custom RLE:

```
Encoding format: [N_zeros, value1, value2, ..., N_zeros, value3, ...]
Where:
  N_zeros = byte: count of consecutive zero (below-threshold) bins
  valueK  = short (int16): probability × 32767, for non-zero bins
```

Decoding is trivial array traversal — no library, no native call, ~0.001 ms:

```java
void decodeRLE(byte[] encoded, float[] output) {
    int outIdx = 0;
    int inIdx = 0;
    while (inIdx < encoded.length && outIdx < output.length) {
        int zeros = encoded[inIdx++] & 0xFF; // unsigned byte: 0–255 consecutive zeros
        outIdx += zeros;
        while (outIdx < output.length && inIdx + 1 < encoded.length) {
            byte marker = encoded[inIdx];
            if (marker == 0 || (marker & 0x80) != 0) break; // next zero-run or end
            short val = (short) ((encoded[inIdx] << 8) | (encoded[inIdx + 1] & 0xFF));
            output[outIdx++] = val / 32767.0f;
            inIdx += 2;
        }
    }
}
```

Compression ratio depends on sparsity:
- If 80% of bins are near-zero (typical for a well-trained model against non-random opponents): ~5× compression.
- If distribution is near-uniform (random opponent): ~1× (no savings). But uniform distributions also don't need high precision — a flat prediction is fine.

**4. Delta encoding + ZLIB.**

GF distributions are smooth — adjacent bins have similar probabilities. Delta encoding stores `bin[i] - bin[i-1]` instead of `bin[i]` directly. Deltas of smooth distributions cluster near zero → ZLIB compresses them much better:

```
Original GF (int16 values):  [0, 12, 45, 120, 350, 800, 1200, 900, 400, 100, 30, 5, 0, ...]
Delta-encoded:               [0, 12, 33,  75, 230, 450,  400, -300, -500, -300, -70, -25, -5, ...]
```

The deltas have smaller absolute values → fewer distinct byte patterns → ZLIB achieves 2–6× better compression than on raw values. Combined pipeline: delta encode → ZLIB compress. At decompression: ZLIB decompress → cumulative sum to undo deltas.

Total compression ratio (delta + ZLIB on GF arrays): typically 4–8×.

**5. Tiled/blocked lookup tables ("sub-cubes").**

Instead of storing the entire 12,960-cell table uncompressed, tile the feature space into blocks:

```
Example: 6D feature space discretized to 9×6×4×4×3×5 = 12,960 cells

Tile across the first 3 dimensions into sub-cubes of size 3×2×2:
  Sub-cubes per outer-cell: (9/3) × (6/2) × (4/2) = 3 × 3 × 2 = 18 sub-cubes
  Outer cells (remaining dimensions): 4 × 3 × 5 = 60
  Total sub-cubes: 60 × 18 = 1,080

Each sub-cube: 3×2×2 = 12 cells × 61 GF bins × 2 bytes (int16) = 1,464 bytes uncompressed
Compressed with ZLIB: ~400–700 bytes each (smooth distributions compress well within a tile)
Total compressed: 1,080 × ~550 bytes = ~580 KB
```

**Runtime lookup:**
1. Compute the 6D feature index from current game state.
2. Determine which sub-cube the feature vector falls in (integer division on discretized indices).
3. Check the LRU cache for that sub-cube.
4. **Cache hit (~80%+ of ticks):** Index directly into the decompressed sub-cube → read GF distribution. Cost: ~0.001 ms.
5. **Cache miss:** Decompress the sub-cube into the pre-allocated buffer (3–5 μs via `Inflater`), insert into LRU cache, read GF distribution. Cost: ~0.005 ms.
6. Total per-tick cost: **~0.005 ms average** (dominated by the 80%+ cache hit case).

**Why 80%+ cache hit rate:** Consecutive ticks have highly correlated feature vectors. The enemy's lateral velocity, distance, and wall proximity change slowly (acceleration is only ±1–2 per tick). Most ticks the feature vector stays in the same sub-cube or an adjacent one. With an LRU cache of N=8–16 sub-cubes (~24 KB of decompressed data in memory), the cache covers the robot's recent movement neighborhood.

```java
// LRU cache: fixed-size array of decompressed sub-cubes
private static final int CACHE_SIZE = 16;
private final int[] cacheKeys = new int[CACHE_SIZE];      // sub-cube indices
private final float[][] cacheData = new float[CACHE_SIZE][]; // decompressed data
private int cachePtr = 0;

float[] getSubCube(int subCubeIndex) {
    // Check cache
    for (int i = 0; i < CACHE_SIZE; i++) {
        if (cacheKeys[i] == subCubeIndex) return cacheData[i];
    }
    // Cache miss: decompress
    if (cacheData[cachePtr] == null) cacheData[cachePtr] = new float[SUB_CUBE_CELLS * 61];
    decompressSubCube(compressedBlocks[subCubeIndex], cacheData[cachePtr]);
    cacheKeys[cachePtr] = subCubeIndex;
    float[] result = cacheData[cachePtr];
    cachePtr = (cachePtr + 1) % CACHE_SIZE;
    return result;
}
```

**6. Sparse arrays.**

Are GF distributions actually sparse? For a well-trained model:
- **Yes, mostly.** Most game situations produce peaked distributions — 70%+ of probability mass concentrated in 5–10 bins out of 61. The remaining 51+ bins have near-zero values (below some threshold ε, e.g., 0.01).
- **Exception:** Against truly random movement, the distribution should be near-uniform (all 61 bins roughly equal) → not sparse. But random movers are weak opponents; coarse predictions suffice.
- **Overall expectation:** 70–80% sparsity across the typical RoboRumble field, giving 3–5× size reduction.

**Sparse representation options:**

*Naive sparse:*
```
struct SparseGF { byte binIndex; short probability; }  // 3 bytes per non-zero bin
```
If average 10 non-zero bins per cell (out of 61): 10 × 3 = 30 bytes vs. 61 × 2 = 122 bytes dense (int16). **4× savings.**

*Compressed Sparse Row (CSR) format:*
```java
// All data packed into three flat arrays (cache-friendly, no per-cell objects)
short[] values;     // all non-zero probabilities, concatenated across all cells
byte[] colIndices;  // which GF bin (0–60) each value belongs to
int[] rowPointers;  // offset into values[] for each cell (length = numCells + 1)
```

CSR is the standard sparse matrix representation in ML libraries (scipy.sparse, ONNX Runtime). Very cache-friendly for sequential access — all values for one cell are contiguous in memory.

**Java implementation (~20 lines):**
```java
// Pre-allocated dense output array, reused every tick
private final float[] gfDist = new float[61];

void lookupSparse(int cellIndex) {
    Arrays.fill(gfDist, 0f);  // fast native memset via JIT intrinsic, ~0.0005 ms
    int start = rowPointers[cellIndex];
    int end = rowPointers[cellIndex + 1];
    for (int i = start; i < end; i++) {
        gfDist[colIndices[i] & 0xFF] = values[i] / 32767f;  // int16 → float
    }
}
```
Execution time: ~0.002 ms for 10 non-zero entries. Dominated by the `Arrays.fill()` call.

No built-in sparse array class in Java — but the CSR pattern is trivial to implement manually as shown above. The three parallel arrays (`values`, `colIndices`, `rowPointers`) are stored as `static final` fields in the generated Java code, initialized from byte literals or a compressed resource.

**7. Combined approach: sparse + compressed sub-cubes.**

Apply all three techniques in combination — the compression stack:

```
Starting point:
  12,960 cells × 61 bins × 2 bytes (int16)        = 1,581 KB (dense, uncompressed)

Step 1 — Sparse (keep top 10 bins per cell):
  12,960 cells × 10 entries × 3 bytes (CSR)         = 389 KB

Step 2 — Delta-encode + ZLIB compress:
  389 KB × ~0.45 compression ratio                  = ~175 KB

Step 3 — Tile into 1,080 sub-cubes, individually compressed:
  ~175 KB total (sub-cube granularity allows 
   better compression than one monolithic block)     ≈ 150–200 KB

Step 4 — LRU cache at runtime:
  Stored in JAR: ~200 KB (compressed sub-cubes)
  RAM at runtime: ~10–15 KB (cache of 8–16 decompressed sub-cubes)
```

**The 200 KB stored + 15 KB runtime memory fits comfortably in the 800 KB JAR**, leaving ~600 KB for robot logic, ensemble models, and per-opponent adaptation data.

**Runtime cost per tick:**
- Cache hit (80%+ of ticks): sparse lookup → 0.002 ms
- Cache miss (20% of ticks): ZLIB decompress (0.005 ms) + sparse lookup (0.002 ms) = 0.007 ms
- Average: **~0.003 ms per tick** — negligible vs. the 10 ms budget.

**Sandbox verification:** `java.util.zip.Inflater` and `java.util.zip.Deflater` are in the `java.util.zip` package, which is part of the `java.base` module. Robocode's security manager (`net.sf.robocode.host.security.RobotClassLoader`) restricts access to file I/O, network, and system calls — but `java.util.zip` is a pure computation library operating on byte arrays in memory. It does NOT perform any I/O itself. The compressed data is loaded from `static final byte[]` constants compiled into the robot class, not from files at runtime. This is fully allowed.

#### Strategy D: Hybrid — NN for Targeting, Lookup for Movement

Use a small NN for targeting (where the 61-bin GF distribution requires expressiveness) and a simpler lookup table or linear model for movement (where the decision space is smaller: dodge left, dodge right, or stay).

Movement decisions per tick are binary/ternary (orbit direction + whether to reverse), not 61-way distributions. A 3-output model (clockwise, counterclockwise, stop) can be much simpler:
```
Input (features) → Dense(50, ReLU) → Dense(3, Softmax) → {CW, CCW, STOP}
```
Total weights: 12 × 50 + 50 + 50 × 3 + 3 = 803. That's ~3.2 KB. Trivial.

### 7.7 The Insight: Top Bots Are Already Doing Distilled Learning

Here is the conceptual bridge that connects ML knowledge distillation to existing Robocode practice:

**A Visit Count Stats array IS a compressed learned model.** Consider what DrussGT's movement stats actually represent:
- 100+ VCS buffers, each with 171 bins
- Each buffer captures the probability distribution of enemy GuessFactor aim under specific feature conditions
- The buffers are updated every tick with rolling averages (very aggressive decay of ~0.5–1.5)
- At fire time, all buffers are consulted and their outputs combined

This is functionally identical to:
- An **ensemble of lookup tables** (each buffer = one lookup table)
- Each table is **indexed by a subset of features** (each buffer uses 5 of 9 available attributes)
- The ensemble output is **combined via summation** (like a committee machine)
- The tables are **trained online** with exponential decay (like online SGD with high learning rate)

The difference between DrussGT and our proposed ML pipeline:

| Aspect | DrussGT (hand-crafted) | ML Distillation (proposed) |
|--------|----------------------|---------------------------|
| When learning happens | During the battle (online) | Before the battle (offline) + online adaptation |
| Training data | ~300 waves per match (one opponent) | Thousands of battles × hundreds of opponents |
| Feature selection | 9 attributes, chosen by human + genetic algorithm | Learned end-to-end from data |
| Bin boundaries | Fixed at compile time (coarse/regular/fine) | Learned optimally per dimension |
| Combining buffers | Summation with equal weight | Learned combination weights |
| Cold start | Empty — no knowledge at tick 0 | Pre-loaded with opponent-specific or archetype model |
| Adaptation speed | Very fast (rolling avg ~1.0) | Depends on online/offline blend |

**The key insight:** Our offline pre-training is doing the same thing DrussGT's 100+ buffers do — it's just doing it on vastly more data, with a more expressive model, and producing a compressed representation that's ready at tick 0 instead of requiring 3+ rounds to warm up.

Top bots' VCS arrays are a *form of compressed learned knowledge* — just learned during the battle rather than beforehand. Knowledge distillation pre-loads this knowledge, giving the robot a head start that no in-battle-only learner can match.

### 7.8 Online/Offline Blending at Runtime

The distilled model provides a **prior** — a best guess based on historical data. In-battle observations provide a **likelihood** — what this specific opponent is doing right now. The optimal strategy blends both:

**Bayesian blending:**
```
P(GF | current_situation) ∝ P_model(GF | features) × P_vcs(GF | in_battle_observations)
```

In practice, implement as a weighted sum of the model's GF distribution and the in-battle VCS histogram:

```java
float[] predict(float[] features, int[] vcsBins, int totalObservations) {
    float[] modelProbs = distilledModel.forward(features);  // offline knowledge
    float[] vcsProbs = normalize(vcsBins);                  // in-battle knowledge
    
    // Model weight decays as in-battle data accumulates
    float modelWeight = 1.0f / (1.0f + totalObservations / BLEND_RATE);
    float vcsWeight = 1.0f - modelWeight;
    
    float[] combined = new float[61];
    for (int i = 0; i < 61; i++) {
        combined[i] = modelWeight * modelProbs[i] + vcsWeight * vcsProbs[i];
    }
    return combined;
}
```

With `BLEND_RATE = 30`, the model contributes 50% weight after 30 wave observations (~1 round), and 25% after 90 observations (~3 rounds). By round 5, in-battle data dominates — but the model has already paid for itself by providing accurate targeting in the critical first rounds.

### 7.9 Practical Distillation Recipes

#### Recipe 1: The Minimal Viable Model (start here)

**Teacher:** XGBoost trained on 10,000+ battles, predicting GF distribution
**Student:** Single-layer linear model (12 features → 61 outputs)
**Size:** 12 × 61 + 61 = 793 weights = ~3.2 KB
**Inference:** One matrix multiply, ~0.001 ms
**Expected accuracy:** Modest — but even a slight improvement over uniform GF prior in rounds 1–2 is valuable. This is the "does the pipeline work at all?" test.

#### Recipe 2: The Sweet Spot

**Teacher:** Deep MLP or GBM ensemble
**Student:** MLP [12 → 100 → 100 → 61] with ReLU activations
**Size:** ~17,500 weights = ~69 KB (float32) or ~35 KB (float16)
**Inference:** ~0.03 ms
**Expected accuracy:** Should capture nonlinear feature interactions (wall effects, velocity-acceleration combos) that the linear model misses. Comparable to what Gaff achieved, but pre-trained on far more data.

#### Recipe 3: The Full Arsenal

**Teacher:** Transformer or deep ensemble trained per-opponent-archetype
**Student per targeting:** MLP [12 → 128 → 128 → 61]
**Student per movement:** MLP [12 → 64 → 3] (CW/CCW/STOP)
**Per-opponent lookup:** Top-5 GF bins for 1,000-cell discretized feature grid (~15 KB per opponent, loaded from data dir)
**Archetype classifier:** Small decision tree (depth 6) that identifies opponent type from first 20 waves
**Total JAR footprint:** ~150 KB for models + classifier + code
**Total data footprint:** ~200 KB for per-opponent profiles (within RoboRumble limit; unlimited in self-hosted)

### 7.10 Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Feature mismatch between offline and online | Model predictions are meaningless | Shared feature extraction code with unit tests |
| Overfitting to training opponents | Poor generalization to new/updated bots | Regularization, archetype-level models, online blend |
| Student model can't match teacher accuracy | Wasted complexity over simple VCS | Measure distillation gap on held-out data; fall back to VCS if gap >15% |
| GC pauses from model inference | Skip turns | Pre-allocate all arrays; zero `new` in tick loop |
| JAR size exceeds rumble limits | Robot rejected | Quantize weights to int8; reduce model size; use lookup tables |
| Opponent versioning (DrussGT 2.7 vs 2.8) | Stale per-opponent model | Detect behavioral drift via KL divergence between model and observations; discard stale model within 2 rounds |
| CPU time across diverse rumble hardware | Skip turns on slow machines | Benchmark model inference at battle start; fall back to VCS if >2 ms |

*Sources: RoboWiki — [Neural Targeting](https://robowiki.net/wiki/Neural_Targeting), [Gaff/Targeting](https://robowiki.net/wiki/Gaff/Targeting) (Darkcanuck's architecture details), [Visit Count Stats](https://robowiki.net/wiki/Visit_Count_Stats), [Engineer](https://robowiki.net/wiki/Engineer) (SOM-based targeting), [Dynamic Clustering](https://robowiki.net/wiki/Dynamic_Clustering). Back-of-envelope calculations assume a modern 3+ GHz JVM with JIT compilation, consistent with typical RoboRumble volunteer hardware. Gaff's real-time dual-MLP performance confirms that neural network inference is feasible within Robocode's tick budget.*

### 7.11 Adversarial Robustness and Deceptive Strategies

The online/offline blending architecture described in Section 7.8 creates an adaptive system — and any adaptive system can be attacked. This section examines how opponents can exploit our learning, what deceptive strategies already exist in the competitive ecosystem, whether we can turn deception around offensively, and how to defend against it.

#### 7.11.1 Can Our Online Learning Be Deceived?

**Short answer: Yes — and the competitive Robocode ecosystem has been doing exactly this for over a decade.**

Recall the blending formula from Section 7.8:

```
modelWeight = 1.0 / (1.0 + totalObservations / BLEND_RATE)
```

With `BLEND_RATE = 30`, the offline model contributes 50% weight after 30 wave observations (~1 round), and only 25% after 90 observations (~3 rounds). This creates an inherent vulnerability window.

**Attack Vector 1: Behavioral Switching (Bait-and-Switch)**

An opponent moves predictably in rounds 1–3, using a simple pattern (e.g., consistent lateral velocity, no direction changes). Our online VCS accumulates data that says "this enemy always appears at GF +0.7." The BLEND_RATE formula steadily dilutes the offline model's broader distribution in favor of this concentrated in-battle observation. Then in round 4, the opponent switches to a completely different movement — random, or anti-surfer, or just a different oscillation pattern.

The result: our VCS data is now *wrong*, AND our offline prior has been diluted. We're firing at a phantom GF distribution that no longer reflects reality.

**How real is this?** Very real. [Multi-Mode](https://robowiki.net/wiki/Multi-Mode) bots (e.g., PrairieWolf) explicitly switch between different movement algorithms based on which one is succeeding. While their goal is survival rather than attacking the opponent's learning per se, the effect is identical: invalidating previously accumulated statistics. The [Stop-and-Go](https://robowiki.net/wiki/Stop_And_Go) movement explicitly recommends starting with S&G and switching to [Random Movement](https://robowiki.net/wiki/Random_Movement) when it fails — a documented multi-mode strategy that invalidates early-round data.

**Attack Vector 2: Anti-Surfer Targeting (Exploiting Predictable Adaptation)**

If our movement is adaptive (wave surfing), the opponent fires at WHERE we're likely to surf TO, not where we are. This is [Anti-Surfer Targeting](https://robowiki.net/wiki/Anti-Surfer_Targeting), and it's one of the most developed strategy families in competitive Robocode.

Key elements of anti-surfer guns (documented on RoboWiki):
- **Rapid data decay** — since a surfer changes movement each time it's hit, older data becomes irrelevant quickly. The opponent's gun adapts as fast as or faster than our surfing.
- **Lower weighting of non-firing waves** — surfers base their entire movement on dodging fired bullets, so data from non-firing waves is far less predictive. Smart opponents track this distinction.
- **Pattern matching as natural anti-surfer** — because wave surfing inherently simulates a wave-based gun, pattern matchers (which don't use waves) exploit the surfer's structural blind spot.

This isn't theoretical — DrussGT, the long-time #1 bot, uses a Virtual Gun system that selects between an anti-random gun (low decay) and an anti-adaptive gun (high decay), explicitly adapting its targeting to whether the opponent is surfing or not.

**Attack Vector 3: Flattener Poisoning (Against Our Targeting)**

Against our targeting ML model, an opponent can deliberately [flatten their GF profile](https://robowiki.net/wiki/Flattener). A flattener tracks its own movement profile (exactly as our targeting would) and deliberately avoids visiting the same GF bins too often. The result: our learned GF model produces a uniform distribution — no better than random.

DrussGT implements *three* separate flatteners:
1. **Standard flattener** — logs all waves, adjusts movement to flatten the hit profile
2. **Tick-flattener** — generates a wave every single tick (not just on enemy fire events), creating an even finer-grained profile to flatten against
3. **Anti-bullet-shadow flattener** — adds danger to regions likely to contain our bullets based on their movement's analysis of our firing

> [!important] The flattener is the most important adversarial mechanism to understand: it directly attacks the information content of our targeting model. If the opponent's movement profile is truly flat, no amount of ML sophistication will help — there's no signal in the data to learn.

**How these interact with our blending:**

| Attack | Affects | Blending Vulnerability |
|--------|---------|----------------------|
| Behavioral switching | Online VCS data | Old VCS data becomes counter-productive; dilutes useful offline prior |
| Anti-surfer targeting | Our movement decisions | Our surfing model becomes the attack surface itself |
| Flattener | Our targeting model | Both offline and online models converge on uniform distribution |

#### 7.11.2 Known Deceptive Strategies in Robocode

The following strategies are **documented and implemented** by competitive bots. They all involve some form of information warfare — hiding, falsifying, or exploiting the opponent's learning process.

**Anti-Surfer Guns (Documented — Used by Top Bots)**

Aim where the wave surfer will dodge TO. This is not speculation — it's the standard approach for the top 10 bots. DrussGT uses a KNN-based targeting system with attributes including "current GF" — the opponent's current position in the wave's GF space — specifically to predict where surfers will dodge. It also uses "my expected mirror rotation at bullet hit time" as a feature, explicitly modeling the opponent's view of its own waves.

The [Virtual Guns](https://robowiki.net/wiki/Virtual_Guns) system popularized by Ascendant maintains both a general-purpose gun (beats random/non-adaptive movement) and an anti-surfer gun (beats wave surfers), selecting whichever performs better per-opponent. This is counter-deception — using virtual bullets to detect what kind of movement the opponent is using, then selecting the targeting that exploits it.

**Flattening (Documented — Standard Defensive Technique)**

The flattener is simultaneously a *defensive* technique (make yourself hard to hit) and a form of *deception* (you're hiding your real movement tendencies). The movement you *would* use naturally — which the opponent's gun is trying to learn — is deliberately suppressed in favor of a distribution that reveals no exploitable pattern.

DrussGT only enables its flattener when the enemy hit rate exceeds a weighted threshold of 9%. This is itself an adversarial decision: don't waste movement quality on flattening until the opponent's gun is actually dangerous enough to hurt you. Below 9%, the movement optimizes purely for dodging; above 9%, it starts actively hiding.

**Goto Function Behavior Tweaks (Documented in DrussGT)**

DrussGT's surfing movement includes deliberate noise injection: when it has extra time to reach a destination, it tests whether accelerating or decelerating will still allow it to reach the destination on time. If so, it does one of these — not for movement benefit, but explicitly "to throw off pattern matching and KNN/VCS targeting which use things like time-since-decel to determine enemy movements." This is documented deception at the micro-movement level.

**Stop-and-Go as Information Warfare (Documented)**

Stop-and-Go exploits simple guns (HOT, linear, circular) by alternating between stationary and moving states synchronized to enemy fire events. The RoboWiki explicitly notes: "any advanced targeting system will chop this movement into little pieces." But the *recommended response* is itself a deception: "Your robot should try Stop and Go at the beginning of a battle, and if it fails, replace it with a more generic overall movement" — i.e., switch modes to invalidate whatever the opponent learned about your S&G pattern.

**Virtual Guns as Detection/Counter-Deception (Documented)**

VG systems don't just select guns — they're an adversarial detection mechanism. By tracking which virtual gun would hit the opponent, the bot is implicitly classifying the opponent's movement type. DrussGT's VG system checks whether the "anti-random" or "anti-adaptive" gun's firing angle fell within the hit range, then adjusts gun selection accordingly. This is a Bayesian classifier for opponent type, updated in real time.

**Bullet Shielding (Documented — Extreme Deception)**

[Bullet Shielding](https://robowiki.net/wiki/Bullet_Shielding) is a strategy where you fire 0.1-power bullets to intercept enemy bullets mid-flight. This doesn't directly attack their learning, but it does create an extreme information asymmetry: the opponent fires high-power bullets (draining energy) while you deflect them cheaply. DrussGT implements this as an opening gambit, falling back to normal movement after taking 50 bullet damage. EnergyDome is the state-of-the-art implementation.

> [!question] What's NOT well-documented: Deliberate early-round sacrificial misplay — intentionally moving predictably to poison the opponent's learner, then switching. Multi-mode bots do this accidentally (switching when a mode fails), but purposeful "round 1–2 as poison data, round 3+ as real strategy" doesn't appear to be a named or analyzed technique in the RoboWiki corpus. It may exist in bot implementations without being documented as a strategy.

#### 7.11.3 Can WE Use Deception?

**Strategy 1: Sacrificial First Rounds (Speculative — Not Documented as a Named Technique)**

The idea: move predictably in rounds 1–2, accepting extra hits, so the opponent's targeting model learns incorrect patterns. Switch to real movement in round 3+.

**Energy cost estimation:**

In typical matches, a bot takes 3–6 bullet hits per round against a competent opponent with accurate targeting. With deliberately predictable movement:
- Expected additional hits: ~3–5 extra per round (assuming opponent's gun goes from ~25% to ~50% hit rate against linear movement)
- Energy cost per extra hit: ~8–12 energy (depending on opponent bullet power of 1.5–2.5)
- Total sacrifice over 2 rounds: ~50–100 additional energy damage
- Starting energy: 100 per round, but energy carries meaning in survival scoring

**Information gain estimation:**

If the opponent accumulates ~60 incorrect VCS observations over 2 rounds, and their BLEND_RATE equivalent is ~30, those 60 wrong observations contribute ~67% weight to the opponent's targeting model by round 3. If we then switch to real movement:
- The opponent's model is maximally miscalibrated
- Their accuracy could drop by 10–20% for 1–2 rounds while they recalibrate (if they have data decay) or permanently (if they don't)
- With rapid data decay (like DrussGT's rolling average of 0.5–1.5), they'll recalibrate within ~3 rounds
- Without rapid decay, the poison could persist for 5+ rounds

**Verdict: Probably not worth it.** The energy sacrifice in rounds 1–2 is large and certain. The information gain in rounds 3+ is uncertain — it depends entirely on the opponent's decay rate. Against top bots with rapid decay (DrussGT, most modern surfers), the benefit evaporates within 2 rounds. Against weaker bots without decay, we'd win anyway without deception. The strategy optimizes against the wrong part of the opponent population.

> [!important] The fundamental asymmetry: *taking hits costs energy now*; *giving the opponent wrong data pays off later and only conditionally*. In Robocode's survival + bullet damage scoring, energy NOW is more valuable than statistical advantage LATER.

**Strategy 2: Movement Noise Injection (Documented — DrussGT Does This)**

Instead of sacrificial rounds, inject deliberate noise into otherwise optimal movement. DrussGT already does this with its goto function tweaks: adding unnecessary acceleration/deceleration when time permits. We could implement a similar technique:

```java
if (timeToDestination > minimumRequired + NOISE_MARGIN) {
    // Inject random acceleration/deceleration to confuse pattern matchers
    // and KNN guns using time-since-decel as a feature
    if (random() < NOISE_PROBABILITY) {
        acceleration = randomChoice(-1, 0, 1);
    }
}
```

This is cheap (no extra hits taken) and continuously degrades the opponent's feature-based targeting. **This is viable and recommended.**

**Strategy 3: Bullet Power Deception (Speculative but Grounded)**

Opponents detect our fire events via energy drops (our energy decreases by bullet power). They use the detected power to calculate bullet speed and generate waves. If we fire at varying powers, the opponent's energy-drop detection becomes noisier.

Specific technique: occasionally fire at bullet power ≠ expected power, or decelerate/hit walls to create non-fire energy drops that the opponent misinterprets as fire events. DrussGT's enemy gunheat tracking tries to filter spurious energy drops by tracking expected gun heat — but many bots don't have this sophistication.

However, DrussGT's approach is the correct defense: track gunheat and only register energy drops as fire events when gunheat = 0. Against bots that do this correctly, bullet power deception is neutralized.

**Verdict:** Useful against mid-tier bots that don't track gunheat. Ineffective against top bots. On the margin, worthwhile if cheap to implement.

**Strategy 4: Fire Timing Deception (Speculative)**

Sometimes skip a fire opportunity to mess up the opponent's gun heat tracking. If they expect us to fire every time gunheat = 0, a skipped fire creates a phase mismatch in their wave generation.

**Verdict: Counterproductive.** Skipping fire opportunities directly reduces our bullet damage score. The information warfare benefit is far smaller than the scoring loss. Top bots track gunheat precisely and don't assume constant firing anyway.

**Strategy 5: Using the Flattener Pattern Offensively (Documented Technique, Novel Application)**

We could flatten our own *targeting* profile. Instead of always aiming at the peak of our GF distribution, occasionally aim at non-peak bins. This prevents the opponent's surfer from learning our gun's true profile and concentrating its dodging there.

This is exactly what DrussGT's flattener defends against from the movement side — but applying the concept to targeting is the inverse. The tradeoff: lower expected hit rate per shot in exchange for making the opponent's surfing less effective.

**Verdict:** Potentially valuable against elite surfers who perfectly model our gun. Against weaker opponents, the hit rate loss is pure waste. Could be gated behind a virtual gun comparison: only flatten our targeting profile when our anti-adaptive gun is losing to the opponent's surfing.

#### 7.11.4 Defensive Measures for Our Online/Offline Blend

These are concrete mechanisms to protect our blending system against the attack vectors in Section 7.11.1.

**Defense 1: Minimum Offline Weight Floor**

Never let the model weight drop below a floor, regardless of accumulated observations.

```java
float modelWeight = Math.max(
    MIN_MODEL_WEIGHT,     // floor: e.g., 0.15 (15%)
    1.0f / (1.0f + totalObservations / BLEND_RATE)
);
```

With `MIN_MODEL_WEIGHT = 0.15`:
- The offline model always contributes at least 15% to the prediction
- Even after 500 observations, the prior anchors the distribution
- If the opponent switches behavior, the floor ensures the prior can "catch" the real distribution faster than rebuilding from online data alone

**Why 15%?** High enough to anchor against behavioral switching. Low enough not to override strong in-battle evidence. This is tunable and should be optimized empirically. DrussGT's approach of using very low rolling averages (0.5–1.5) is the equivalent — it naturally forgets old data quickly, acting as implicit drift detection.

**Defense 2: Drift Detection via KL Divergence**

Compare our in-battle VCS distribution to the offline model's prediction. If they diverge sharply, suspect behavioral switching.

```java
float klDivergence = 0;
for (int i = 0; i < bins; i++) {
    if (vcsProbs[i] > 0 && modelProbs[i] > 0) {
        klDivergence += vcsProbs[i] * Math.log(vcsProbs[i] / modelProbs[i]);
    }
}

if (klDivergence > DRIFT_THRESHOLD) {
    // Suspect behavioral switching — INCREASE model weight
    modelWeight = Math.min(0.6f, modelWeight * DRIFT_BOOST);
    // Also: reset VCS bins with partial decay (keep recent, discard old)
    decayVcsBins(VCS_RESET_FACTOR);  // e.g., multiply all bins by 0.3
}
```

**Intuition:** If the opponent has been moving one way and suddenly moves differently, the VCS data and the model will diverge. Rather than trusting the suddenly-wrong VCS data, boost the model weight — the model has seen thousands of battles and is a better prior than 30 observations that may be poisoned.

The threshold requires tuning. Too sensitive → constant false alarms as natural variation triggers resets. Too insensitive → misses real behavioral switches. Start with `DRIFT_THRESHOLD = 0.5` (moderate divergence) and tune empirically.

**Defense 3: Per-Round VCS Segmentation with Anomaly Detection**

Don't pool all rounds into a single VCS histogram. Track per-round distributions and detect inter-round shifts:

```java
float[][] perRoundVcs = new float[MAX_ROUNDS][NUM_BINS];
int currentRound;

void onRoundEnd() {
    if (currentRound > 0) {
        float kl = klDivergence(perRoundVcs[currentRound], perRoundVcs[currentRound - 1]);
        if (kl > ROUND_SHIFT_THRESHOLD) {
            // Opponent changed strategy between rounds
            // Option A: Discard pre-shift data entirely
            // Option B: Down-weight pre-shift data by 0.3x
            applyRoundDecay(currentRound - 1, SHIFT_DECAY_FACTOR);
        }
    }
}
```

**This addresses behavioral switching directly.** If the opponent moves differently in round 4 vs. round 3, the per-round comparison detects it. The older data is then decayed, preventing it from poisoning the blend.

Note: DrussGT already implements a related concept — its stat buffers use rolling averages of 0.5–1.5, meaning only the last 2–3 observations meaningfully contribute. This is an implicit and aggressive form of "forget old data."

**Defense 4: Flattener Detection (Against Our Targeting)**

If the opponent is deliberately flattening their GF profile, our targeting model should detect this and respond.

```java
float uniformity = entropyOfDistribution(vcsProbs) / Math.log(NUM_BINS);
// uniformity ∈ [0, 1]; 1.0 = perfectly uniform (maximum entropy)

if (uniformity > FLATNESS_THRESHOLD) {  // e.g., 0.85
    // Suspect flattener — switch targeting strategy
    // Option 1: Use anti-surfer gun (if available)
    // Option 2: Use pattern matching (which flatteners don't specifically counter)
    // Option 3: Fire at random GF within escape angle (accept low hit rate, save energy)
    activateAntiFlattenerGun();
}
```

**Why pattern matching as counter?** The RoboWiki explicitly notes that pattern matching does "relatively well against wave surfers by its very nature: wave surfing inherently simulates a gun that uses waves and/or GuessFactors, so a targeting system that does not use either cannot be dodged as well." A flattener specifically flattens against GF-based guns. A pattern matcher uses different features (recent movement sequence) and may find structure that the flattener isn't hiding.

**Defense 5: Gunheat-Validated Wave Generation**

To prevent the opponent from confusing our wave generation with false energy drops (wall hits, bullet-bullet collisions), implement gunheat tracking like DrussGT:

```java
float enemyGunHeat;  // estimated, decreases by coolingRate per tick

void onScannedRobot(ScannedRobotEvent e) {
    float energyDrop = lastEnemyEnergy - e.getEnergy();
    if (energyDrop > 0 && energyDrop <= 3.0 && enemyGunHeat <= 0) {
        // Legitimate fire event — create wave
        createEnemyWave(energyDrop);
        enemyGunHeat = 1.0 + energyDrop / 5.0;  // gun heat from firing
    }
    enemyGunHeat -= COOLING_RATE;  // 0.1 per tick by default
}
```

This is standard practice among top bots but essential for adversarial robustness — without it, wall-hit energy drops create phantom waves that pollute our surfing data.

**Summary: Defense Priority Ranking**

| Priority | Defense | Cost | Protects Against |
|----------|---------|------|-----------------|
| 1 (Essential) | Gunheat validation | Trivial | Phantom waves, energy drop spoofing |
| 2 (Essential) | Minimum model weight floor | Trivial | Behavioral switching, data poisoning |
| 3 (High) | Per-round drift detection | Low | Inter-round strategy changes |
| 4 (Medium) | KL divergence → model boost | Medium | Mid-round behavioral switching |
| 5 (Medium) | Flattener detection | Medium | Movement flatteners degrading our gun |
| 6 (Low) | Movement noise injection | Trivial | Pattern matchers, KNN feature exploitation |

> [!decision] **Architectural recommendation:** Implement defenses 1–3 from the start. They're cheap and protect against the most common adversarial patterns in the Robocode ecosystem. Defenses 4–5 should be added when our bot reaches a level where it faces opponents sophisticated enough to employ flatteners and behavioral switching deliberately. Defense 6 (noise injection) is a "free" improvement that should be included regardless.

*Sources: RoboWiki — [Anti-Surfer Targeting](https://robowiki.net/wiki/Anti-Surfer_Targeting), [Flattener](https://robowiki.net/wiki/Flattener), [Adaptive Movement](https://robowiki.net/wiki/Adaptive_Movement), [DrussGT/Understanding DrussGT](https://robowiki.net/wiki/DrussGT/Understanding_DrussGT) (stats system, flattener, goto tweaks, virtual guns, bullet shielding), [Virtual Guns](https://robowiki.net/wiki/Virtual_Guns) (Ascendant's popularization of anti-random + anti-adaptive selection), [Stop And Go](https://robowiki.net/wiki/Stop_And_Go) (multi-mode switching), [Multi-Mode](https://robowiki.net/wiki/Multi-Mode), [Bullet Shielding](https://robowiki.net/wiki/Bullet_Shielding) (EnergyDome implementation), [Melee Strategy](https://robowiki.net/wiki/Melee_Strategy). Energy cost calculations based on game physics from Section 1. Speculative strategies are explicitly marked; all other claims are grounded in documented implementations.*

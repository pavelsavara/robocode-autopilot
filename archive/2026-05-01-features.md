---
tags: [projects, robocode, ML]
created: 2026-04-12
updated: 2026-04-12
sources: 1
---

# Stage 2: Feature Engineering Specification

## 1. Overview

This document defines the complete feature set for the Stage 2 feature engineering component. Stage 2 is a standalone Java program that reads per-tick CSV data produced by Stage 1 (god-view ground truth) and transforms it into ML-ready features.

**Constraints:**
- Uses ONLY data observable by the robot through its API (ScannedRobotEvent, HitByBulletEvent, etc.)
- Does NOT perform prediction or learning — only computes features
- Exception: energy drop detection (inferring opponent fired) is permitted as a universally known technique
- All angles in **radians** unless otherwise noted
- All distances in **pixels**
- All times in **ticks** (1 tick = 1 turn)

**Learning domains served:**
- **A** — Opponent movement prediction
- **B** — Opponent firing detection (energy/angle/timing)
- **C** — Our firing optimization
- **D** — Our movement optimization
- **E** — Strategy/tactics adaptation

---

## 2. Physics Reference

Every formula below has been verified against the Robocode source code (`robocode.api/src/main/java/robocode/Rules.java`, `robocode.battle/src/main/java/net/sf/robocode/battle/peer/RobotPeer.java`, `BulletPeer.java`).

### 2.1 Coordinate System

| Property | Value | Verified |
|---|---|---|
| Origin | Bottom-left corner of battlefield | ✅ `Rules.java` |
| X axis | Positive rightward | ✅ |
| Y axis | Positive upward | ✅ |
| Heading 0 | North (up) | ✅ |
| Rotation | Clockwise (0=N, π/2=E, π=S, 3π/2=W) | ✅ |
| Robot size | 36×36 pixels | ✅ `RobotPeer.WIDTH=36, HEIGHT=36` |
| Robot center offset | 18 pixels from each edge | ✅ `HALF_WIDTH_OFFSET=18` |

**Critical note:** Robocode headings are NOT standard math angles. Standard math: 0=East, counter-clockwise. Robocode: 0=North, clockwise. The conversion is: `mathAngle = π/2 - robocodeHeading`. All features in this document use the Robocode convention unless explicitly stated.

### 2.2 Movement Physics

| Formula | Expression | Source | Verified |
|---|---|---|---|
| Max velocity | 8 px/tick | `Rules.MAX_VELOCITY = 8` | ✅ |
| Acceleration | +1 px/tick² (forward) | `Rules.ACCELERATION = 1` | ✅ |
| Deceleration | −2 px/tick² (braking) | `Rules.DECELERATION = 2` | ✅ |
| Position update | `x += velocity * sin(heading)` | `RobotPeer.updateMovement()` | ✅ |
| | `y += velocity * cos(heading)` | | ✅ |

**Velocity update** (from `RobotPeer.getNewVelocity()`):
```
if moving forward toward target:
    newVelocity = clamp(velocity - DECELERATION, goalVelocity, velocity + ACCELERATION)
if moving backward (reversing):
    newVelocity = clamp(velocity - ACCELERATION, goalVelocity, velocity + maxDecel(-velocity))
```
Where `maxDecel(speed)` handles the transition from braking to reverse acceleration.

### 2.3 Rotation Physics

| Component | Max turn rate | Formula | Verified |
|---|---|---|---|
| **Robot body** | 10°/tick at v=0 | `(10 − 0.75 × |velocity|)` deg/tick | ✅ `Rules.getTurnRate()` |
| **Gun** | 20°/tick | Fixed rate | ✅ `Rules.GUN_TURN_RATE = 20` |
| **Radar** | 45°/tick | Fixed rate | ✅ `Rules.RADAR_TURN_RATE = 45` |

**Body turn rate in detail:**

The `Rules.java` formula is:
```
turnRate = MAX_TURN_RATE − 0.75 * abs(velocity)  // in degrees
```

The actual `RobotPeer.updateHeading()` implementation uses an equivalent form:
```java
turnRate = min(maxTurnRate, (0.4 + 0.6 * (1 - abs(velocity) / MAX_VELOCITY)) * MAX_TURN_RATE_RADIANS)
```

Algebraically equivalent: `(0.4 + 0.6 × (1 − |v|/8)) × 10 = 10 − 0.75 × |v|` ✅

| Velocity (px/tick) | Turn rate (deg/tick) | Turn rate (rad/tick) |
|---|---|---|
| 0 | 10.00 | 0.17453 |
| 2 | 8.50 | 0.14835 |
| 4 | 7.00 | 0.12217 |
| 6 | 5.50 | 0.09599 |
| 8 | 4.00 | 0.06981 |

### 2.4 Bullet Physics

| Formula | Expression | Source | Verified |
|---|---|---|---|
| Bullet speed | `20 − 3 × power` px/tick | `Rules.getBulletSpeed()` | ✅ |
| Bullet damage | `4 × power` (if power ≤ 1) | `Rules.getBulletDamage()` | ✅ |
| | `4 × power + 2 × (power − 1)` (if power > 1) | | ✅ |
| Energy return on hit | `3 × power` | `Rules.getBulletHitBonus()` | ✅ |
| Gun heat on fire | `1 + power / 5` | `Rules.getGunHeat()` | ✅ |
| Gun cooling rate | Configurable, default 0.1/tick | `BattleRules.getGunCoolingRate()` | ✅ |
| Power range | [0.1, 3.0] | `Rules.MIN/MAX_BULLET_POWER` | ✅ |
| Bullet radius (collision) | 3 pixels | `BulletPeer.RADIUS = 3` | ✅ |

**Bullet speed/damage reference table:**

| Power | Speed (px/tick) | Damage | Energy return |
|---|---|---|---|
| 0.1 | 19.7 | 0.4 | 0.3 |
| 0.5 | 18.5 | 2.0 | 1.5 |
| 1.0 | 17.0 | 4.0 | 3.0 |
| 1.5 | 15.5 | 5.0 + 1.0 = 6.0 | 4.5 |
| 2.0 | 14.0 | 8.0 + 2.0 = 6.0... | 6.0 |
| 2.0 | 14.0 | 4×2 + 2×(2−1) = 10.0 | 6.0 |
| 3.0 | 11.0 | 4×3 + 2×(3−1) = 16.0 | 9.0 |

*(Corrected table — damage for power > 1 is `4p + 2(p−1) = 6p − 2`.)*

### 2.5 Collision Physics

| Event | Damage | Source | Verified |
|---|---|---|---|
| **Wall hit** | `max(abs(velocity) / 2 − 1, 0)` | `Rules.getWallHitDamage()` | ✅ |
| **Robot collision** | 0.6 per robot (both take it) | `Rules.ROBOT_HIT_DAMAGE = 0.6` | ✅ |
| **Ram bonus** | +1.2 to the ramming robot's score (not damage) | `Rules.ROBOT_HIT_BONUS = 1.2` | ✅ |

**Note:** Wall hit damage only applies to AdvancedRobots (verified in `RobotPeer.checkWallCollision()`: `if (statics.isAdvancedRobot())`). Standard robots don't take wall damage.

### 2.6 Processing Loop Order

Verified from `Battle.runTurn()` and the wiki:

1. Battle view painted
2. Robots execute code (fire commands queued)
3. `time++`
4. **Bullets move** and check collisions (wall, robot, bullet-bullet)
5. **Robots move**: heading → gun heading → radar heading → velocity/position → gun heat cools
6. Robots scan
7. Robots resume
8. Event queue processed

**Key implication:** Bullets fire at the gun heading BEFORE the gun turns in that tick. A bullet's initial position is the robot's center at fire time.

---

## 3. Observation Model

### 3.1 What a Robot Can Observe

**Own state (every tick):**
- Position (`getX()`, `getY()`)
- Heading, gun heading, radar heading
- Velocity
- Energy
- Gun heat
- Time (tick number)
- Battlefield dimensions
- Gun cooling rate

**From ScannedRobotEvent:**
- `distance` — distance to scanned robot
- `bearing` — bearing to scanned robot, **relative to our heading**
- `heading` — **absolute heading** of scanned robot
- `velocity` — **signed velocity** of scanned robot (negative = moving backward)
- `energy` — energy of scanned robot
- `name` — name of scanned robot

**From HitByBulletEvent:**
- `bearing` — bearing of bullet relative to our heading
- `power` — power of the bullet
- `heading` — bullet's heading
- `name` — name of the robot that fired

**From BulletHitEvent:**
- `energy` — remaining energy of bot we hit
- `name` — name of bot we hit

**From HitRobotEvent:**
- `bearing`, `energy`, `atFault`

### 3.2 What a Robot CANNOT Observe

- Opponent's gun heading or radar heading
- Opponent's gun heat
- Whether opponent just fired (except via energy drop heuristic)
- Opponent's internal commands (target distance, turn remaining, etc.)
- Bullets in flight (no visibility between fire and impact)
- Other robots' scan data (in 1v1)

### 3.3 Scan Timing

A scan only occurs when the radar sweeps over the opponent. The **radar scan radius** is 1200 pixels (`Rules.RADAR_SCAN_RADIUS = 1200`). Scan data may be 1+ ticks stale depending on radar management. The feature engineering program should handle potentially stale data by tracking `ticks_since_scan`.

### 3.4 Derived Position

Opponent position is not directly given but is computable:
```
opponent_x = our_x + distance × sin(absolute_bearing_to_opponent)
opponent_y = our_y + distance × cos(absolute_bearing_to_opponent)
where:
  absolute_bearing_to_opponent = our_heading + relative_bearing
```

---

## 4. Feature Catalog

### 4.1 Category 1: Spatial Features

| # | Feature Name | Formula | Unit | Domains | Observable By |
|---|---|---|---|---|---|
| 1 | `distance` | Direct from scan | px | A,B,C,D,E | Both |
| 2 | `distance_squared` | `distance²` (avoids sqrt in downstream math) | px² | C | Both |
| 3 | `bearing_to_opponent_abs` | `our_heading + scan.bearing` | rad | A,C | Us |
| 4 | `bearing_to_opponent_rel` | Direct from scan | rad | A,C | Us |
| 5 | `bearing_from_opponent_abs` | `normalAbsoluteAngle(bearing_to_opponent_abs + π)` | rad | A,D | Us |
| 6 | `bearing_from_opponent_rel` | `normalRelativeAngle(bearing_from_opponent_abs − opponent_heading)` | rad | A,D | Us |
| 7 | `relative_heading` | `normalRelativeAngle(opponent_heading − our_heading)` | rad | A,C | Us |
| 8 | `our_x` | `getX()` | px | D,E | Us |
| 9 | `our_y` | `getY()` | px | D,E | Us |
| 10 | `opponent_x` | Derived (see §3.4) | px | A,C | Us |
| 11 | `opponent_y` | Derived (see §3.4) | px | A,C | Us |
| 12 | `our_x_norm` | `our_x / battlefieldWidth` | [0,1] | D,E | Us |
| 13 | `our_y_norm` | `our_y / battlefieldHeight` | [0,1] | D,E | Us |
| 14 | `opponent_x_norm` | `opponent_x / battlefieldWidth` | [0,1] | A,C | Us |
| 15 | `opponent_y_norm` | `opponent_y / battlefieldHeight` | [0,1] | A,C | Us |
| 16 | `our_dist_to_wall_n` | `battlefieldHeight − 18 − our_y` | px | D | Us |
| 17 | `our_dist_to_wall_s` | `our_y − 18` | px | D | Us |
| 18 | `our_dist_to_wall_e` | `battlefieldWidth − 18 − our_x` | px | D | Us |
| 19 | `our_dist_to_wall_w` | `our_x − 18` | px | D | Us |
| 20 | `opponent_dist_to_wall_n` | `battlefieldHeight − 18 − opponent_y` | px | A,C | Us |
| 21 | `opponent_dist_to_wall_s` | `opponent_y − 18` | px | A,C | Us |
| 22 | `opponent_dist_to_wall_e` | `battlefieldWidth − 18 − opponent_x` | px | A,C | Us |
| 23 | `opponent_dist_to_wall_w` | `opponent_x − 18` | px | A,C | Us |
| 24 | `our_dist_to_wall_min` | `min(wall_n, wall_s, wall_e, wall_w)` for us | px | D | Us |
| 25 | `opponent_dist_to_wall_min` | `min(wall_n, wall_s, wall_e, wall_w)` for opponent | px | A,C | Us |

**Note:** The 18-pixel offset is `HALF_WIDTH_OFFSET` — the robot's center cannot be closer than 18px to any wall.

### 4.2 Category 2: Movement Features

| # | Feature Name | Formula | Unit | Domains | Observable By |
|---|---|---|---|---|---|
| 26 | `our_velocity` | `getVelocity()` | px/tick | C,D | Us |
| 27 | `our_heading` | `getHeadingRadians()` | rad | C,D | Us |
| 28 | `opponent_velocity` | From scan (signed; negative = backward) | px/tick | A,B,C | Us |
| 29 | `opponent_heading` | From scan (absolute) | rad | A,C | Us |
| 30 | `opponent_speed` | `abs(opponent_velocity)` | px/tick | A,C | Us |
| 31 | `opponent_lateral_velocity` | `opponent_velocity × sin(opponent_heading − bearing_from_opponent_abs)` | px/tick | A,C | Us |
| 32 | `opponent_advancing_velocity` | `opponent_velocity × cos(opponent_heading − bearing_from_opponent_abs)` | px/tick | A,B,D | Us |
| 33 | `our_lateral_velocity` | `our_velocity × sin(our_heading − bearing_to_opponent_abs)` | px/tick | D | Us |
| 34 | `our_advancing_velocity` | `our_velocity × cos(our_heading − bearing_to_opponent_abs)` | px/tick | D | Us |
| 35 | `opponent_heading_delta` | `normalRelativeAngle(current_heading − prev_heading) / delta_ticks` | rad/tick | A,C | Us |
| 36 | `opponent_velocity_delta` | `(current_velocity − prev_velocity) / delta_ticks` | px/tick² | A | Us |
| 37 | `opponent_acceleration` | Signed: positive = speeding up, negative = braking/reversing | px/tick² | A | Us |
| 38 | `opponent_distance_from_prev` | `hypot(dx, dy)` between consecutive scanned positions | px | A | Us |
| 39 | `opponent_lateral_direction` | `sign(opponent_lateral_velocity)`: +1=clockwise, −1=counter-clockwise, 0=stationary | {−1,0,1} | A,C | Us |
| 40 | `opponent_max_turn_rate` | `(10 − 0.75 × abs(opponent_velocity))` converted to radians | rad/tick | A,C | Us |
| 41 | `opponent_is_decelerating` | `abs(current_velocity) < abs(prev_velocity)` | bool | A | Us |
| 42 | `opponent_is_reversing` | `sign(current_velocity) ≠ sign(prev_velocity)` and both ≠ 0 | bool | A | Us |

**Lateral/advancing velocity decomposition explained:**

Lateral velocity is the component of the opponent's movement perpendicular to the line connecting the two robots (the "bearing line"). Advancing velocity is the component along that line. Both are critical for targeting.

```
bearing_from_opponent = absolute angle from opponent toward us
lateral_vel = opponent_vel × sin(opponent_heading − bearing_from_opponent)
advancing_vel = opponent_vel × cos(opponent_heading − bearing_from_opponent)
```

A positive `lateral_velocity` means the opponent moves clockwise relative to us; negative means counter-clockwise.
A positive `advancing_velocity` means the opponent is approaching; negative means retreating.

### 4.3 Category 3: Energy Features

| # | Feature Name | Formula | Unit | Domains | Observable By |
|---|---|---|---|---|---|
| 43 | `our_energy` | `getEnergy()` | energy | B,C,D,E | Us |
| 44 | `opponent_energy` | From scan | energy | B,C,D,E | Us |
| 45 | `energy_advantage` | `our_energy − opponent_energy` | energy | C,E | Us |
| 46 | `energy_ratio` | `our_energy / (our_energy + opponent_energy)` | [0,1] | C,E | Us |
| 47 | `opponent_energy_delta` | `current_opponent_energy − prev_opponent_energy` | energy | B | Us |
| 48 | `our_energy_delta` | `current_our_energy − prev_our_energy` | energy | D | Us |
| 49 | `opponent_fired` | Energy drop detection flag (see §6) | bool | B,D | Us |
| 50 | `opponent_fire_power` | Inferred bullet power from energy drop (see §6) | energy | B,C,D | Us |
| 51 | `opponent_bullet_speed` | `20 − 3 × opponent_fire_power` (when detected) | px/tick | B,D | Us |
| 52 | `opponent_bullet_damage` | Computed from inferred power (see §2.4) | energy | D,E | Us |

### 4.4 Category 4: Bullet & Wave Features

| # | Feature Name | Formula | Unit | Domains | Observable By |
|---|---|---|---|---|---|
| 53 | `our_bullet_speed` | `20 − 3 × our_fire_power` | px/tick | C | Us |
| 54 | `our_bullet_travel_time` | `distance / our_bullet_speed` | ticks | C | Us |
| 55 | `mea_for_our_bullet` | `asin(8.0 / our_bullet_speed)` | rad | C | Us |
| 56 | `mea_for_opponent_bullet` | `asin(8.0 / opponent_bullet_speed)` (when detected) | rad | D | Us |
| 57 | `opponent_guess_factor` | `(bearing_offset × lateral_direction) / mea` normalized to [−1, 1] | unitless | C | Us |
| 58 | `our_wave_distance` | `our_bullet_speed × ticks_since_we_fired` | px | C | Us |
| 59 | `our_wave_remaining` | `distance − our_wave_distance` (negative = wave passed) | px | C | Us |
| 60 | `opponent_wave_distance` | `opponent_bullet_speed × ticks_since_opponent_fired` (when detected) | px | D | Us |
| 61 | `opponent_wave_remaining` | `distance − opponent_wave_distance` | px | D | Us |
| 62 | `opponent_wave_eta` | `max(0, opponent_wave_remaining / opponent_bullet_speed)` | ticks | D | Us |

**Maximum Escape Angle (MEA) derivation:**

From the law of sines applied to the bullet/robot triangle:
```
A = asin(Vr / Vb × sin(C))
```
Worst case is `C = π/2` → `sin(C) = 1`:
```
MEA = asin(MAX_VELOCITY / bulletSpeed) = asin(8.0 / bulletSpeed)
```

| Bullet power | Bullet speed | MEA (rad) | MEA (deg) |
|---|---|---|---|
| 0.1 | 19.7 | 0.4163 | 23.85° |
| 0.5 | 18.5 | 0.4446 | 25.47° |
| 1.0 | 17.0 | 0.4888 | 28.00° |
| 1.5 | 15.5 | 0.5423 | 31.07° |
| 2.0 | 14.0 | 0.6082 | 34.85° |
| 2.5 | 12.5 | 0.6941 | 39.77° |
| 3.0 | 11.0 | 0.8145 | 46.66° |

### 4.5 Category 5: Targeting Geometry

| # | Feature Name | Formula | Unit | Domains | Observable By |
|---|---|---|---|---|---|
| 63 | `head_on_angle` | `bearing_to_opponent_abs` (just aim at current position) | rad | C | Us |
| 64 | `linear_target_angle_exact` | `bearing_to_opponent_abs + asin(opponent_velocity / our_bullet_speed × sin(opponent_heading − bearing_to_opponent_abs))` | rad | C | Us |
| 65 | `linear_target_offset` | `linear_target_angle_exact − bearing_to_opponent_abs` | rad | C | Us |
| 66 | `circular_target_angle` | Iterative: project opponent along circular path until wave reaches (see below) | rad | C | Us |
| 67 | `circular_target_offset` | `circular_target_angle − bearing_to_opponent_abs` | rad | C | Us |
| 68 | `linear_target_x` | Predicted x at bullet arrival (linear assumption) | px | C | Us |
| 69 | `linear_target_y` | Predicted y at bullet arrival (linear assumption) | px | C | Us |
| 70 | `circular_target_x` | Predicted x at bullet arrival (circular assumption) | px | C | Us |
| 71 | `circular_target_y` | Predicted y at bullet arrival (circular assumption) | px | C | Us |

**Linear targeting — exact non-iterative formula:**

Using the law of sines:
```
fireAngle = bearing_to_opponent_abs + asin(Vr / Vb × sin(opponent_heading − bearing_to_opponent_abs))
```
Where `Vr` = opponent velocity, `Vb` = bullet speed. This is exact for constant-velocity straight-line movement.

**Circular targeting — iterative algorithm:**

```java
double predictedX = opponent_x, predictedY = opponent_y;
double predictedHeading = opponent_heading;
double deltaTime = 0;
while ((++deltaTime) * bulletSpeed < hypot(our_x - predictedX, our_y - predictedY)) {
    predictedX += sin(predictedHeading) * opponent_velocity;
    predictedY += cos(predictedHeading) * opponent_velocity;
    predictedHeading += opponent_heading_delta;
    // Clamp to battlefield (18px from walls)
    predictedX = clamp(predictedX, 18, battlefieldWidth - 18);
    predictedY = clamp(predictedY, 18, battlefieldHeight - 18);
}
circularAngle = atan2(predictedX - our_x, predictedY - our_y);
```

### 4.6 Category 6: Timing Features

| # | Feature Name | Formula | Unit | Domains | Observable By |
|---|---|---|---|---|---|
| 72 | `tick` | `getTime()` | ticks | E | Us |
| 73 | `ticks_since_scan` | `current_tick − last_scan_tick` | ticks | A,B,C | Us |
| 74 | `ticks_since_we_fired` | `current_tick − our_last_fire_tick` | ticks | C | Us |
| 75 | `ticks_since_opponent_fired` | `current_tick − opponent_last_inferred_fire_tick` (from energy drop) | ticks | B,D | Us |
| 76 | `our_gun_heat` | `getGunHeat()` | heat | C | Us |
| 77 | `our_gun_heat_ticks` | `ceil(our_gun_heat / gunCoolingRate)` | ticks | C | Us |
| 78 | `our_can_fire` | `our_gun_heat == 0 && our_energy > 0` | bool | C | Us |
| 79 | `opponent_inferred_gun_heat` | Estimated from last inferred fire: `max(0, (1 + power/5) − elapsed_ticks × coolingRate)` | heat | B | Us |
| 80 | `opponent_can_fire_est` | `opponent_inferred_gun_heat ≤ 0` | bool | B,D | Us |
| 81 | `round_number` | `getRoundNum()` | int | E | Us |
| 82 | `ticks_in_round` | Tick count since round start | ticks | E | Us |

### 4.7 Category 7: Battlefield Geometry

| # | Feature Name | Formula | Unit | Domains | Observable By |
|---|---|---|---|---|---|
| 83 | `our_wall_ahead_distance` | Distance to wall along our current heading | px | D | Us |
| 84 | `opponent_wall_ahead_distance` | Distance to wall along opponent's current heading | px | A,C | Us |
| 85 | `our_center_distance` | `hypot(our_x − bfWidth/2, our_y − bfHeight/2)` | px | D,E | Us |
| 86 | `opponent_center_distance` | `hypot(opponent_x − bfWidth/2, opponent_y − bfHeight/2)` | px | A,E | Us |
| 87 | `our_corner_proximity` | `min(hypot(x−cx, y−cy))` for all 4 corners (cx,cy)={(18,18),(18,H-18),(W-18,18),(W-18,H-18)} | px | D | Us |
| 88 | `opponent_corner_proximity` | Same for opponent | px | A | Us |
| 89 | `our_wall_danger` | Ticks until we hit closest wall at current velocity and heading: `our_wall_ahead_distance / abs(our_velocity)` | ticks | D | Us |
| 90 | `opponent_wall_danger` | Same for opponent | ticks | A,C | Us |
| 91 | `battlefield_width` | `getBattleFieldWidth()` | px | E | Us |
| 92 | `battlefield_height` | `getBattleFieldHeight()` | px | E | Us |
| 93 | `battlefield_diagonal` | `hypot(width, height)` | px | E | Us |
| 94 | `distance_norm` | `distance / battlefield_diagonal` | [0,1] | A,C,E | Us |

**Wall-ahead distance calculation:**

For a heading `h` and position `(x, y)`, project a ray from position along heading and find the nearest wall intersection:
```
// Walls are at x=18, x=W-18, y=18, y=H-18
dx = sin(heading), dy = cos(heading)
t_candidates = []
if dx > 0: t_candidates.add((W - 18 - x) / dx)
if dx < 0: t_candidates.add((18 - x) / dx)
if dy > 0: t_candidates.add((H - 18 - y) / dy)
if dy < 0: t_candidates.add((18 - y) / dy)
wall_ahead_distance = min(positive values in t_candidates)
```

---

## 5. Derived & Synthetic Features

These features are computed from multiple raw values and encode higher-level patterns useful for ML.

### 5.1 Movement Pattern Features

| # | Feature Name | Formula | Unit | Domains |
|---|---|---|---|---|
| 95 | `opponent_time_since_direction_change` | Ticks since `lateral_direction` last changed sign | ticks | A |
| 96 | `opponent_time_since_velocity_change` | Ticks since `opponent_velocity` last changed by ≥ 1 | ticks | A |
| 97 | `opponent_distance_since_direction_change` | Cumulative distance opponent traveled since last direction reversal | px | A |
| 98 | `opponent_move_segment_length` | Number of ticks in current movement "segment" (same direction, no reversals) | ticks | A |
| 99 | `opponent_avg_lateral_velocity_10` | Rolling mean of `opponent_lateral_velocity` over last 10 scans | px/tick | A,C |
| 100 | `opponent_avg_lateral_velocity_30` | Rolling mean over last 30 scans | px/tick | A,C |
| 101 | `opponent_heading_delta_variability` | Standard deviation of `opponent_heading_delta` over last 10 scans | rad/tick | A |
| 102 | `opponent_velocity_variability` | Standard deviation of `opponent_velocity` over last 10 scans | px/tick | A |
| 103 | `opponent_is_wall_hugging` | `opponent_dist_to_wall_min < 40` | bool | A,C |
| 104 | `opponent_is_orbiting` | `abs(opponent_lateral_velocity) > 4 && abs(opponent_advancing_velocity) < 2` | bool | A |

### 5.2 Firing Strategy Features

| # | Feature Name | Formula | Unit | Domains |
|---|---|---|---|---|
| 105 | `optimal_fire_power_by_distance` | Heuristic: `min(3.0, max(0.1, 500 / distance²))` — closer = more power | energy | C |
| 106 | `optimal_fire_power_by_energy` | `min(our_energy / 4, 3.0)` — don't spend > 25% of remaining energy | energy | C |
| 107 | `bullet_travel_time_at_power_1` | `distance / 17.0` | ticks | C |
| 108 | `bullet_travel_time_at_power_2` | `distance / 14.0` | ticks | C |
| 109 | `bullet_travel_time_at_power_3` | `distance / 11.0` | ticks | C |
| 110 | `mea_at_power_1` | `asin(8.0 / 17.0)` = 0.4888 rad | rad | C |
| 111 | `mea_at_power_2` | `asin(8.0 / 14.0)` = 0.6082 rad | rad | C |
| 112 | `mea_at_power_3` | `asin(8.0 / 11.0)` = 0.8145 rad | rad | C |
| 113 | `can_hit_with_head_on` | `abs(opponent_lateral_velocity) < 0.5` | bool | C |
| 114 | `targeting_difficulty` | `abs(opponent_lateral_velocity) × distance / our_bullet_speed` — higher = harder to hit | px | C |

### 5.3 Danger Assessment Features

| # | Feature Name | Formula | Unit | Domains |
|---|---|---|---|---|
| 115 | `incoming_bullet_eta` | `opponent_wave_eta` (if opponent fired) | ticks | D |
| 116 | `escape_angle_coverage` | fraction of MEA arc reachable before bullet arrives: `min(1, opponent_wave_eta × max_lateral_speed / (mea × distance))` | [0,1] | D |
| 117 | `our_reachable_gf_range` | Precise: simulate movement forward/backward for `wave_eta` ticks, compute achievable GF range | [−1,1]→ width | D |
| 118 | `opponent_fire_rate` | Shots detected / elapsed ticks (rolling window) | shots/tick | B,E |
| 119 | `opponent_hit_rate` | Opponent hits on us / opponent shots detected | [0,1] | D,E |
| 120 | `our_hit_rate` | Our bullet hits / our shots fired | [0,1] | C,E |

### 5.4 Historical / Smoothed Features

| # | Feature Name | Formula | Unit | Domains |
|---|---|---|---|---|
| 121 | `distance_delta` | Change in distance since last scan | px | A,D |
| 122 | `distance_trend_10` | Linear regression slope of distance over last 10 scans | px/tick | A,E |
| 123 | `energy_advantage_trend` | Change in `energy_advantage` over last 20 ticks | energy | E |
| 124 | `opponent_fire_power_avg` | Average of inferred fire powers over last N shots | energy | B,E |
| 125 | `opponent_fire_power_last_3` | Average of last 3 inferred fire powers | energy | B |

### 5.5 Scan-Based Features

Features derived from radar scan timing and coverage. Become available once scan events are tracked tick-by-tick.

| # | Feature Name | Formula | Unit | Domains |
|---|---|---|---|---|
| 126 | `ticks_between_scans` | `current_scan_tick − prev_scan_tick` | ticks | A,B |
| 127 | `scan_coverage_20` | Fraction of last 20 ticks that had a scan: `scans_in_window / 20` | [0,1] | A,B,C |
| 128 | `scan_coverage_50` | Fraction of last 50 ticks that had a scan | [0,1] | A,B |
| 129 | `scan_arc_width` | `abs(normalRelativeAngle(radar_heading − prev_radar_heading))` | rad | A |
| 130 | `radar_locked` | `ticks_between_scans ≤ 2` sustained for ≥ 5 consecutive scans | bool | A,B |
| 131 | `scan_gap_max_10` | Max gap between scans in last 10 scans | ticks | A,B |
| 132 | `radar_turn_direction` | `sign(normalRelativeAngle(radar_heading − prev_radar_heading))`: +1=CW, −1=CCW | {−1,0,1} | A |
| 133 | `opponent_ticks_invisible` | Ticks since opponent was last scanned (same as `ticks_since_scan` but explicit) | ticks | A,B,C |

### 5.6 GuessFactor Features

GuessFactor (GF) normalizes firing angles to [−1, +1] relative to the Maximum Escape Angle. These features express where the opponent currently sits in GF space for various bullet powers.

| # | Feature Name | Formula | Unit | Domains |
|---|---|---|---|---|
| 134 | `gf_bearing_offset` | `normalRelativeAngle(bearing_to_opponent_abs − our_gun_heading)` | rad | C |
| 135 | `gf_current_at_power_1` | `(lateral_offset × lateral_direction) / mea_at_power_1` clamped to [−1,1] | GF | C |
| 136 | `gf_current_at_power_1_5` | Same formula with `mea = asin(8.0/15.5)` | GF | C |
| 137 | `gf_current_at_power_2` | Same formula with `mea_at_power_2` | GF | C |
| 138 | `gf_current_at_power_3` | Same formula with `mea_at_power_3` | GF | C |
| 139 | `gf_head_on_offset` | Always 0 by definition (head-on = GF 0) — included as constant anchor | GF | C |
| 140 | `gf_linear_prediction_1` | GF that linear targeting would aim at, for power 1.0 | GF | C |
| 141 | `gf_linear_prediction_2` | GF that linear targeting would aim at, for power 2.0 | GF | C |
| 142 | `gf_linear_prediction_3` | GF that linear targeting would aim at, for power 3.0 | GF | C |
| 143 | `gf_circular_prediction_1` | GF that circular targeting would aim at, for power 1.0 | GF | C |
| 144 | `gf_circular_prediction_2` | GF that circular targeting would aim at, for power 2.0 | GF | C |
| 145 | `gf_circular_prediction_3` | GF that circular targeting would aim at, for power 3.0 | GF | C |

**Note:** `lateral_offset` is the angular offset of the opponent from the bearing line, measured perpendicular to it. `lateral_direction` is +1 (clockwise) or −1 (counter-clockwise). The GF is the ratio of this offset to the theoretical MEA.

### 5.7 Wave Surfing Geometry Features

Features that encode the geometry of detected enemy waves (from energy-drop detection) and our escape options relative to them.

| # | Feature Name | Formula | Unit | Domains |
|---|---|---|---|---|
| 146 | `wave_front_distance` | `opponent_bullet_speed × ticks_since_opponent_fired` (distance wave has traveled) | px | D |
| 147 | `wave_time_to_impact` | `max(0, (distance − wave_front_distance) / opponent_bullet_speed)` | ticks | D |
| 148 | `precise_mea_forward` | MEA considering walls, in the forward (current lateral) direction — via precise prediction | rad | D |
| 149 | `precise_mea_backward` | MEA considering walls, in the reverse lateral direction — via precise prediction | rad | D |
| 150 | `orbit_direction` | `sign(our_lateral_velocity)`: +1=clockwise, −1=counter-clockwise relative to opponent | {−1,0,1} | D |
| 151 | `our_gf_on_wave` | Our current position expressed as GF on the nearest enemy wave | GF | D |
| 152 | `our_gf_reachable_forward` | Maximum GF reachable by continuing forward (precise prediction) | GF | D |
| 153 | `our_gf_reachable_backward` | Maximum GF reachable by reversing (precise prediction) | GF | D |
| 154 | `wave_break_ticks` | Ticks until nearest enemy wave reaches our current distance | ticks | D |
| 155 | `active_enemy_waves` | Count of currently tracked enemy waves (from energy drops) | int | D |
| 156 | `nearest_wave_bullet_power` | Power of the nearest incoming wave's bullet | energy | D |
| 157 | `second_wave_eta` | ETA of the second-nearest enemy wave (`NaN` if only 0 or 1 waves active) | ticks | D |

### 5.8 Pattern & Movement Rhythm Features

Features that capture temporal movement patterns, rhythm, and oscillation signatures. Critical for pattern matching and detecting stop-and-go, oscillator, and random movements.

| # | Feature Name | Formula | Unit | Domains |
|---|---|---|---|---|
| 158 | `opponent_direction_change_count_20` | Number of lateral direction reversals in last 20 ticks | int | A |
| 159 | `opponent_direction_change_count_50` | Number of lateral direction reversals in last 50 ticks | int | A |
| 160 | `opponent_direction_change_count_100` | Number of lateral direction reversals in last 100 ticks | int | A |
| 161 | `opponent_time_since_last_zero_velocity` | Ticks since opponent velocity was last 0 (stop-and-go detection) | ticks | A |
| 162 | `opponent_time_at_max_velocity` | Consecutive ticks opponent has been at `abs(velocity) ≥ 7.5` | ticks | A |
| 163 | `opponent_acceleration_state` | Discrete: +1=accelerating (`|v_curr| > |v_prev|`), 0=constant, −1=decelerating | {−1,0,1} | A |
| 164 | `opponent_move_segment_ticks` | Current uninterrupted movement segment length (ticks since last direction change or stop) | ticks | A |
| 165 | `opponent_move_segment_distance` | Distance traveled in current movement segment | px | A |
| 166 | `opponent_stop_frequency_100` | Number of times `velocity == 0` in last 100 ticks | int | A |
| 167 | `opponent_reversal_period_avg` | Mean ticks between lateral direction reversals (over last 10 reversals) | ticks | A |
| 168 | `opponent_reversal_period_std` | Std deviation of ticks between reversals (low = oscillator, high = random) | ticks | A |
| 169 | `opponent_heading_change_rate_10` | Mean of `abs(heading_delta)` over last 10 scans (turning intensity) | rad/tick | A |
| 170 | `opponent_heading_change_rate_30` | Mean of `abs(heading_delta)` over last 30 scans | rad/tick | A |
| 171 | `opponent_velocity_sign_changes_20` | Number of sign changes in velocity over last 20 ticks (including stops) | int | A |
| 172 | `opponent_is_stop_and_go` | `opponent_stop_frequency_100 ≥ 5 && opponent_reversal_period_avg < 30` | bool | A |
| 173 | `opponent_movement_entropy_20` | Shannon entropy of discretized lateral velocity bins over last 20 ticks | bits | A |
| 174 | `opponent_lateral_velocity_variance_10` | Variance of `opponent_lateral_velocity` over last 10 scans | (px/tick)² | A,C |
| 175 | `opponent_pattern_length_est` | Estimated pattern length via autocorrelation of lateral velocity sequence | ticks | A |

### 5.9 Combat State Features

Features that encode the overall match state: who is winning, round history, aggression level, and cumulative damage.

| # | Feature Name | Formula | Unit | Domains |
|---|---|---|---|---|
| 176 | `rounds_won` | Count of rounds we won so far in this battle | int | E |
| 177 | `rounds_lost` | Count of rounds we lost so far | int | E |
| 178 | `rounds_total` | Total rounds completed: `rounds_won + rounds_lost` | int | E |
| 179 | `win_rate` | `rounds_won / max(1, rounds_total)` | [0,1] | E |
| 180 | `energy_advantage_ema` | Exponential moving average of `energy_advantage` with α=0.05 | energy | E |
| 181 | `survival_probability` | `our_energy / (our_energy + opponent_energy)` (= energy_ratio, alias for clarity) | [0,1] | D,E |
| 182 | `damage_dealt_this_round` | Cumulative bullet damage dealt to opponent this round | energy | C,E |
| 183 | `damage_received_this_round` | Cumulative bullet damage received from opponent this round | energy | D,E |
| 184 | `net_damage_this_round` | `damage_dealt − damage_received` | energy | E |
| 185 | `our_shots_fired_this_round` | Count of bullets we have fired this round | int | C,E |
| 186 | `opponent_shots_detected_this_round` | Count of opponent fires detected (energy drops) this round | int | B,E |
| 187 | `aggression_index` | `opponent_shots_detected_this_round / max(1, ticks_in_round) × opponent_fire_power_avg` | energy/tick | B,E |
| 188 | `our_aggression_index` | `our_shots_fired_this_round / max(1, ticks_in_round) × our_avg_fire_power` | energy/tick | C,E |
| 189 | `energy_at_round_start` | Always 100 for standard bots — included for completeness with droids/team bots | energy | E |
| 190 | `ticks_since_damage_received` | Ticks since last `HitByBulletEvent` or `HitRobotEvent` with damage | ticks | D,E |
| 191 | `ticks_since_damage_dealt` | Ticks since last `BulletHitEvent` | ticks | C,E |
| 192 | `our_bullet_hit_count` | Running count of our bullets that hit this round | int | C,E |
| 193 | `opponent_bullet_hit_count` | Running count of opponent bullets that hit us this round | int | D,E |

### 5.10 Wall Interaction Features

Features encoding proximity and danger from walls, including directional wall distances, collision risk, and corner geometry.

| # | Feature Name | Formula | Unit | Domains |
|---|---|---|---|---|
| 194 | `our_dist_to_wall_forward` | Distance to wall along our current heading (same as `our_wall_ahead_distance` #83, but included in this group for completeness) | px | D |
| 195 | `our_dist_to_wall_backward` | Distance to wall along *opposite* of our current heading | px | D |
| 196 | `ticks_until_our_wall_collision` | `our_dist_to_wall_forward / max(0.01, abs(our_velocity))` (∞ if moving away) | ticks | D |
| 197 | `wall_smooth_angle_forward` | Angle adjustment needed to avoid hitting wall within stick distance (~120 px), forward direction | rad | D |
| 198 | `wall_smooth_angle_backward` | Same for backward direction | rad | D |
| 199 | `our_corner_proximity_perpendicular` | `min(our_dist_to_wall_n, our_dist_to_wall_s) + min(our_dist_to_wall_e, our_dist_to_wall_w)` — lower = deeper in corner | px | D |
| 200 | `our_wall_danger_zone` | `our_dist_to_wall_min < 36` (within one robot-width of wall) | bool | D |
| 201 | `opponent_dist_to_wall_forward` | Distance to wall along opponent's current heading | px | A,C |
| 202 | `opponent_dist_to_wall_backward` | Distance to wall opposite opponent's heading | px | A,C |
| 203 | `opponent_corner_proximity_perpendicular` | Same formula as #199 for opponent | px | A,C |
| 204 | `opponent_wall_danger_zone` | `opponent_dist_to_wall_min < 36` | bool | A,C |
| 205 | `opponent_wall_escape_fraction_forward` | Fraction of MEA arc reachable in forward direction considering walls: `precise_mea_forward / theoretical_mea` | [0,1] | A,C |
| 206 | `opponent_wall_escape_fraction_backward` | Same for backward direction | [0,1] | A,C |
| 207 | `our_wall_escape_fraction_forward` | Fraction of our MEA arc reachable forward considering walls | [0,1] | D |
| 208 | `our_wall_escape_fraction_backward` | Same backward | [0,1] | D |

### 5.11 Opponent Modeling Features (Observable Only)

Features that model the opponent's behavioral patterns from observable data. These infer strategic tendencies without violating the observation model.

| # | Feature Name | Formula | Unit | Domains |
|---|---|---|---|---|
| 209 | `opponent_fire_frequency_50` | Opponent shots detected / 50 ticks (rolling window) | shots/tick | B,E |
| 210 | `opponent_fire_frequency_100` | Opponent shots detected / 100 ticks | shots/tick | B,E |
| 211 | `opponent_avg_fire_power_all` | Mean of all inferred fire powers this round | energy | B,E |
| 212 | `opponent_fire_power_std` | Std deviation of inferred fire powers (low = fixed power, high = adaptive) | energy | B,E |
| 213 | `opponent_preferred_distance` | Rolling mean of `distance` over last 50 scans | px | A,E |
| 214 | `opponent_distance_std` | Std deviation of `distance` over last 50 scans (low = distance keeper, high = erratic) | px | A,E |
| 215 | `opponent_movement_bias` | Rolling mean of `opponent_lateral_direction` over last 50 scans (0 = no bias, ±1 = strong CW/CCW bias) | [−1,1] | A |
| 216 | `opponent_ram_tendency` | Fraction of last 50 ticks where `opponent_advancing_velocity > 4` | [0,1] | A,D |
| 217 | `opponent_approach_speed_avg` | Rolling mean of `opponent_advancing_velocity` over last 30 scans | px/tick | A,D |
| 218 | `opponent_is_chaser` | `opponent_preferred_distance < 200 && opponent_advancing_velocity > 2` sustained for ≥ 10 ticks | bool | A,D |
| 219 | `opponent_shot_on_fire_correlation` | Fraction of detected opponent fires that occurred within 3 ticks of an opponent velocity = 0 (stop-and-shoot detection) | [0,1] | B |
| 220 | `opponent_gun_heat_wave_count` | Count of ticks where opponent COULD fire (inferred gun heat ≤ 0) in last 50 ticks | int | B |
| 221 | `opponent_fire_on_cooldown_ratio` | Opponent fires / `opponent_gun_heat_wave_count` — how often they fire when they CAN | [0,1] | B,E |
| 222 | `opponent_wall_hugging_fraction` | Fraction of last 100 ticks where `opponent_dist_to_wall_min < 50` | [0,1] | A |
| 223 | `opponent_corner_camping_fraction` | Fraction of last 100 ticks where `opponent_corner_proximity < 80` | [0,1] | A |

### 5.12 Multi-Tick Delta Features

Discrete deltas for key state variables at lag-1 and lag-2 (one and two ticks ago). NOT rolling windows — these are raw differences the ML model can use for acceleration, jerk, and short-horizon trend detection.

| # | Feature Name | Formula | Unit | Domains |
|---|---|---|---|---|
| 224 | `our_x_delta_1` | `our_x(t) − our_x(t−1)` | px | D |
| 225 | `our_y_delta_1` | `our_y(t) − our_y(t−1)` | px | D |
| 226 | `our_heading_delta_1` | `normalRelativeAngle(our_heading(t) − our_heading(t−1))` | rad | D |
| 227 | `our_velocity_delta_1` | `our_velocity(t) − our_velocity(t−1)` | px/tick | D |
| 228 | `our_energy_delta_1` | `our_energy(t) − our_energy(t−1)` | energy | D |
| 229 | `opponent_x_delta_1` | `opponent_x(t) − opponent_x(t−1)` (requires consecutive scans) | px | A |
| 230 | `opponent_y_delta_1` | `opponent_y(t) − opponent_y(t−1)` | px | A |
| 231 | `opponent_heading_delta_1` | `normalRelativeAngle(opponent_heading(t) − opponent_heading(t−1))` — alias for #35 single-tick | rad | A |
| 232 | `opponent_velocity_delta_1` | `opponent_velocity(t) − opponent_velocity(t−1)` — alias for #36 single-tick | px/tick | A |
| 233 | `opponent_energy_delta_1` | `opponent_energy(t) − opponent_energy(t−1)` — alias for #47 single-tick | energy | B |
| 234 | `distance_delta_1` | `distance(t) − distance(t−1)` — alias for #121 single-tick | px | A,D |
| 235 | `our_x_delta_2` | `our_x(t−1) − our_x(t−2)` (second lag) | px | D |
| 236 | `our_y_delta_2` | `our_y(t−1) − our_y(t−2)` | px | D |
| 237 | `our_heading_delta_2` | `normalRelativeAngle(our_heading(t−1) − our_heading(t−2))` | rad | D |
| 238 | `our_velocity_delta_2` | `our_velocity(t−1) − our_velocity(t−2)` | px/tick | D |
| 239 | `opponent_x_delta_2` | `opponent_x(t−1) − opponent_x(t−2)` | px | A |
| 240 | `opponent_y_delta_2` | `opponent_y(t−1) − opponent_y(t−2)` | px | A |
| 241 | `opponent_heading_delta_2` | `normalRelativeAngle(opponent_heading(t−1) − opponent_heading(t−2))` | rad | A |
| 242 | `opponent_velocity_delta_2` | `opponent_velocity(t−1) − opponent_velocity(t−2)` | px/tick | A |

**Note on lag-2 features:** These let the model compute second-order derivatives (jerk, angular acceleration rate) internally. When scans are non-consecutive, delta features are divided by the actual tick gap or set to `NaN`.

### 5.13 Trigonometric Feature Pairs

ML models handle circular (angular) values better when decomposed into sin/cos pairs. These features duplicate key angles as explicit sin/cos components.

| # | Feature Name | Formula | Unit | Domains |
|---|---|---|---|---|
| 243 | `sin_our_heading` | `sin(our_heading)` | [−1,1] | C,D |
| 244 | `cos_our_heading` | `cos(our_heading)` | [−1,1] | C,D |
| 245 | `sin_our_gun_heading` | `sin(our_gun_heading)` (from `getGunHeadingRadians()`) | [−1,1] | C |
| 246 | `cos_our_gun_heading` | `cos(our_gun_heading)` | [−1,1] | C |
| 247 | `sin_bearing_to_opponent` | `sin(bearing_to_opponent_abs)` | [−1,1] | A,C |
| 248 | `cos_bearing_to_opponent` | `cos(bearing_to_opponent_abs)` | [−1,1] | A,C |
| 249 | `sin_relative_heading` | `sin(relative_heading)` | [−1,1] | A,C |
| 250 | `cos_relative_heading` | `cos(relative_heading)` | [−1,1] | A,C |
| 251 | `sin_opponent_heading` | `sin(opponent_heading)` | [−1,1] | A |
| 252 | `cos_opponent_heading` | `cos(opponent_heading)` | [−1,1] | A |
| 253 | `sin_bearing_from_opponent` | `sin(bearing_from_opponent_abs)` | [−1,1] | A,D |
| 254 | `cos_bearing_from_opponent` | `cos(bearing_from_opponent_abs)` | [−1,1] | A,D |
| 255 | `sin_linear_target_offset` | `sin(linear_target_offset)` | [−1,1] | C |
| 256 | `cos_linear_target_offset` | `cos(linear_target_offset)` | [−1,1] | C |
| 257 | `sin_circular_target_offset` | `sin(circular_target_offset)` | [−1,1] | C |
| 258 | `cos_circular_target_offset` | `cos(circular_target_offset)` | [−1,1] | C |

### 5.14 Bullet Shadow & Shielding Features

Features that encode bullet shadow and bullet shielding geometry. Bullet shadows represent GF ranges on enemy waves that are guaranteed safe because our own bullet intersects that region.

| # | Feature Name | Formula | Unit | Domains |
|---|---|---|---|---|
| 259 | `our_bullet_in_flight` | Whether we have a bullet currently in flight (not yet hit/missed) | bool | C,D |
| 260 | `our_bullet_flight_ticks` | Ticks our most recent bullet has been in flight | ticks | C |
| 261 | `our_bullet_current_distance` | `our_bullet_speed × our_bullet_flight_ticks` (distance our bullet has traveled) | px | C |
| 262 | `shadow_gf_low` | Lower bound of GF range on nearest enemy wave that our bullet shadows (NaN if no shadow) | GF | D |
| 263 | `shadow_gf_high` | Upper bound of GF range shadowed | GF | D |
| 264 | `shadow_gf_width` | `shadow_gf_high − shadow_gf_low` (width of safe region from our bullet) | GF | D |
| 265 | `shadow_covers_current_gf` | Whether our current GF on the enemy wave falls within the shadow | bool | D |
| 266 | `bullet_shield_feasible` | Whether we can fire a 0.1-power bullet that would intercept the nearest enemy bullet (heading/distance geometry check) | bool | D |
| 267 | `bullet_shield_intercept_ticks` | Estimated ticks until our shielding bullet would intercept the enemy bullet (NaN if not feasible) | ticks | D |

### 5.15 Segmentation-Class Features

Features that competitive bots use as segmentation dimensions for VCS/GF targeting. These are explicitly designed to partition the situation space into bins. Many overlap with earlier features but are discretized or binned appropriately.

| # | Feature Name | Formula | Unit | Domains |
|---|---|---|---|---|
| 268 | `distance_bin` | Discretized distance: 0=close (<150), 1=medium (150–400), 2=far (>400) | {0,1,2} | A,C |
| 269 | `lateral_velocity_bin` | Discretized `abs(opponent_lateral_velocity)`: 0=stopped (<0.5), 1=slow (0.5–4), 2=fast (>4) | {0,1,2} | A,C |
| 270 | `advancing_velocity_bin` | Discretized `opponent_advancing_velocity`: 0=retreating (<−2), 1=neutral (−2 to +2), 2=approaching (>+2) | {0,1,2} | A,C |
| 271 | `acceleration_bin` | Discretized: 0=decelerating, 1=constant, 2=accelerating (from #163) | {0,1,2} | A |
| 272 | `wall_proximity_bin` | Discretized `opponent_dist_to_wall_min`: 0=touching (<40), 1=near (40–120), 2=open (>120) | {0,1,2} | A,C |
| 273 | `bullet_flight_time_bin` | Discretized `distance / our_bullet_speed`: 0=short (<10), 1=medium (10–25), 2=long (>25) | {0,1,2} | C |
| 274 | `move_time_bin` | Discretized `opponent_time_since_last_zero_velocity`: 0=just stopped (0–5), 1=recent (6–20), 2=running (>20) | {0,1,2} | A |
| 275 | `wall_space_forward` | Fraction of theoretical MEA reachable in opponent's forward-lateral direction considering walls: `min(1, perpendicular_wall_dist / (mea × distance))` | [0,1] | A,C |
| 276 | `wall_space_reverse` | Same for opponent's reverse-lateral direction | [0,1] | A,C |

### 5.16 Flattener & Movement Profile Features

Features that a wave-surfing movement would use to track its own GF profile and attempt to flatten it. These represent self-awareness of our own movement's statistical fingerprint.

| # | Feature Name | Formula | Unit | Domains |
|---|---|---|---|---|
| 277 | `our_gf_visit_entropy` | Shannon entropy of our own GF visit distribution (higher = flatter = harder to target) | bits | D |
| 278 | `our_gf_peak_visit_ratio` | `max(our_gf_bins) / sum(our_gf_bins)` — how spiked our movement profile is | [0,1] | D |
| 279 | `our_gf_peak_bin` | The GF bin index with the highest visit count (indicates our most predictable angle) | int | D |
| 280 | `our_gf_visited_count` | Total visit count across all GF bins this round (= number of waves that have passed us) | int | D |
| 281 | `our_lateral_direction_change_rate` | Our own lateral direction changes / ticks_in_round | changes/tick | D |
| 282 | `our_time_since_direction_change` | Ticks since we last changed lateral direction | ticks | D |
| 283 | `our_avg_lateral_velocity_10` | Rolling mean of our own `lateral_velocity` over last 10 ticks | px/tick | D |

### 5.17 Gun Alignment Features

Features expressing the current state of our gun alignment relative to various targeting solutions. Critical for fire timing decisions.

| # | Feature Name | Formula | Unit | Domains |
|---|---|---|---|---|
| 284 | `gun_turn_remaining` | `getGunTurnRemainingRadians()` — how much our gun still needs to turn to reach its target | rad | C |
| 285 | `gun_turn_remaining_abs` | `abs(gun_turn_remaining)` | rad | C |
| 286 | `gun_aligned` | `gun_turn_remaining_abs < 0.01` (close enough to fire accurately) | bool | C |
| 287 | `gun_offset_from_head_on` | `normalRelativeAngle(our_gun_heading − bearing_to_opponent_abs)` | rad | C |
| 288 | `gun_offset_from_linear` | `normalRelativeAngle(our_gun_heading − linear_target_angle_exact)` | rad | C |
| 289 | `gun_offset_from_circular` | `normalRelativeAngle(our_gun_heading − circular_target_angle)` | rad | C |
| 290 | `ticks_to_gun_alignment` | `ceil(gun_turn_remaining_abs / GUN_TURN_RATE_RADIANS)` | ticks | C |

### 5.18 Virtual Gun Performance Features

Features tracking the historical performance of different targeting strategies, enabling the model to learn which gun performs best against the current opponent.

| # | Feature Name | Formula | Unit | Domains |
|---|---|---|---|---|
| 291 | `vgun_head_on_hit_rate` | Fraction of head-on virtual waves that would have hit | [0,1] | C |
| 292 | `vgun_linear_hit_rate` | Fraction of linear-targeting virtual waves that would have hit | [0,1] | C |
| 293 | `vgun_circular_hit_rate` | Fraction of circular-targeting virtual waves that would have hit | [0,1] | C |
| 294 | `vgun_best_gun_id` | ID of best-performing virtual gun: 0=head-on, 1=linear, 2=circular | {0,1,2} | C |
| 295 | `vgun_best_hit_rate` | Hit rate of the best virtual gun | [0,1] | C |
| 296 | `vgun_waves_evaluated` | Total number of virtual gun waves that have reached the opponent (sample size indicator) | int | C |

### 5.19 Advanced Spatial Relationship Features

Additional spatial features derived from competitive RoboWiki concepts: center control, angular velocity, displacement vectors.

| # | Feature Name | Formula | Unit | Domains |
|---|---|---|---|---|
| 297 | `opponent_angular_velocity` | `opponent_lateral_velocity / distance` — angular rate opponent sweeps across our field of view | rad/tick | A,C |
| 298 | `our_angular_velocity` | `our_lateral_velocity / distance` — angular rate we sweep across opponent's field of view | rad/tick | D |
| 299 | `center_distance_advantage` | `opponent_center_distance − our_center_distance` — positive = we're closer to center | px | E |
| 300 | `angle_to_center_from_us` | `atan2(bfWidth/2 − our_x, bfHeight/2 − our_y)` | rad | D |
| 301 | `angle_to_center_from_opponent` | `atan2(bfWidth/2 − opponent_x, bfHeight/2 − opponent_y)` | rad | A |
| 302 | `displacement_vector_x_10` | `opponent_x(t) − opponent_x(t−10)` (displacement over 10 ticks) | px | A |
| 303 | `displacement_vector_y_10` | `opponent_y(t) − opponent_y(t−10)` | px | A |
| 304 | `displacement_magnitude_10` | `hypot(displacement_vector_x_10, displacement_vector_y_10)` | px | A |
| 305 | `displacement_vector_x_30` | `opponent_x(t) − opponent_x(t−30)` | px | A |
| 306 | `displacement_vector_y_30` | `opponent_y(t) − opponent_y(t−30)` | px | A |
| 307 | `displacement_magnitude_30` | `hypot(displacement_vector_x_30, displacement_vector_y_30)` | px | A |
| 308 | `opponent_confinement_ratio` | `displacement_magnitude_30 / (opponent_avg_speed_30 × 30)` — ratio of net displacement to total distance traveled; 1 = straight line, 0 = stationary/orbiting | [0,1] | A |

---

## 6. Energy Drop Detection

This is the one "heuristic" allowed. It is universally used in competitive Robocode.

### 6.1 Detection Logic

```java
double energyDrop = prevOpponentEnergy - currentOpponentEnergy;

boolean opponentFired = false;
double inferredPower = 0;

if (energyDrop >= Rules.MIN_BULLET_POWER      // >= 0.1
    && energyDrop <= Rules.MAX_BULLET_POWER    // <= 3.0
    && ticks_since_scan == 1                   // consecutive scans (no data gap)
    && !we_hit_opponent_this_tick              // our bullet didn't cause the drop
    && !opponent_hit_wall_this_tick            // wall hit didn't cause the drop (can't always know)
    ) {
    opponentFired = true;
    inferredPower = energyDrop;
}
```

### 6.2 False Positive Sources

| Source | Energy change | Distinguishable? |
|---|---|---|
| Opponent hit wall | `max(|v|/2 − 1, 0)` i.e. 0 to 3.0 | Overlaps bullet power range. Partially: if drop is 0 (low-speed wall hit), it's not a fire. For high speeds, ambiguous. |
| Robot-robot collision | 0.6 each | Yes: we get `HitRobotEvent` |
| Our bullet hit opponent | `4p + 2(p−1)` for p>1 | Yes: we get `BulletHitEvent` |
| Opponent bullet hit our bullet | 0 (no energy change to opponent) | N/A |
| Inactivity zap | Variable | Rare; ignorable in practice |

### 6.3 Confidence Levels

- **High confidence**: `energyDrop ∈ [0.1, 3.0]`, consecutive scans, no collision event → almost certainly a fire
- **Medium confidence**: Scan gap > 1 tick → could be fire(s) but timing uncertain
- **Low confidence**: Energy drop during wall-hit-speed ranges or collision ranges

### 6.4 What to Record When Fire Detected

| Field | Value |
|---|---|
| `opponent_fire_tick` | Current tick |
| `opponent_fire_power` | `energyDrop` |
| `opponent_fire_bearing` | `bearing_from_opponent_abs` at fire time |
| `opponent_bullet_speed` | `20 − 3 × inferredPower` |
| `opponent_fire_distance` | Distance at fire time |
| `opponent_wave_origin_x` | Opponent position at fire time |
| `opponent_wave_origin_y` | Opponent position at fire time |

---

## 7. Notes on Precision

### 7.1 Angle Conventions

- All angles in **radians** in internal computation
- Robocode uses **0 = North, clockwise positive** (NOT standard math convention)
- `atan2` in Java uses standard convention → Robocode uses `atan2(dx, dy)` instead of the standard `atan2(dy, dx)` to get Robocode-convention angles
- Always normalize angles:
  - Absolute angles to `[0, 2π)` via `normalAbsoluteAngle()`
  - Relative angles to `(−π, π]` via `normalRelativeAngle()`

### 7.2 Position Precision

- All positions are `double` precision
- Robot center is constrained to `[18, battlefieldWidth − 18] × [18, battlefieldHeight − 18]`
- Bullet positions are `double` precision, unconstrained until wall collision at boundaries

### 7.3 Velocity Sign Convention

- Positive velocity = moving **forward** (in the direction of heading)
- Negative velocity = moving **backward** (opposite to heading direction)
- The `ScannedRobotEvent.getVelocity()` returns a signed value reflecting this

### 7.4 Scan Data Staleness

Since `ScannedRobotEvent` data is only updated when the radar sweeps over the opponent, features that depend on opponent state may be stale. The `ticks_since_scan` feature tracks this. Delta features (velocity_delta, heading_delta) must divide by `delta_ticks`, not assume 1.

### 7.5 Gun Heat Tracking

- Initial gun heat at round start: 3.0 (from the code: `gunHeat = 3`)
- Decreases by `gunCoolingRate` per tick (default 0.1)
- Increases by `1 + power/5` on fire
- For opponent gun heat estimation: start tracking from detected fires and energy-drop inferences. Cannot observe directly.

### 7.6 Floating Point Considerations

The `isNear()` utility in Robocode uses `Math.abs(a - b) < .00001` for near-zero checks. Feature engineering should propagate this tolerance where applicable, especially for velocity == 0 checks.

---

## 8. Feature Count Summary

| Category | Feature Range | Count |
|---|---|---|
| Spatial | #1–#25 | 25 |
| Movement | #26–#42 | 17 |
| Energy | #43–#52 | 10 |
| Bullet & Wave | #53–#62 | 10 |
| Targeting Geometry | #63–#71 | 9 |
| Timing | #72–#82 | 11 |
| Battlefield Geometry | #83–#94 | 12 |
| Movement Patterns (derived) | #95–#104 | 10 |
| Firing Strategy (derived) | #105–#114 | 10 |
| Danger Assessment (derived) | #115–#120 | 6 |
| Historical/Smoothed (derived) | #121–#125 | 5 |
| Scan-Based | #126–#133 | 8 |
| GuessFactor | #134–#145 | 12 |
| Wave Surfing Geometry | #146–#157 | 12 |
| Pattern & Movement Rhythm | #158–#175 | 18 |
| Combat State | #176–#193 | 18 |
| Wall Interaction | #194–#208 | 15 |
| Opponent Modeling | #209–#223 | 15 |
| Multi-Tick Deltas | #224–#242 | 19 |
| Trigonometric Pairs | #243–#258 | 16 |
| Bullet Shadow & Shielding | #259–#267 | 9 |
| Segmentation-Class | #268–#276 | 9 |
| Flattener & Movement Profile | #277–#283 | 7 |
| Gun Alignment | #284–#290 | 7 |
| Virtual Gun Performance | #291–#296 | 6 |
| Advanced Spatial Relationships | #297–#308 | 12 |
| **Total (Base)** | | **308** |

*See §12 for the updated total including Stage 2 extensions (443 features).*

---

## 9. CSV Output Format

Each row in the output CSV corresponds to one tick where a scan occurred (or interpolated between scans). Columns are ordered by feature number. Additional metadata columns:

| Column | Description |
|---|---|
| `tick` | Game tick number |
| `round` | Round number |
| `scan_available` | Whether fresh scan data was available this tick |
| `our_name` | Our robot name |
| `opponent_name` | Opponent robot name |

The 308 feature columns follow, using the `snake_case` names from the catalog above.

---

## 10. Implementation Notes

### 10.1 State Management

The feature engineering program must maintain per-tick state across scans:
- Previous opponent position, heading, velocity, energy (for deltas)
- Rolling windows for averaged/smoothed features (10- and 30-scan buffers)
- Fire detection state (last opponent fire tick, cumulative fire count)
- Wave tracking (active waves, their origins and speeds)

### 10.2 Interpolation Between Scans

When scans are not consecutive (radar missed a tick), the program should:
- Carry forward previous features unchanged
- Set `ticks_since_scan` to the actual gap
- NOT interpolate opponent position (unreliable)
- Set delta features to `NaN` or compute over the actual gap

### 10.3 Feature Pipeline Order

1. Parse raw CSV row (tick, positions, headings, velocities, energies)
2. Compute spatial features (#1–#25)
3. Compute movement features (#26–#42) using current + previous state
4. Compute energy features (#43–#52) including energy drop detection
5. Compute bullet/wave features (#53–#62)
6. Compute targeting geometry (#63–#71)
7. Compute timing features (#72–#82)
8. Compute battlefield geometry (#83–#94)
9. Compute derived features (#95–#308)
10. Compute advanced features (#309–#443)
11. Emit CSV row
12. Update state for next tick

---

## 11. Advanced Feature Catalog (Stage 2 Extensions)

Features derived from competitive strategy analysis. Each feature is marked **[RT]** (real-time computable from scan data during a battle) or **[OFF]** (offline-only, requires post-processing or data not available during live play).

### 11.1 Category: Wave Surfing Defense — Per-Wave Danger (Domain D)

Features a wave-surfing robot needs to compute on every detected enemy wave. These encode the danger landscape of incoming waves and the quality of our escape options.

| # | Feature Name | Formula | Unit | Domains | RT/OFF |
|---|---|---|---|---|---|
| 309 | `wave_danger_at_current_gf` | Danger value (from VCS/KDE lookup) at our current GF on nearest enemy wave | unitless | D | [RT] |
| 310 | `wave_danger_at_gf_zero` | Danger at GF=0 (head-on) on nearest enemy wave | unitless | D | [RT] |
| 311 | `wave_danger_peak_gf` | The GF with maximum danger on nearest enemy wave | GF | D | [RT] |
| 312 | `wave_danger_peak_value` | The danger value at the peak GF | unitless | D | [RT] |
| 313 | `wave_danger_min_reachable` | Minimum danger among all GFs reachable from current position (via precise prediction) | unitless | D | [RT] |
| 314 | `wave_danger_ratio` | `wave_danger_at_current_gf / max(0.001, wave_danger_min_reachable)` — how much safer we could be | unitless | D | [RT] |
| 315 | `wave_gf_range_reachable` | `our_gf_reachable_forward − our_gf_reachable_backward` — total reachable GF width | GF | D | [RT] |
| 316 | `wave_gf_danger_integral` | Sum of danger values across all reachable GF bins (total exposure) | unitless | D | [RT] |
| 317 | `wave_safe_gf_count` | Number of reachable GF bins with danger below median danger | int | D | [RT] |
| 318 | `wave_escape_urgency` | `1.0 / max(1, wave_time_to_impact)` — higher when less time to escape | 1/ticks | D | [RT] |
| 319 | `wave_closest_safe_gf_distance` | `abs(our_gf_on_wave − nearest_safe_gf)` — GF distance to nearest safe bin | GF | D | [RT] |
| 320 | `gun_heat_wave_active` | Whether a predictive wave exists (enemy gun heat ≤ 0, no energy drop yet — DrussGT's technique) | bool | D | [RT] |
| 321 | `gun_heat_wave_eta` | Estimated time to impact of the gun-heat-predicted wave (2 ticks before energy drop is visible) | ticks | D | [RT] |

### 11.2 Category: Multi-Wave Interaction (Domain D)

Features encoding the interaction between multiple concurrent enemy waves. Critical for two-wave surfing (Diamond, Dookious).

| # | Feature Name | Formula | Unit | Domains | RT/OFF |
|---|---|---|---|---|---|
| 322 | `wave_1_eta` | ETA of nearest enemy wave | ticks | D | [RT] |
| 323 | `wave_2_eta` | ETA of second-nearest enemy wave (`NaN` if < 2 waves) | ticks | D | [RT] |
| 324 | `wave_eta_gap` | `wave_2_eta − wave_1_eta` — ticks between first and second wave impacts | ticks | D | [RT] |
| 325 | `wave_1_power` | Bullet power of nearest wave | energy | D | [RT] |
| 326 | `wave_2_power` | Bullet power of second wave | energy | D | [RT] |
| 327 | `wave_combined_danger` | `wave_1_danger × wave_1_power² + wave_2_danger × wave_2_power² × decay(wave_eta_gap)` — power-weighted combined danger | unitless | D | [RT] |
| 328 | `wave_1_gf_constrains_wave_2` | Whether dodging wave 1 to best GF limits options on wave 2 (reachable GF range on wave 2 from wave 1's best position is < 0.5) | bool | D | [RT] |
| 329 | `waves_from_same_quadrant` | Whether both waves originate from within 45° of each other (similar angle of attack) | bool | D | [RT] |

### 11.3 Category: GuessFactor Distribution Statistics (Domain C)

Features that summarize the statistical properties of GF targeting data collected from waves. These let the ML model reason about the quality and shape of collected targeting statistics.

| # | Feature Name | Formula | Unit | Domains | RT/OFF |
|---|---|---|---|---|---|
| 330 | `gf_hit_distribution_entropy` | Shannon entropy of our observed GF hit distribution (opponent's actual GF positions when waves break): `−Σ p(i) log₂ p(i)` | bits | C | [RT] |
| 331 | `gf_hit_distribution_peak_gf` | GF bin with most observations (the opponent's most-visited GF) | GF | C | [RT] |
| 332 | `gf_hit_distribution_peak_ratio` | `max_bin_count / total_observations` — how peaked (exploitable) the opponent's movement is | [0,1] | C | [RT] |
| 333 | `gf_hit_distribution_skewness` | Skewness of GF distribution — positive = biased toward positive GF (clockwise), negative = counter-clockwise | unitless | C | [RT] |
| 334 | `gf_hit_distribution_std` | Standard deviation of observed GF hit positions | GF | C | [RT] |
| 335 | `gf_observations_total` | Total number of wave-break observations collected this round | int | C | [RT] |
| 336 | `gf_observations_current_segment` | Observations in the current segmentation bin (distance × lateral_vel × wall) | int | C | [RT] |
| 337 | `gf_recent_hit_gf_1` | GF at which our most recent wave broke (opponent's position on last completed wave) | GF | C | [RT] |
| 338 | `gf_recent_hit_gf_2` | GF of second-most-recent wave break | GF | C | [RT] |
| 339 | `gf_recent_hit_gf_3` | GF of third-most-recent wave break | GF | C | [RT] |
| 340 | `gf_rolling_mean_10` | Mean GF of last 10 wave breaks | GF | C | [RT] |
| 341 | `gf_rolling_std_10` | Std deviation of GF over last 10 wave breaks | GF | C | [RT] |
| 342 | `gf_rolling_mean_vs_all_mean` | `gf_rolling_mean_10 − gf_mean_all` — drift between recent and overall GF tendency | GF | C | [RT] |

### 11.4 Category: Pattern Matching Support (Domain A, C)

Features designed to support pattern-matching targeting. These encode movement sequences in forms suitable for symbolic matching and autocorrelation analysis.

| # | Feature Name | Formula | Unit | Domains | RT/OFF |
|---|---|---|---|---|---|
| 343 | `opponent_movement_symbol` | Discretized per-tick movement state: encode `(velocity_bin, heading_delta_bin)` as integer symbol. Velocity bins: {−8..−5, −4..−1, 0, 1..4, 5..8} = 5 bins. Heading delta bins: {strong_left, slight_left, straight, slight_right, strong_right} = 5 bins. Total: 25 symbols. | int [0,24] | A,C | [RT] |
| 344 | `opponent_movement_hash_8` | Hash of last 8 movement symbols — compact encoding of recent movement pattern | int | A,C | [RT] |
| 345 | `opponent_movement_hash_16` | Hash of last 16 movement symbols | int | A,C | [RT] |
| 346 | `opponent_movement_hash_32` | Hash of last 32 movement symbols | int | A,C | [OFF] |
| 347 | `pattern_match_length_best` | Length of longest matching subsequence found in movement history (0 if no meaningful match) | int | A,C | [OFF] |
| 348 | `pattern_match_confidence` | `pattern_match_length_best / max(8, ticks_in_round / 10)` — longer matches relative to history = more confident | [0,1] | A,C | [OFF] |
| 349 | `pattern_target_gf` | GF predicted by replaying the movement sequence that follows the best pattern match | GF | C | [OFF] |
| 350 | `opponent_lateral_vel_autocorr_lag_10` | Autocorrelation of `opponent_lateral_velocity` at lag 10: `corr(lat_vel[t], lat_vel[t−10])` over last 100 ticks | [−1,1] | A | [RT] |
| 351 | `opponent_lateral_vel_autocorr_lag_20` | Autocorrelation at lag 20 | [−1,1] | A | [RT] |
| 352 | `opponent_lateral_vel_autocorr_lag_30` | Autocorrelation at lag 30 | [−1,1] | A | [RT] |
| 353 | `opponent_lateral_vel_autocorr_peak_lag` | Lag with highest autocorrelation (estimated dominant period of movement oscillation) | ticks | A | [RT] |
| 354 | `opponent_heading_delta_autocorr_lag_10` | Autocorrelation of `opponent_heading_delta` at lag 10 | [−1,1] | A | [RT] |

### 11.5 Category: Opponent Profiling & Fingerprinting (Domain A, B, E)

Features characterizing the opponent's strategic "personality" — movement style, targeting tendencies, and adaptation behavior. These are the dimensions an archetype classifier would use.

| # | Feature Name | Formula | Unit | Domains | RT/OFF |
|---|---|---|---|---|---|
| 355 | `opponent_movement_type_indicator` | Composite: 0=stationary, 1=linear, 2=circular, 3=oscillator, 4=random, 5=surfing (heuristic classification from movement statistics) | {0..5} | A | [RT] |
| 356 | `opponent_gf_distribution_flatness` | `1 − gf_hit_distribution_peak_ratio` — how flat the opponent's GF profile is (higher = likely surfer or random mover) | [0,1] | A,C | [RT] |
| 357 | `opponent_dodge_correlation` | Correlation between our fire detection ticks and opponent direction changes within ±3 ticks — high correlation = likely wave surfer | [−1,1] | A,B | [RT] |
| 358 | `opponent_fire_after_dodge_rate` | Fraction of opponent fires that occur within 5 ticks of an opponent direction change (stop-and-shoot pattern) | [0,1] | B | [RT] |
| 359 | `opponent_distance_keeping_score` | Standard deviation of `distance` / mean of `distance` over last 100 ticks — low = strong distance keeper, high = erratic | [0,∞) | A | [RT] |
| 360 | `opponent_targeting_style_gf_mean` | Mean GF of bullets that hit us (from `HitByBulletEvent` bearings matched to waves) — reveals their aiming tendency | GF | B | [RT] |
| 361 | `opponent_targeting_style_gf_std` | Std deviation of GFs of bullets that hit us | GF | B | [RT] |
| 362 | `opponent_uses_head_on` | `abs(opponent_targeting_style_gf_mean) < 0.15 && opponent_targeting_style_gf_std < 0.2` sustained for ≥ 5 hits | bool | B | [RT] |
| 363 | `opponent_uses_linear` | Whether `gf_linear_prediction` matches the mean of opponent hit GFs within ±0.15 | bool | B | [RT] |
| 364 | `opponent_adaptation_rate` | Rate of change of opponent's GF hit distribution over rolling windows: KL-divergence between first-half and second-half of observations | unitless | B,E | [RT] |
| 365 | `opponent_energy_aggression` | `opponent_fire_power_avg / max(0.1, 3.0 × opponent_energy / 100)` — fire power relative to their energy (>1 = aggressive, <1 = conservative) | unitless | B,E | [RT] |
| 366 | `opponent_is_adaptive` | `opponent_adaptation_rate > threshold` — whether the opponent's movement changes significantly across the round | bool | A,E | [RT] |
| 367 | `opponent_mirror_movement_score` | Correlation between opponent's lateral velocity and our lateral velocity — high correlation = mirroring movement | [−1,1] | A | [RT] |
| 368 | `opponent_fires_on_scan` | Fraction of opponent fires within 2 ticks of a scan event (suggests tight radar lock + immediate fire pattern) | [0,1] | B | [RT] |

### 11.6 Category: Energy Management & Efficiency (Domain E, C)

Features that inform optimal fire power selection, energy budgeting, and damage efficiency tracking.

| # | Feature Name | Formula | Unit | Domains | RT/OFF |
|---|---|---|---|---|---|
| 369 | `net_energy_per_shot` | `(3 × avg_fire_power × our_hit_rate) − avg_fire_power` — expected energy gain/loss per shot | energy | C,E | [RT] |
| 370 | `energy_roi` | `damage_dealt_this_round / max(1, energy_spent_on_bullets)` where `energy_spent = sum of all fire powers this round` | unitless | C,E | [RT] |
| 371 | `energy_spent_this_round` | Cumulative sum of fire powers used this round | energy | C,E | [RT] |
| 372 | `opponent_energy_spent_est` | Cumulative sum of detected opponent fire powers this round | energy | B,E | [RT] |
| 373 | `kill_shot_power` | `min(3.0, opponent_energy / 4)` — minimum power needed so damage ≥ opponent remaining energy (for power ≤ 1: damage=4p, so p=energy/4; for p>1: damage=6p−2, so p=(energy+2)/6) | energy | C,E | [RT] |
| 374 | `can_kill_with_min_power` | `opponent_energy ≤ 0.4` (4 × 0.1 = 0.4 damage from minimum bullet) | bool | C,E | [RT] |
| 375 | `energy_burndown_rate_us` | Linear regression slope of `our_energy` over last 50 ticks | energy/tick | E | [RT] |
| 376 | `energy_burndown_rate_opponent` | Linear regression slope of `opponent_energy` over last 50 ticks | energy/tick | E | [RT] |
| 377 | `ticks_until_our_energy_zero` | `our_energy / max(0.001, −energy_burndown_rate_us)` if rate is negative, else `∞` | ticks | E | [RT] |
| 378 | `ticks_until_opponent_energy_zero` | `opponent_energy / max(0.001, −energy_burndown_rate_opponent)` if rate is negative, else `∞` | ticks | E | [RT] |
| 379 | `damage_efficiency` | `damage_dealt_this_round / max(1, damage_dealt_this_round + damage_received_this_round)` — fraction of total damage that we dealt | [0,1] | E | [RT] |
| 380 | `fire_power_distance_ratio` | `our_last_fire_power × distance` — higher = more energy risked at range (usually bad) | energy×px | C,E | [RT] |
| 381 | `optimal_power_for_hit_rate` | Power maximizing expected net energy: `argmax_p (hit_rate(p) × 2p − (1−hit_rate(p)) × p)` approximated from rolling hit rates at different powers | energy | C,E | [OFF] |
| 382 | `our_hit_rate_at_power_low` | Hit rate for bullets fired with power ≤ 1.0 | [0,1] | C | [RT] |
| 383 | `our_hit_rate_at_power_mid` | Hit rate for bullets fired with power 1.0–2.0 | [0,1] | C | [RT] |
| 384 | `our_hit_rate_at_power_high` | Hit rate for bullets fired with power > 2.0 | [0,1] | C | [RT] |

### 11.7 Category: Anti-Surfer Targeting (Domain C)

Features for targeting wave-surfing opponents. These help detect surfing behavior and predict where a surfer will dodge to.

| # | Feature Name | Formula | Unit | Domains | RT/OFF |
|---|---|---|---|---|---|
| 385 | `opponent_is_likely_surfer` | Composite: `opponent_dodge_correlation > 0.3 && opponent_gf_distribution_flatness > 0.7` | bool | A,C | [RT] |
| 386 | `opponent_surf_direction_bias` | Mean direction of opponent's post-fire-detection movement: +1 = predominantly forward dodge, −1 = predominantly backward dodge | [−1,1] | A,C | [RT] |
| 387 | `opponent_predicted_surf_gf` | GF the opponent is most likely surfing toward (the GF with minimum danger in their likely danger model — estimated from their observed evasion pattern) | GF | C | [RT] |
| 388 | `anti_surfer_offset` | `opponent_predicted_surf_gf − gf_hit_distribution_peak_gf` — the difference between where a surfer will go and where a non-surfer would be | GF | C | [RT] |
| 389 | `opponent_dodge_distance` | Mean absolute GF displacement between opponent position at wave-fire-time and at wave-break-time | GF | A,C | [RT] |
| 390 | `opponent_dodge_consistency` | Std deviation of dodge distances — low = consistent dodger (surfer), high = erratic | GF | A,C | [RT] |
| 391 | `opponent_reverse_on_fire_rate` | Fraction of our fire events followed by an opponent lateral direction reversal within 5 ticks | [0,1] | A,C | [RT] |
| 392 | `flattener_detection_score` | Entropy of opponent's GF visit distribution relative to log₂(bins) — closer to 1.0 = actively flattening | [0,1] | A,C | [RT] |
| 393 | `opponent_gf_bin_min_visits` | Minimum visit count across all GF bins (high minimum = flat distribution = likely flattener) | int | A,C | [RT] |
| 394 | `opponent_best_gf_changing` | Whether `gf_hit_distribution_peak_gf` has changed by > 0.3 in the last 20 wave observations (indicates adaptive movement) | bool | A,C | [RT] |

### 11.8 Category: Displacement Vector Targeting (Domain A, C)

Features based on the Displacement Vector technique (Diamond, Shadow). Displacement vectors record where the opponent ends up relative to where they were when we fired, expressed relative to initial heading. These are key for predictive targeting and melee.

| # | Feature Name | Formula | Unit | Domains | RT/OFF |
|---|---|---|---|---|---|
| 395 | `displacement_heading_relative_x_10` | Displacement vector over 10 ticks rotated into opponent's heading frame: `dx × cos(h₀) + dy × sin(h₀)` where h₀ = opponent heading at t−10 | px | A | [RT] |
| 396 | `displacement_heading_relative_y_10` | `−dx × sin(h₀) + dy × cos(h₀)` | px | A | [RT] |
| 397 | `displacement_heading_relative_x_30` | Same for 30-tick displacement | px | A | [RT] |
| 398 | `displacement_heading_relative_y_30` | Same for 30-tick displacement | px | A | [RT] |
| 399 | `displacement_angle_10` | `atan2(displacement_heading_relative_x_10, displacement_heading_relative_y_10)` — angle of displacement relative to initial heading | rad | A | [RT] |
| 400 | `displacement_angle_30` | Same for 30-tick displacement | rad | A | [RT] |
| 401 | `displacement_scaled_by_bft_x` | `displacement_heading_relative_x_N / bullet_flight_time` — displacement scaled by bullet travel time (normalizes for distance/power) | px/tick | A,C | [RT] |
| 402 | `displacement_scaled_by_bft_y` | Same for y component | px/tick | A,C | [RT] |
| 403 | `displacement_gf_equivalent` | Displacement angle converted to GF: `displacement_angle / mea` clamped to [−1,1] — allows comparison with GF-based targeting | GF | A,C | [RT] |
| 404 | `displacement_recent_mean_angle` | Mean displacement angle over last 10 completed waves | rad | A,C | [RT] |
| 405 | `displacement_recent_std_angle` | Std deviation of displacement angles over last 10 waves | rad | A,C | [RT] |

### 11.9 Category: DrussGT-Inspired Advanced Dimensions (Domain A, C, D)

Features modeled directly on the segmentation dimensions used by the #1 RoboRumble bot. These are the dimensions that genetic algorithm optimization identified as highest-value.

| # | Feature Name | Formula | Unit | Domains | RT/OFF |
|---|---|---|---|---|---|
| 406 | `opponent_lateral_distance_10` | `abs(Σ opponent_lateral_velocity[t−9..t])` — cumulative absolute lateral displacement over last 10 ticks (DrussGT attribute #7) | px | A,C | [RT] |
| 407 | `opponent_lateral_distance_20` | Same over last 20 ticks | px | A,C | [RT] |
| 408 | `opponent_time_since_deceleration` | Ticks since `abs(opponent_velocity)` decreased (DrussGT attribute #5) | ticks | A,C | [RT] |
| 409 | `our_time_since_deceleration` | Ticks since `abs(our_velocity)` decreased (mirror for surfing) | ticks | D | [RT] |
| 410 | `opponent_current_gf_on_our_wave` | GF position opponent currently occupies on our nearest active wave (DrussGT attribute #10 — meta-feature for anti-surfer) | GF | C | [RT] |
| 411 | `expected_orbit_angle_change` | Predicted change in opponent's orbital angle around us by bullet arrival time: `opponent_angular_velocity × bullet_travel_time` (DrussGT attribute #11) | rad | C | [RT] |
| 412 | `our_bullets_fired_normalized` | `our_shots_fired_this_round / (1 + our_shots_fired_this_round)` — fast early growth, slow later (DrussGT attribute #12 normalization) | [0,1] | C,E | [RT] |
| 413 | `opponent_precise_mea_forward` | Precise MEA in opponent's current forward-lateral direction, computed by simulating opponent movement with full physics + wall bounds | rad | A,C | [RT] |
| 414 | `opponent_precise_mea_backward` | Same in opponent's reverse-lateral direction | rad | A,C | [RT] |
| 415 | `precise_gf_forward_wall_limited` | `opponent_precise_mea_forward / theoretical_mea` — 1.0 = full escape, < 1.0 = wall constrains escape | [0,1] | A,C | [RT] |
| 416 | `precise_gf_backward_wall_limited` | Same for backward direction | [0,1] | A,C | [RT] |
| 417 | `opponent_predicted_bullet_power` | Predicted most likely fire power for opponent's next shot (from KDE on {enemy energy, our energy, distance} — DrussGT technique) | energy | B,D | [RT] |

### 11.10 Category: Wave Surfing Self-Model (Domain D)

Extended features for tracking our own movement profile as seen by the enemy. Enables flattening and self-aware movement. Supplements §5.16.

| # | Feature Name | Formula | Unit | Domains | RT/OFF |
|---|---|---|---|---|---|
| 418 | `our_gf_visit_distribution_skewness` | Skewness of our own GF visit profile — reveals directional bias | unitless | D | [RT] |
| 419 | `our_gf_visit_at_current_gf` | Visit count at the GF we are currently occupying on the nearest wave | int | D | [RT] |
| 420 | `our_gf_visit_overexposure` | `our_gf_visit_at_current_gf / max(1, our_gf_visited_count / num_bins)` — ratio of actual visits at this GF to expected uniform visits (>1 = overexposed) | unitless | D | [RT] |
| 421 | `our_surfing_gf_chosen` | The GF we last chose to surf toward (decision output from movement system) | GF | D | [RT] |
| 422 | `our_time_since_orbit_reversal` | Ticks since we last reversed orbit direction (our lateral direction change relative to opponent) | ticks | D | [RT] |
| 423 | `our_orbit_reversal_count_round` | Total orbit direction reversals this round | int | D | [RT] |

### 11.11 Category: Bullet Interaction Geometry (Domain C, D)

Features for bullet-bullet collision detection and bullet shielding optimization.

| # | Feature Name | Formula | Unit | Domains | RT/OFF |
|---|---|---|---|---|---|
| 424 | `bullet_shield_angle` | Required gun angle to intercept nearest enemy bullet (heading toward midpoint intersection) | rad | D | [RT] |
| 425 | `bullet_shield_power_needed` | Minimum power for shield bullet to reach intersection point before enemy bullet passes (speed constraint) | energy | D | [RT] |
| 426 | `bullet_shield_angular_error` | `abs(our_gun_heading − bullet_shield_angle)` — how far our gun is from shield firing position | rad | D | [RT] |
| 427 | `bullets_in_flight_count` | Number of our bullets currently in flight (not yet hit/missed/expired) | int | C,D | [RT] |
| 428 | `shadow_coverage_fraction` | Fraction of nearest enemy wave's reachable GF range that is covered by bullet shadows from our in-flight bullets | [0,1] | D | [RT] |

### 11.12 Category: Cross-Battle Transfer & Opponent Identification (Domain E)

Features supporting cross-battle learning. These features are computable in real-time but are USED for cross-battle model lookup / archetype matching.

| # | Feature Name | Formula | Unit | Domains | RT/OFF |
|---|---|---|---|---|---|
| 429 | `opponent_name_hash` | Hash of `scannedRobot.getName()` — compact opponent identifier for model lookup | int | E | [RT] |
| 430 | `opponent_behavior_embedding_0` | First component of a 4-dimensional opponent behavior embedding computed from: {mean GF, GF std, dodge correlation, fire power avg} | unitless | E | [RT] |
| 431 | `opponent_behavior_embedding_1` | Second component | unitless | E | [RT] |
| 432 | `opponent_behavior_embedding_2` | Third component | unitless | E | [RT] |
| 433 | `opponent_behavior_embedding_3` | Fourth component | unitless | E | [RT] |
| 434 | `rounds_of_data_collected` | Number of rounds of data collected against this opponent (this match) | int | E | [RT] |
| 435 | `data_confidence` | `min(1.0, gf_observations_total / 100)` — how much targeting data we have (1.0 = sufficient) | [0,1] | C,E | [RT] |

### 11.13 Category: Temporal Movement Sequences (Domain A)

Raw temporal features aimed at sequence models (LSTM/Transformer). These provide fixed-lag lookback of key dimensions without averaging, preserving temporal structure.

| # | Feature Name | Formula | Unit | Domains | RT/OFF |
|---|---|---|---|---|---|
| 436 | `opponent_lateral_velocity_lag_3` | `opponent_lateral_velocity` at t−3 | px/tick | A | [RT] |
| 437 | `opponent_lateral_velocity_lag_5` | `opponent_lateral_velocity` at t−5 | px/tick | A | [RT] |
| 438 | `opponent_lateral_velocity_lag_10` | `opponent_lateral_velocity` at t−10 | px/tick | A | [RT] |
| 439 | `opponent_heading_delta_lag_3` | `opponent_heading_delta` at t−3 | rad/tick | A | [RT] |
| 440 | `opponent_heading_delta_lag_5` | `opponent_heading_delta` at t−5 | rad/tick | A | [RT] |
| 441 | `opponent_heading_delta_lag_10` | `opponent_heading_delta` at t−10 | rad/tick | A | [RT] |
| 442 | `distance_lag_5` | `distance` at t−5 | px | A,D | [RT] |
| 443 | `distance_lag_10` | `distance` at t−10 | px | A,D | [RT] |

---

## 12. Feature Count Summary (Updated)

| Category | Feature Range | Count |
|---|---|---|
| Spatial | #1–#25 | 25 |
| Movement | #26–#42 | 17 |
| Energy | #43–#52 | 10 |
| Bullet & Wave | #53–#62 | 10 |
| Targeting Geometry | #63–#71 | 9 |
| Timing | #72–#82 | 11 |
| Battlefield Geometry | #83–#94 | 12 |
| Movement Patterns (derived) | #95–#104 | 10 |
| Firing Strategy (derived) | #105–#114 | 10 |
| Danger Assessment (derived) | #115–#120 | 6 |
| Historical/Smoothed (derived) | #121–#125 | 5 |
| Scan-Based | #126–#133 | 8 |
| GuessFactor | #134–#145 | 12 |
| Wave Surfing Geometry | #146–#157 | 12 |
| Pattern & Movement Rhythm | #158–#175 | 18 |
| Combat State | #176–#193 | 18 |
| Wall Interaction | #194–#208 | 15 |
| Opponent Modeling | #209–#223 | 15 |
| Multi-Tick Deltas | #224–#242 | 19 |
| Trigonometric Pairs | #243–#258 | 16 |
| Bullet Shadow & Shielding | #259–#267 | 9 |
| Segmentation-Class | #268–#276 | 9 |
| Flattener & Movement Profile | #277–#283 | 7 |
| Gun Alignment | #284–#290 | 7 |
| Virtual Gun Performance | #291–#296 | 6 |
| Advanced Spatial Relationships | #297–#308 | 12 |
| **— Stage 2 Extensions —** | | |
| Wave Surfing Defense — Per-Wave Danger | #309–#321 | 13 |
| Multi-Wave Interaction | #322–#329 | 8 |
| GF Distribution Statistics | #330–#342 | 13 |
| Pattern Matching Support | #343–#354 | 12 |
| Opponent Profiling & Fingerprinting | #355–#368 | 14 |
| Energy Management & Efficiency | #369–#384 | 16 |
| Anti-Surfer Targeting | #385–#394 | 10 |
| Displacement Vector Targeting | #395–#405 | 11 |
| DrussGT-Inspired Advanced Dimensions | #406–#417 | 12 |
| Wave Surfing Self-Model | #418–#423 | 6 |
| Bullet Interaction Geometry | #424–#428 | 5 |
| Cross-Battle Transfer & Opponent ID | #429–#435 | 7 |
| Temporal Movement Sequences | #436–#443 | 8 |
| **Total** | | **443** |

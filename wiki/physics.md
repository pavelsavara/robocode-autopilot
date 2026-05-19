# Robocode Game Physics

*Reference: verified against Robocode 1.10.x source code.*

## Coordinate System

- Origin (0, 0) at bottom-left of battlefield
- X increases rightward, Y increases upward
- Heading: 0 = North (up), increases **clockwise** (non-standard!)
- All angles in Robocode API are in degrees; internally we use radians

## Battlefield

- Default 800×600 px. Configurable per battle.
- Robot is a 36×36 px square (half-size = 18 px from center to edge)
- Robots cannot overlap walls: center constrained to [18, width−18] × [18, height−18]

## Turn Execution Order

Each tick processes in this exact order:
1. **Bullets move** (before robots!)
2. Robots move (acceleration, turning, position update)
3. Robot scans execute
4. Bullets are checked for hits
5. Robots are checked for wall/robot collisions
6. Events are delivered (onScannedRobot, onHitByBullet, etc.)

**Critical implication:** Bullets move before robots. A bullet fired on tick T
is at distance `speed` from origin on tick T+1, *then* the robot moves.

## Movement Physics

### Velocity
- Max velocity: **8.0 px/tick**
- Acceleration: **+1.0 px/tick²**
- Deceleration: **−2.0 px/tick²** (asymmetric!)
- Time 0→8: 8 ticks. Time 8→0: 4 ticks. Decel is 2× faster than accel.

### Body Turning
- Max turn rate: `10 − 0.75 × |velocity|` degrees/tick
- At speed 0: 10°/tick. At speed 8: 4°/tick.
- Turning constrains speed (faster = wider turns)

### Position Update
```
newHeading = heading + turnRate  (clamped to maxTurnRate)
newVelocity = velocity + acceleration  (clamped to [-8, 8])
newX = x + newVelocity × sin(newHeading)
newY = y + newVelocity × cos(newHeading)
```

### Wall Collision
- Robot bounces: velocity set to 0, takes `max(0, abs(velocity)*0.5 - 1)` damage
- Position clamped to wall boundary (18px inset)

## Gun and Radar

### Gun
- Turns independently from body
- Max gun turn: **20°/tick**
- Gun heat starts at 3.0, decreases by `coolingRate` per tick (default 0.1)
- Firing adds `1 + power/5` heat
- Cannot fire when heat > 0

### Radar
- Turns independently from gun
- Max radar turn: **45°/tick**
- Scan arc = sweep between previous and current radar heading
- Returns `ScannedRobotEvent` for each robot in arc

### Adjustment Flags
- `setAdjustGunForRobotTurn(true)` — gun heading is absolute (not relative to body)
- `setAdjustRadarForGunTurn(true)` — radar heading is absolute (not relative to gun)
- Both should be `true` for competitive play

## Bullet Physics

- Speed: `20 − 3 × power` px/tick
  - Power 0.1 → speed 19.7 (fastest)
  - Power 1.0 → speed 17.0
  - Power 3.0 → speed 11.0 (slowest)
- Damage: `4 × power` for power ≤ 1.0, `6 × power − 2` for power > 1.0
  - Power 0.1 → 0.4 damage
  - Power 1.0 → 4.0 damage
  - Power 3.0 → 16.0 damage (40× the damage of 0.1!)
- Energy gain on hit: `3 × power` returned to firer
- Bullet is a point; hit detection checks if point is within 18px of target center

### Maximum Escape Angle (MEA)
- `MEA = arcsin(8.0 / bulletSpeed)`
- At power 3.0 (speed 11): MEA ≈ 46.7°
- At power 0.1 (speed 19.7): MEA ≈ 23.9°

## Energy Model

| Event | Energy Change |
|---|---|
| Start of battle | +100 |
| Fire bullet | −power |
| Bullet hits opponent | +3 × power |
| Hit by opponent bullet | −damage(power) |
| Ram opponent | −0.6 (initiator), −0.6 (both) |
| Hit wall | −max(0, |velocity|×0.5 − 1) |
| Energy ≤ 0 | Robot disabled (cannot move/fire, still visible) |

## Firing Pitfall

**`setFire()` uses the gun heading from the PREVIOUS tick**, not the current one.
The gun turns, then the bullet is created at the new heading. But `setFire()` is
queued and executed at the start of the tick, before the gun turn.

**Practical impact:** Aim the gun one tick ahead. The robot skeleton's
`setTurnGunRightRadians()` + `setFire()` pattern handles this automatically.

## Scoring (1v1)

| Component | Formula |
|---|---|
| Survival | 50 per round survived |
| Last survivor bonus | 10 per round won |
| Bullet damage | 1 per damage dealt |
| Bullet bonus | 20% of total damage dealt (winner only) |
| Ram damage | 2 per ram damage dealt |
| Ram bonus | 30% of total ram damage (winner only) |

**Implication:** Bullet damage scoring rewards aggression. Pure survival
(running away) scores less than aggressive play even if you survive longer.

## Observation Model

### What a robot CAN see
- Own state: `getX()`, `getY()`, `getHeading()`, `getVelocity()`, `getEnergy()`,
  `getGunHeading()`, `getGunHeat()`, `getRadarHeading()`
- Scanned opponent: distance, bearing (relative), heading, velocity, energy, name
- Hit events: `onHitByBullet` (bearing, power), `onBulletHit` (name, energy)

### What a robot CANNOT see
- Opponent's absolute (x, y) — must derive from bearing + distance
- Opponent's gun heading or radar heading
- Opponent's gun heat
- Bullets in flight (no API to detect opponent bullets)
- Opponent's planned actions

### Energy Drop Detection
Opponent fires are inferred from energy drops between consecutive scans:
```
drop = prevOpponentEnergy − currentOpponentEnergy
if (0.1 ≤ drop ≤ 3.0) → opponent fired with power = drop
```
False positives: wall hits, ram damage, our bullet hitting them. Pipeline
filters these via event correlation.

### One-Tick Detection Lag
Energy drop is observed on the tick *after* the opponent fires. The bullet
has already traveled one tick by the time we detect it. This means
`fireDistance` in waves.csv is the distance minus one tick of bullet travel.

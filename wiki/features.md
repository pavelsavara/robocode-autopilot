# Feature Catalog

*80+ features implemented. 443 specified in the full catalog
([archive/2026-05-01-features.md](../archive/2026-05-01-features.md)).*

## Implemented Features (Feature Enum)

### Identity (scores.csv — per-battle constants)
| Feature | Formula | Notes |
|---|---|---|
| `OPPONENT_NAME_HASH` | FNV-1a(full name) | Tightest segmentation |
| `OPPONENT_BOT_ID_HASH` | FNV-1a(name before first space) | Survives version bumps |
| `OPPONENT_VERSION_HASH` | FNV-1a(name after first space) | Distinguishes patches |

### Spatial (ticks.csv — no dependencies)
| Feature | Formula | Notes |
|---|---|---|
| `DISTANCE` | `hypot(oppX−ourX, oppY−ourY)` | Primary segmentation dimension |
| `BEARING_TO_OPPONENT_ABS` | `atan2(dx, dy)` normalised [0, 2π) | Reference angle for decomposition |
| `OPPONENT_DIST_TO_WALL_MIN` | `min(oppX, bfW−oppX, oppY, bfH−oppY)` | Wall constraint |

### Movement (depends on bearing)
| Feature | Formula | Notes |
|---|---|---|
| `OPPONENT_VELOCITY` | Direct from scan | Speed component |
| `OPPONENT_LATERAL_VELOCITY` | `vel × sin(heading − bearing)` | Core GF input |
| `OPPONENT_ADVANCING_VELOCITY` | `−vel × cos(heading − bearing)` | Closing speed |
| `OPPONENT_HEADING_DELTA` | `heading − prevHeading` | Turn rate signal |

### Energy
| Feature | Formula | Notes |
|---|---|---|
| `OPPONENT_ENERGY` | Direct from scan | Budget + desperation |
| `OPPONENT_FIRED` | Energy drop in [0.1, 3.0] | Triggers wave tracking |
| `OPPONENT_FIRE_POWER` | `prevEnergy − currEnergy` | Determines bullet speed |

### Timing
| Feature | Formula | Notes |
|---|---|---|
| `OUR_GUN_HEAT` | Direct from status | Next-fire window |
| `TICKS_SINCE_SCAN` | `tick − lastScanTick` | Data staleness |

### Movement Segmentation
| Feature | Formula | Notes |
|---|---|---|
| `OPPONENT_LATERAL_DIRECTION` | `sign(lateralVelocity)` | +1/−1/0, GF bin |
| `OPPONENT_VELOCITY_DELTA` | `(vel − prevVel) / dt` | Acceleration |
| `OPPONENT_IS_DECELERATING` | `|currVel| < |prevVel|` | Tree models split heavily |
| `OPPONENT_TIME_SINCE_DIRECTION_CHANGE` | Counts from 0 | Oscillation feature |

### Targeting Geometry
| Feature | Formula | Notes |
|---|---|---|
| `OPPONENT_ANGULAR_VELOCITY` | `lateralVel / distance` | Aiming difficulty |
| `OPPONENT_MAX_TURN_RATE` | `toRad(10 − 0.75×|vel|)` | Reachable heading |
| `DISTANCE_NORM` | `distance / diag(bfW, bfH)` | Removes size bias |

### State Normalisation
| Feature | Formula | Notes |
|---|---|---|
| `ENERGY_RATIO` | `ourE / (ourE + oppE)` | 0.5 = even, >0.5 = winning |
| `OUR_LATERAL_VELOCITY` | `ourVel × sin(ourHeading − bearing)` | Dodge capability |
| `OUR_DIST_TO_WALL_MIN` | `min(x−18, bfW−x−18, y−18, bfH−y−18)` | Escape constraint |

### Opponent Prediction
| Feature | Formula | Notes |
|---|---|---|
| `OPPONENT_WALL_AHEAD_DISTANCE` | Ray-cast to nearest wall | Travel limit |
| `OPPONENT_INFERRED_GUN_HEAT` | `max(0, (1+p/5) − elapsed×cooling)` | ⚠️ Leaks `opponent_fired` |

### Wave Features (waves.csv — one row per detected fire)
| Feature | Notes |
|---|---|
| `WAVE_BULLET_POWER` | Same as `OPPONENT_FIRE_POWER` at fire tick |
| `WAVE_BULLET_SPEED` | `20 − 3 × power` |
| `WAVE_FIRE_DISTANCE` | Distance snapshot at fire tick |
| `WAVE_MEA` | `arcsin(8 / speed)` |
| `WAVE_FLIGHT_TIME` | `distance / speed` |
| `WAVE_LATERAL_VELOCITY_AT_FIRE` | Our lateral vel snapshot |

### Score Features (scores.csv — one row per round)
| Feature | Notes |
|---|---|
| `SCORE_DAMAGE_DEALT` | Per-round counter |
| `SCORE_DAMAGE_RECEIVED` | Per-round counter |
| `SCORE_NET_DAMAGE` | `dealt − received` |
| `SCORE_OUR_HIT_RATE` | `hits / max(1, shots)` |
| `SCORE_OPPONENT_HIT_RATE` | `oppHits / max(1, oppShots)` |
| `SCORE_WIN_RATE` | `won / max(1, won + lost)` |

### Tier 1: Wave / MEA / Timing (ticks.csv)
| Feature | Formula |
|---|---|
| `OUR_BULLET_SPEED` | `20 − 3 × ourPower` |
| `OUR_BULLET_TRAVEL_TIME` | `distance / ourBulletSpeed` |
| `MEA_FOR_OUR_BULLET` | `arcsin(8 / ourBulletSpeed)` |
| `OPPONENT_BULLET_SPEED` | `20 − 3 × oppPower` |
| `MEA_FOR_OPPONENT_BULLET` | `arcsin(8 / oppBulletSpeed)` |
| `TICKS_SINCE_WE_FIRED` | Counter |
| `TICKS_SINCE_OPPONENT_FIRED` | Counter |
| `OUR_WAVE_DISTANCE` | `speed × ticks` |
| `OUR_WAVE_REMAINING` | `distance − waveDistance` |
| `OPPONENT_WAVE_DISTANCE` | `speed × ticks` |
| `OPPONENT_WAVE_REMAINING` | `distance − waveDistance` |
| `OPPONENT_WAVE_ETA` | `remaining / speed` |

### Tier 1: GuessFactor (ticks.csv)
| Feature | Formula |
|---|---|
| `LINEAR_TARGET_ANGLE` | `bearing + arcsin(vel/speed × sin(h−bearing))` |
| `LINEAR_TARGET_OFFSET` | `linearAngle − bearing` |
| `CIRCULAR_TARGET_ANGLE` | Iterative (constant turn rate) |
| `CIRCULAR_TARGET_OFFSET` | `circularAngle − bearing` |
| `GF_BEARING_OFFSET` | `bearing − ourGunHeading` |
| `GF_CURRENT_AT_POWER_1` | GF position at power 1.0 |
| `GF_CURRENT_AT_POWER_1_5` | GF position at power 1.5 |
| `GF_CURRENT_AT_POWER_2` | GF position at power 2.0 |
| `OPPONENT_GUESS_FACTOR` | `ourLateralVel / 8` (≡ proxy) |

### Tier 2: Movement History (ticks.csv)
| Feature | Formula |
|---|---|
| `OPPONENT_AVG_LATERAL_VELOCITY_10` | Rolling mean (10 scans) |
| `OPPONENT_AVG_LATERAL_VELOCITY_30` | Rolling mean (30 scans) |
| `OPPONENT_HEADING_DELTA_VARIABILITY_10` | Rolling std (10 scans) |
| `OPPONENT_VELOCITY_VARIABILITY_10` | Rolling std (10 scans) |
| `OPPONENT_TIME_SINCE_VELOCITY_CHANGE` | Counter (≥1 px/tick change) |
| `OPPONENT_DISTANCE_SINCE_DIRECTION_CHANGE` | Cumulative px |

### Tier 2: Battlefield Geometry
| Feature | Formula |
|---|---|
| `OPPONENT_CENTER_DISTANCE` | `hypot(oppX − bfW/2, oppY − bfH/2)` |
| `OPPONENT_CORNER_PROXIMITY` | Min distance to 4 corners |

### Tier 3: Scan Coverage
| Feature | Notes |
|---|---|
| `TICKS_BETWEEN_SCANS` | Data quality |
| `SCAN_COVERAGE_20` | Fraction of last 20 ticks scanned |
| `SCAN_COVERAGE_50` | Fraction of last 50 ticks scanned |
| `SCAN_ARC_WIDTH` | Radar sweep width |
| `RADAR_LOCKED` | 5+ consecutive scans |
| `RADAR_TURN_DIRECTION` | +1/−1/0 |

### Tier 3: Danger Assessment
| Feature | Formula |
|---|---|
| `ESCAPE_ANGLE_COVERAGE` | `min(1, eta × 8 / (mea × dist))` |

### Absolute Positions
| Feature | Notes |
|---|---|
| `OUR_X`, `OUR_Y` | Observable via `getX()`/`getY()` |
| `OUR_HEADING`, `OUR_VELOCITY` | Raw state |
| `OPPONENT_X`, `OPPONENT_Y` | Derived from bearing + distance |
| `OPPONENT_HEADING` | Direct from scan |

### Multi-Wave Tracking
| Feature | Notes |
|---|---|
| `N_OPPONENT_WAVES_IN_FLIGHT` | Count of active opponent waves |
| `N_OUR_WAVES_IN_FLIGHT` | Count of active our waves |
| `NEAREST_OPPONENT_WAVE_GAP` | Min tick-gap between adjacent opponent waves |
| `TOTAL_OPPONENT_WAVE_DAMAGE` | Sum of damage from all in-flight opponent waves |
| `NEAREST_OUR_WAVE_GAP` | Min tick-gap between our waves |

### 20-Tick Sliding Window Statistics
Computed by `WindowFeatures` (O(1) incremental mean/std over ring buffers).

| Feature | Base feature |
|---|---|
| `DISTANCE_WMEAN`, `DISTANCE_WSTD` | `DISTANCE` |
| `BEARING_TO_OPPONENT_ABS_WMEAN`, `_WSTD` | `BEARING_TO_OPPONENT_ABS` |
| `OPPONENT_DIST_TO_WALL_MIN_WMEAN`, `_WSTD` | `OPPONENT_DIST_TO_WALL_MIN` |
| `OUR_GUN_HEAT_WMEAN`, `_WSTD` | `OUR_GUN_HEAT` |
| `TICKS_SINCE_SCAN_WMEAN`, `_WSTD` | `TICKS_SINCE_SCAN` |
| `OPPONENT_ENERGY_WMEAN`, `_WSTD` | `OPPONENT_ENERGY` |
| `OUR_X_WMEAN`, `OUR_X_WSTD` | `OUR_X` |
| `OUR_Y_WMEAN`, `OUR_Y_WSTD` | `OUR_Y` |
| `OUR_HEADING_WMEAN`, `_WSTD` | `OUR_HEADING` |
| `OUR_VELOCITY_WMEAN`, `_WSTD` | `OUR_VELOCITY` |

### Opponent Profile (set once per battle from offline lookup)
| Feature | Range | Source |
|---|---|---|
| `OPPONENT_STRENGTH_RATING` | [0, 1] | Offline win-rate data (stub: returns 0.5) |

### Predictor Outputs (written by trivial/ML predictors)
| Feature | Range | Source |
|---|---|---|
| `PREDICTED_FIRE_POWER` | [0.1, 3.0] | Fire power predictor |
| `PREDICTED_FIRE_POWER_CONFIDENCE` | [0, 1] | |
| `PREDICTED_LAT_VEL_5` | px/tick | Movement predictor |
| `PREDICTED_LAT_VEL_5_CONFIDENCE` | [0, 1] | |
| `PREDICTED_OPPONENT_FIRES_3` | [0, 1] | Fire timing predictor |
| `PREDICTED_OPPONENT_FIRES_3_CONFIDENCE` | [0, 1] | |

---

## Planned Features (Not Yet Implemented)

### Full Catalog Reference

The complete 443-feature catalog with formulas is in
[archive/2026-05-01-features.md](../archive/2026-05-01-features.md).

**Category summary:**

| Category | Feature count | Implemented |
|---|---|---|
| Spatial | 25 | 3 |
| Movement | 17 | 4 |
| Energy | 10 | 3 |
| Bullet & Wave | 10 | 12 |
| Targeting Geometry | 9 | 9 |
| Timing | 11 | 7 |
| Battlefield Geometry | 12 | 2 |
| Movement Patterns | 10 | 6 |
| Firing Strategy | 10 | 0 |
| Danger Assessment | 6 | 1 |
| Scan-Based | 8 | 6 |
| GuessFactor | 12 | 9 |
| Wave Surfing Geometry | 12 | 0 |
| Pattern & Movement Rhythm | 18 | 0 |
| Combat State | 18 | 0 |
| Wall Interaction | 15 | 0 |
| Opponent Modeling | 15 | 0 |
| Multi-Tick Deltas | 19 | 0 |
| Stage 2 Extensions | ~135 | 0 |

**Priority for next implementation round:**
1. Multi-wave pressure features (3) — highest ML importance
2. Wave surfing geometry (12) — needed for path planning
3. Combat state features (18) — needed for strategy layer
4. Firing strategy features (10) — needed for gun selection

package cz.zamboch.autopilot.core;

/**
 * Numeric enum of all feature keys. Array-backed storage in Whiteboard for O(1) access.
 */
public enum Feature {

    // === Identity (no dependencies) ===

    /** Stable hash of full opponent robot name (incl. version). Unit: integer (32-bit).
     *  Eq: FNV-1a hash of opponent name string. Tightest segmentation — same exact build. */
    OPPONENT_NAME_HASH,

    /** Stable hash of opponent bot family (part before first space). Unit: integer (32-bit).
     *  Eq: FNV-1a hash of substring(0, indexOf(' ')). Survives version bumps — primary fingerprint. */
    OPPONENT_BOT_ID_HASH,

    /** Stable hash of opponent version (part after first space). Unit: integer (32-bit).
     *  Eq: FNV-1a hash of substring(indexOf(' ')+1). 0 if no version. Distinguishes patches. */
    OPPONENT_VERSION_HASH,

    // === Spatial (no dependencies) ===

    /** Euclidean distance to opponent. Unit: px. Eq: hypot(oppX-ourX, oppY-ourY).
     *  Primary segmentation dimension for targeting and movement. */
    DISTANCE,

    /** Absolute bearing from us to opponent. Unit: rad [0,2PI). Eq: atan2(dx,dy) normalised.
     *  Reference angle for lateral/advancing velocity decomposition. */
    BEARING_TO_OPPONENT_ABS,

    /** Opponent min distance to any wall (centre-based). Unit: px.
     *  Eq: min(oppX, bfW-oppX, oppY, bfH-oppY). Wall proximity constrains escape. */
    OPPONENT_DIST_TO_WALL_MIN,

    // === Movement (depends on BEARING_TO_OPPONENT_ABS) ===

    /** Opponent velocity (raw scan value). Unit: px/tick. Direct from snapshot.
     *  Speed component of movement profile. */
    OPPONENT_VELOCITY,

    /** Opponent velocity component perpendicular to bearing line. Unit: px/tick.
     *  Eq: velocity * sin(oppHeading - absBearing). Core GF targeting input. */
    OPPONENT_LATERAL_VELOCITY,

    /** Opponent velocity component along bearing line (positive = approaching). Unit: px/tick.
     *  Eq: -velocity * cos(oppHeading - absBearing). Closing-speed indicator. */
    OPPONENT_ADVANCING_VELOCITY,

    /** Opponent heading change since previous scan. Unit: rad.
     *  Eq: normalRelAngle(oppHeading - prevOppHeading). Turn-rate signal. */
    OPPONENT_HEADING_DELTA,

    // === Energy (no dependencies) ===

    /** Opponent energy from last scan. Unit: energy (0-100+). Direct from snapshot.
     *  Determines bullet power budget and desperation level. */
    OPPONENT_ENERGY,

    /** Whether opponent fired (energy-drop heuristic). Unit: boolean (0/1).
     *  Eq: energyDrop in [0.1,3.0] on consecutive scans, excluding our hit/wall.
     *  Triggers wave tracking and dodge timing. */
    OPPONENT_FIRED,

    /** Inferred bullet power from energy drop. Unit: energy [0.1,3.0].
     *  Eq: prevEnergy - currEnergy (when OPPONENT_FIRED). Determines bullet speed. */
    OPPONENT_FIRE_POWER,

    // === Timing (no dependencies) ===

    /** Our current gun heat. Unit: heat. Direct from robot status.
     *  Determines when we can fire next. */
    OUR_GUN_HEAT,

    /** Ticks since we last scanned the opponent. Unit: ticks.
     *  Eq: currentTick - lastScanTick. Data staleness indicator. */
    TICKS_SINCE_SCAN,

    // === Movement segmentation (depends on OPPONENT_LATERAL_VELOCITY, OPPONENT_VELOCITY) ===

    /** Sign of opponent lateral velocity: +1=CW, -1=CCW, 0=stopped. Unit: {-1,0,1}.
     *  Eq: sign(OPPONENT_LATERAL_VELOCITY). Core GF segmentation bin. */
    OPPONENT_LATERAL_DIRECTION,

    /** Opponent acceleration. Unit: px/tick^2.
     *  Eq: (velocity - prevVelocity) / deltaTicks. Anticipates speed changes. */
    OPPONENT_VELOCITY_DELTA,

    /** Whether opponent is slowing down. Unit: boolean (0/1).
     *  Eq: |currentVel| < |prevVel|. Tree models split heavily on deceleration. */
    OPPONENT_IS_DECELERATING,

    /** Ticks since opponent lateral direction last changed sign. Unit: ticks.
     *  Counts up from 0 on each sign change. Key oscillation/pattern feature. */
    OPPONENT_TIME_SINCE_DIRECTION_CHANGE,

    // === Targeting geometry (depends on OPPONENT_LATERAL_VELOCITY, DISTANCE, OPPONENT_VELOCITY) ===

    /** Opponent angular sweep rate across our FOV. Unit: rad/tick.
     *  Eq: OPPONENT_LATERAL_VELOCITY / DISTANCE. Primary aiming-difficulty input. */
    OPPONENT_ANGULAR_VELOCITY,

    /** Max turn rate opponent could achieve at current speed. Unit: rad/tick.
     *  Eq: toRadians(10 - 0.75 * |velocity|). Constrains reachable heading space. */
    OPPONENT_MAX_TURN_RATE,

    /** Distance normalised by battlefield diagonal. Unit: [0,1].
     *  Eq: DISTANCE / hypot(bfW, bfH). Removes battlefield-size bias for ML. */
    DISTANCE_NORM,

    // === State normalisation (depends on BEARING_TO_OPPONENT_ABS) ===

    /** Energy balance. Unit: [0,1].
     *  Eq: ourEnergy / (ourEnergy + oppEnergy). Better than raw advantage for ML. */
    ENERGY_RATIO,

    /** Our velocity perpendicular to bearing line to opponent. Unit: px/tick.
     *  Eq: ourVelocity * sin(ourHeading - absBearing). Dodge-capability indicator. */
    OUR_LATERAL_VELOCITY,

    /** Our min distance to any wall (18px robot-centre offset). Unit: px.
     *  Eq: min(x-18, bfW-x-18, y-18, bfH-y-18). Limits our escape options. */
    OUR_DIST_TO_WALL_MIN,

    // === Opponent prediction (depends on OPPONENT_FIRED, OPPONENT_FIRE_POWER) ===

    /** Ray-cast distance from opponent along their heading to nearest wall. Unit: px.
     *  Eq: min wall intersection along (sin(h),cos(h)) with 18px offset.
     *  Predicts how far opponent can travel before wall collision. */
    OPPONENT_WALL_AHEAD_DISTANCE,

    /** Estimated opponent gun heat based on last detected fire. Unit: heat.
     *  Eq: max(0, (1+firePower/5) - elapsed*coolingRate). Predicts next fire window. */
    OPPONENT_INFERRED_GUN_HEAT,

    // === Wave features (one row per detected opponent fire — WAVES file type) ===
    // Depends on OPPONENT_FIRED, OPPONENT_FIRE_POWER, DISTANCE, OUR_LATERAL_VELOCITY.

    /** Bullet power of the detected opponent shot. Unit: energy [0.1,3.0].
     *  Same as OPPONENT_FIRE_POWER, captured as wave-event column. */
    WAVE_BULLET_POWER,

    /** Speed of the detected opponent bullet. Unit: px/tick [11,19.7].
     *  Eq: 20 - 3 * firePower. Determines wave travel rate and MEA. */
    WAVE_BULLET_SPEED,

    /** Distance to opponent at the moment they fired. Unit: px.
     *  Snapshot of DISTANCE at fire tick. Sets total wave travel distance. */
    WAVE_FIRE_DISTANCE,

    /** Maximum escape angle against this bullet. Unit: rad.
     *  Eq: asin(8.0 / bulletSpeed). Defines reachable GF range. */
    WAVE_MEA,

    /** Estimated ticks for this wave to reach us. Unit: ticks.
     *  Eq: fireDistance / bulletSpeed. Dodge-time budget. */
    WAVE_FLIGHT_TIME,

    /** Our lateral velocity when the opponent fired. Unit: px/tick.
     *  Snapshot of OUR_LATERAL_VELOCITY at fire tick. Initial dodge state. */
    WAVE_LATERAL_VELOCITY_AT_FIRE,

    // === Score features (one row per round end — SCORES file type) ===
    // No feature dependencies — reads per-round Whiteboard counters directly.

    /** Bullet damage we dealt to opponent this round. Unit: energy.
     *  Per-round counter from Whiteboard. Offensive effectiveness. */
    SCORE_DAMAGE_DEALT,

    /** Bullet damage received from opponent this round. Unit: energy.
     *  Per-round counter from Whiteboard. Defensive pressure. */
    SCORE_DAMAGE_RECEIVED,

    /** Net damage this round. Unit: energy.
     *  Eq: damageDealtThisRound - damageReceivedThisRound. Combat advantage. */
    SCORE_NET_DAMAGE,

    /** Our bullet hit rate this round. Unit: [0,1].
     *  Eq: ourBulletHitCountThisRound / max(1, ourShotsFiredThisRound). */
    SCORE_OUR_HIT_RATE,

    /** Opponent hit rate on us this round. Unit: [0,1].
     *  Eq: opponentBulletHitCountThisRound / max(1, opponentShotsDetectedThisRound). */
    SCORE_OPPONENT_HIT_RATE,

    /** Win rate across completed rounds. Unit: [0,1].
     *  Eq: roundsWon / max(1, roundsWon + roundsLost). Match-level trend. */
    SCORE_WIN_RATE,

    // === Tier 1: Wave / MEA / timing (TICKS file) ===

    /** Bullet speed of our last fired bullet. Unit: px/tick. Eq: 20 - 3*ourFirePower. */
    OUR_BULLET_SPEED,
    /** Travel time of our hypothetical bullet to opponent. Unit: ticks. Eq: distance/ourBulletSpeed. */
    OUR_BULLET_TRAVEL_TIME,
    /** Maximum escape angle for our bullet. Unit: rad. Eq: asin(8/ourBulletSpeed). */
    MEA_FOR_OUR_BULLET,
    /** Bullet speed of opponent's last detected bullet. Unit: px/tick. Eq: 20 - 3*opponentFirePower. */
    OPPONENT_BULLET_SPEED,
    /** MEA for opponent's last detected bullet. Unit: rad. Eq: asin(8/opponentBulletSpeed). */
    MEA_FOR_OPPONENT_BULLET,
    /** Ticks since we last fired. Unit: ticks. Eq: tick - lastOurFireTick. */
    TICKS_SINCE_WE_FIRED,
    /** Ticks since opponent last fired (energy-drop detection). Unit: ticks. */
    TICKS_SINCE_OPPONENT_FIRED,
    /** Distance our last bullet has traveled. Unit: px. Eq: ourBulletSpeed*ticks_since_we_fired. */
    OUR_WAVE_DISTANCE,
    /** Distance our last bullet still has to travel to opponent. Unit: px (negative = wave passed). */
    OUR_WAVE_REMAINING,
    /** Distance opponent's last bullet has traveled. Unit: px. */
    OPPONENT_WAVE_DISTANCE,
    /** Distance opponent's last bullet still has to travel to us. Unit: px. */
    OPPONENT_WAVE_REMAINING,
    /** Estimated ticks until opponent's last bullet reaches us. Unit: ticks. */
    OPPONENT_WAVE_ETA,

    // === Tier 1: Targeting + GuessFactor (TICKS file) ===

    /** Linear-prediction target angle (exact non-iterative). Unit: rad.
     *  Eq: bearing + asin(oppVel/ourBulletSpeed * sin(oppHeading-bearing)). */
    LINEAR_TARGET_ANGLE,
    /** Offset from current bearing to linear target angle. Unit: rad. */
    LINEAR_TARGET_OFFSET,
    /** Circular-prediction target angle (iterative). Unit: rad. */
    CIRCULAR_TARGET_ANGLE,
    /** Offset from bearing to circular target angle. Unit: rad. */
    CIRCULAR_TARGET_OFFSET,
    /** Angular offset from our gun heading to opponent bearing. Unit: rad.
     *  Eq: normalRelativeAngle(bearing - ourGunHeading). */
    GF_BEARING_OFFSET,
    /** Current GF position at bullet power 1.0. Unit: GF [-1,1].
     *  Eq: clamp(gf_bearing_offset * lateralDir / mea_at_power_1). */
    GF_CURRENT_AT_POWER_1,
    /** Current GF position at bullet power 1.5. Unit: GF [-1,1]. */
    GF_CURRENT_AT_POWER_1_5,
    /** Current GF position at bullet power 2.0. Unit: GF [-1,1]. */
    GF_CURRENT_AT_POWER_2,
    /** Where we sit in opponent's GF space (proxy: our_lateral_velocity / 8). Unit: GF [-1,1]. */
    OPPONENT_GUESS_FACTOR,

    // === Tier 2: Movement history / segmentation (TICKS file) ===

    /** Rolling mean of opponent_lateral_velocity over last 10 scans. Unit: px/tick. */
    OPPONENT_AVG_LATERAL_VELOCITY_10,
    /** Rolling mean over last 30 scans. Unit: px/tick. */
    OPPONENT_AVG_LATERAL_VELOCITY_30,
    /** Std-dev of opponent_heading_delta over last 10 scans. Unit: rad/tick. */
    OPPONENT_HEADING_DELTA_VARIABILITY_10,
    /** Std-dev of opponent_velocity over last 10 scans. Unit: px/tick. */
    OPPONENT_VELOCITY_VARIABILITY_10,
    /** Ticks since opponent_velocity last changed by >=1 px/tick. Unit: ticks. */
    OPPONENT_TIME_SINCE_VELOCITY_CHANGE,
    /** Cumulative distance opponent traveled since last lateral-direction reversal. Unit: px. */
    OPPONENT_DISTANCE_SINCE_DIRECTION_CHANGE,

    // === Tier 2: Battlefield geometry (TICKS file) ===

    /** Distance from opponent to battlefield center. Unit: px.
     *  Eq: hypot(oppX - bfW/2, oppY - bfH/2). */
    OPPONENT_CENTER_DISTANCE,
    /** Min distance from opponent to any of the 4 corners (18px inset). Unit: px. */
    OPPONENT_CORNER_PROXIMITY,

    // === Tier 3: Scan coverage (TICKS file) ===

    /** Ticks between current scan and previous scan. Unit: ticks. */
    TICKS_BETWEEN_SCANS,
    /** Fraction of last 20 ticks that had a scan. Unit: [0,1]. */
    SCAN_COVERAGE_20,
    /** Fraction of last 50 ticks that had a scan. Unit: [0,1]. */
    SCAN_COVERAGE_50,
    /** Width of radar sweep arc this tick. Unit: rad.
     *  Eq: |normalRelativeAngle(radarHeading - prevRadarHeading)|. */
    SCAN_ARC_WIDTH,
    /** Whether radar is locked (5+ consecutive scans with gap <=2). Unit: boolean. */
    RADAR_LOCKED,
    /** Sign of radar sweep direction: +1=CW, -1=CCW, 0=none. Unit: {-1,0,1}. */
    RADAR_TURN_DIRECTION,

    // === Tier 3: Danger assessment (TICKS file) ===

    /** Fraction of MEA arc reachable before opponent's bullet arrives. Unit: [0,1].
     *  Eq: min(1, opponent_wave_eta * MAX_VELOCITY / (mea * distance)). */
    ESCAPE_ANGLE_COVERAGE,

    // === Absolute positions (no dependencies — reads Whiteboard state directly) ===

    /** Our absolute X position. Unit: px. Direct from StatusEvent.getX(). */
    OUR_X,
    /** Our absolute Y position. Unit: px. Direct from StatusEvent.getY(). */
    OUR_Y,
    /** Our absolute heading. Unit: rad [0,2PI). Direct from StatusEvent.getHeading(). */
    OUR_HEADING,
    /** Our raw velocity (signed). Unit: px/tick. Direct from StatusEvent.getVelocity(). */
    OUR_VELOCITY,
    /** Opponent absolute X position. Unit: px. Derived from bearing + distance. */
    OPPONENT_X,
    /** Opponent absolute Y position. Unit: px. Derived from bearing + distance. */
    OPPONENT_Y,
    /** Opponent absolute heading. Unit: rad [0,2PI). Direct from ScannedRobotEvent. */
    OPPONENT_HEADING,

    // === Multi-wave tracking (depends on OPPONENT_FIRED) ===

    /** Number of opponent waves currently in flight (not yet reached us). Unit: count. */
    N_OPPONENT_WAVES_IN_FLIGHT,
    /** Number of our waves currently in flight (not yet reached opponent). Unit: count. */
    N_OUR_WAVES_IN_FLIGHT,

    // === Multi-wave pressure (depends on multi-wave tracking) ===

    /** Minimum tick-gap between adjacent opponent waves in flight. Unit: ticks.
     *  Eq: min over consecutive wave pairs of |fireTick_i - fireTick_j|.
     *  When only 0-1 waves in flight, set to 0. Low values = tight pressure. */
    NEAREST_OPPONENT_WAVE_GAP,

    /** Total potential damage from all opponent waves currently in flight. Unit: energy.
     *  Eq: sum of damage(power) for each wave. High values = dangerous multi-wave state. */
    TOTAL_OPPONENT_WAVE_DAMAGE,

    /** Minimum tick-gap between adjacent our waves in flight. Unit: ticks.
     *  Eq: min over consecutive wave pairs of |fireTick_i - fireTick_j|.
     *  Low values = we are applying tight wave pressure. */
    NEAREST_OUR_WAVE_GAP,

    // === Predictor outputs (scalar predictions written by IInGameFeatures predictors) ===

    /** Predicted opponent fire power. Unit: energy [0.1,3.0]. From fire-power predictor. */
    PREDICTED_FIRE_POWER,
    /** Confidence of fire power prediction. Unit: [0,1]. */
    PREDICTED_FIRE_POWER_CONFIDENCE,
    /** Predicted probability of winning the round. Unit: [0,1]. From round-outcome predictor. */
    PREDICTED_WIN_PROBABILITY,
    /** Confidence of win probability prediction. Unit: [0,1]. */
    PREDICTED_WIN_PROBABILITY_CONFIDENCE,
    /** Predicted opponent lateral velocity at t+5. Unit: px/tick. From movement predictor. */
    PREDICTED_LAT_VEL_5,
    /** Confidence of lateral velocity prediction. Unit: [0,1]. */
    PREDICTED_LAT_VEL_5_CONFIDENCE,
    /** Predicted probability opponent fires within 3 ticks. Unit: [0,1]. From fire-timing predictor. */
    PREDICTED_OPPONENT_FIRES_3,
    /** Confidence of fire timing prediction. Unit: [0,1]. */
    PREDICTED_OPPONENT_FIRES_3_CONFIDENCE;
}

package cz.zamboch.autopilot.core;

/**
 * Numeric enum of all feature keys. Array-backed storage in Whiteboard for O(1) access.
 */
public enum Feature {

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
    OPPONENT_INFERRED_GUN_HEAT;
}

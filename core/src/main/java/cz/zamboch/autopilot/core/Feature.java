package cz.zamboch.autopilot.core;

/**
 * Numeric enum of all feature keys. Array-backed storage in Whiteboard for O(1) access.
 */
public enum Feature {
    DISTANCE,
    BEARING_TO_OPPONENT_ABS,
    OPPONENT_VELOCITY,
    OPPONENT_LATERAL_VELOCITY,
    OPPONENT_ADVANCING_VELOCITY,
    OPPONENT_HEADING_DELTA,
    OPPONENT_ENERGY,
    OPPONENT_FIRED,
    OPPONENT_FIRE_POWER,
    OUR_GUN_HEAT,
    TICKS_SINCE_SCAN,
    OPPONENT_DIST_TO_WALL_MIN;
}

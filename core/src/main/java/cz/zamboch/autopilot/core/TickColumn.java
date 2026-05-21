package cz.zamboch.autopilot.core;

/**
 * Column indices for the TickRing table (depth=2 ring buffer).
 * Entries are ordered to match Feature enum's TICKS-type features
 * in declaration order.
 */
public enum TickColumn {
    OUR_X,
    OUR_Y,
    OUR_HEADING,
    OUR_VELOCITY,
    OUR_ENERGY,
    GUN_HEAT,
    GUN_HEADING,
    RADAR_HEADING,
    TICK,
    BATTLEFIELD_WIDTH,
    BATTLEFIELD_HEIGHT,
    DISTANCE,
    BEARING_RADIANS,
    OPPONENT_HEADING,
    OPPONENT_VELOCITY,
    OPPONENT_ENERGY,
    LAST_SCAN_TICK,
    OUR_BULLET_DAMAGE_TO_OPPONENT,
    OPPONENT_BULLET_ENERGY_GAIN,
    RAM_DAMAGE_TO_OPPONENT,
    OPPONENT_BEARING_ABSOLUTE,
    OPPONENT_X,
    OPPONENT_Y,
    OPPONENT_LATERAL_VELOCITY,
    OPPONENT_ADVANCING_VELOCITY,
    GUN_AIM_POWER,
    GUN_AIM_ANGLE,
    TICKS_SINCE_SCAN,
    PREV_SCAN_OPPONENT_ENERGY,
    OPPONENT_ID,
    OPPONENT_ID_HASH;

    public static final int COUNT = values().length;
}

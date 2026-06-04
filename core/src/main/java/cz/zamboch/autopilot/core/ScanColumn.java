package cz.zamboch.autopilot.core;

/**
 * Column indices for the scan-event ring buffer.
 * Entries are ordered to match Feature enum's {@link FileType#SCAN} features
 * in declaration order.
 */
public enum ScanColumn {
    SCAN_TICK,
    DISTANCE,
    BEARING_RADIANS,
    OPPONENT_HEADING,
    OPPONENT_VELOCITY,
    OPPONENT_ENERGY,
    OPPONENT_ID,
    OPPONENT_ID_HASH,
    OPPONENT_BEARING_ABSOLUTE,
    OPPONENT_X,
    OPPONENT_Y,
    OPPONENT_LATERAL_VELOCITY,
    OPPONENT_ADVANCING_VELOCITY,
    TICKS_SINCE_SCAN,
    PREV_SCAN_OPPONENT_ENERGY,
    OUR_BULLET_DAMAGE_TO_OPPONENT,
    OPPONENT_BULLET_ENERGY_GAIN,
    RAM_DAMAGE_TO_OPPONENT,
    OPPONENT_WALL_HIT_DAMAGE,
    THEIR_GUN_HEAT,
    THEIR_INACTIVITY_ZAP_ACTIVE,
    THEIR_ENERGY_DROP_ADJUSTED;

    public static final int COUNT = values().length;
}

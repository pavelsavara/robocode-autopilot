package cz.zamboch.autopilot.core;

/**
 * Column indices for the TheirWaveTable ring buffer.
 * Each slot tracks one detected opponent bullet from fire to break.
 */
public enum TheirWaveColumn {
    // --- Fire-time columns (frozen at detection) ---
    FIRE_POWER,
    FIRE_TICK,
    FIRE_X,
    FIRE_Y,
    BULLET_SPEED,
    FIRE_BEARING, // absolute bearing from them to us at fire time
    FIRE_DISTANCE,
    FIRE_OUR_X, // our position at their fire time
    FIRE_OUR_Y,

    // --- Break-time columns (filled at resolution) ---
    BREAK_TICK,
    BREAK_OUR_X,
    BREAK_OUR_Y,
    BREAK_GF, // GF where we were (their perspective)
    BREAK_BEARING_OFFSET,
    HIT_US; // 1 if their bullet hit us, 0 otherwise

    public static final int COUNT = values().length;
}

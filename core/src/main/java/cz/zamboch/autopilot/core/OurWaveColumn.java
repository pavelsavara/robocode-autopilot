package cz.zamboch.autopilot.core;

/**
 * Column indices for the OurWaveTable ring buffer (capacity=64).
 * Each row stores the complete lifecycle of one bullet wave we fired.
 * Entries are ordered to match Feature enum's OUR_WAVES-type features
 * in declaration order.
 */
public enum OurWaveColumn {
    FIRE_DISTANCE,
    FIRE_LATERAL_VELOCITY,
    FIRE_ADVANCING_VELOCITY,
    FIRE_BULLET_SPEED,
    FIRE_MEA,
    FIRE_DIRECTION,
    FIRE_BEARING_ABSOLUTE,
    FIRE_X,
    FIRE_Y,
    FIRE_OPPONENT_X,
    FIRE_OPPONENT_Y,
    FIRE_POWER,
    FIRE_TICK,
    FIRE_BULLET_ID,
    BREAK_TICK,
    BREAK_GF,
    BREAK_BEARING_OFFSET,
    BREAK_OPPONENT_X,
    BREAK_OPPONENT_Y,
    BREAK_HIT;

    public static final int COUNT = values().length;
}

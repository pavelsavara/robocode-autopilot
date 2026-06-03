package cz.zamboch.autopilot.core;

/**
 * Column indices for the TickRing table (depth=3 ring buffer).
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
    TICK;

    public static final int COUNT = values().length;
}

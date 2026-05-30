package cz.zamboch.autopilot.core;

/**
 * Column indices for the NoneRing table (depth=3 ring buffer).
 * Entries are ordered to match Feature enum's {@link FileType#NONE} features
 * in declaration order.
 * <p>
 * NONE features are robot-side decision outputs and inter-tick accumulators
 * that are kept in the whiteboard (with the same per-tick / N-ticks-ago ring
 * semantics as TICKS features) but are never written to any CSV.
 */
public enum NoneColumn {
    OUR_BULLET_DAMAGE_TO_OPPONENT,
    OPPONENT_BULLET_ENERGY_GAIN,
    RAM_DAMAGE_TO_OPPONENT,
    OPPONENT_RAM_ENERGY_GAIN,
    OPPONENT_WALL_HIT_DAMAGE,
    GUN_AIM_POWER,
    GUN_AIM_ANGLE,
    GUN_AIM_GF,
    PREV_SCAN_OPPONENT_ENERGY;

    public static final int COUNT = values().length;
}

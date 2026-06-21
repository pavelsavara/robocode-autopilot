package cz.zamboch.autopilot.core;

/**
 * Column indices for the OurWaveTable ring buffer (capacity=64).
 * Each row stores the complete lifecycle of one bullet wave we fired.
 * The first entries (through {@code BREAK_HIT}) are ordered to match Feature
 * enum's OUR_WAVES-type features in declaration order. Trailing entries (e.g.
 * {@code WAVE_ID}) are internal bookkeeping columns with no Feature mapping;
 * they must stay AFTER the Feature-aligned columns so the staging dispatch in
 * {@link Whiteboard} (which indexes by {@code Feature.columnIndex()}) is never
 * disturbed.
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
    AIM_GF,
    IS_REAL,
    // Aim-time geometry (tick before fire, T-1) — see Feature.OUR_AIM_*.
    AIM_X,
    AIM_Y,
    AIM_OPPONENT_X,
    AIM_OPPONENT_Y,
    AIM_DISTANCE,
    // Opponent lateral velocity at aim time (T-1) — see Feature.OUR_AIM_LATERAL_VELOCITY.
    AIM_LATERAL_VELOCITY,
    AIM_BEARING_ABSOLUTE,
    // Lag-1 dodge-context developing guess factor captured at aim time — see
    // Feature.OUR_AIM_LAG1_GF. VcsStore bins this raw GF into a lag-1 slice.
    AIM_LAG1_GF,
    BREAK_TICK,
    BREAK_GF,
    BREAK_BEARING_OFFSET,
    BREAK_OPPONENT_X,
    BREAK_OPPONENT_Y,
    BREAK_HIT,

    /**
     * Stable, deterministic wave identifier (internal — no Feature mapping).
     * Assigned at creation as {@code fireTick * 1000 + groupIndex} where
     * groupIndex is 0 for the real bullet and 1..N for virtual bullets. Because
     * {@code fireTick} is identical on the live robot and the observer shadow,
     * the same logical wave receives the same id on both sides, enabling
     * per-wave Layer 0 fidelity comparison.
     */
    WAVE_ID;

    public static final int COUNT = values().length;
}

package cz.zamboch.autopilot.core;

/**
 * Column indices for the ScoreRow (per-round results).
 * Entries are ordered to match Feature enum's SCORES-type features
 * in declaration order.
 */
public enum ScoreColumn {
    HIT_RATE,
    RESULT;

    public static final int COUNT = values().length;
}

package cz.zamboch.autopilot.core;

/**
 * Column indices for the decision ring table (depth=3 ring buffer).
 * Entries are ordered to match Feature enum's {@link FileType#DECISIONS}
 * features in declaration order.
 */
public enum DecisionColumn {
    GUN_AIM_POWER,
    GUN_AIM_ANGLE,
    GUN_AIM_GF;

    public static final int COUNT = values().length;
}
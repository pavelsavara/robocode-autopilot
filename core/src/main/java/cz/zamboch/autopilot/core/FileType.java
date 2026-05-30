package cz.zamboch.autopilot.core;

/**
 * Declares which CSV output file a feature belongs to,
 * and what event triggers a new row in that file.
 */
public enum FileType {
    /** One row per simulation tick. */
    TICKS,
    /** One row per detected opponent fire (energy drop). */
    THEIR_WAVES,
    /**
     * One row per bullet we fire (GF gun learning: fire_* at fire, break_* at
     * resolution).
     */
    OUR_WAVES,
    /** One row per round (outcomes + per-battle constants). */
    SCORES,
    /**
     * Whiteboard-internal state only; never written to any CSV. Used for
     * robot-side decision outputs (gun aim) and inter-tick accumulators that are
     * inputs to other features but are not themselves part of the dataset.
     * Backed by the tick ring (carries the same per-tick / N-ticks-ago
     * semantics as {@link #TICKS}).
     */
    NONE
}

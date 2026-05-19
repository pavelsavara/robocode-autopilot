package cz.zamboch.autopilot.core;

/**
 * Declares which CSV output file a feature belongs to,
 * and what event triggers a new row in that file.
 */
public enum FileType {
    /** One row per simulation tick. */
    TICKS,
    /** One row per detected opponent fire (energy drop). */
    WAVES,
    /** One row per round (outcomes + per-battle constants). */
    SCORES
}

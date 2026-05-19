package cz.zamboch.autopilot.core;

/**
 * Minimal feature enum. Each entry is an array index into Whiteboard's feature
 * store. Each feature declares which CSV file it belongs to via
 * {@link FileType}.
 * Grow this as new features are needed — but only add what's actually consumed.
 */
public enum Feature {
    // --- Spatial (per-tick) ---
    DISTANCE(FileType.TICKS),
    BEARING_RADIANS(FileType.TICKS),
    OUR_X(FileType.TICKS),
    OUR_Y(FileType.TICKS),
    OPPONENT_BEARING_ABSOLUTE(FileType.TICKS),

    // --- Movement (per-tick) ---
    OUR_VELOCITY(FileType.TICKS),
    OUR_HEADING(FileType.TICKS),
    OPPONENT_VELOCITY(FileType.TICKS),
    OPPONENT_HEADING(FileType.TICKS),
    OPPONENT_LATERAL_VELOCITY(FileType.TICKS),
    OPPONENT_ADVANCING_VELOCITY(FileType.TICKS),

    // --- Energy (per-tick) ---
    OUR_ENERGY(FileType.TICKS),
    OPPONENT_ENERGY(FileType.TICKS),

    // --- Timing (per-tick) ---
    TICK(FileType.TICKS),
    GUN_HEAT(FileType.TICKS),
    TICKS_SINCE_SCAN(FileType.TICKS);

    private final FileType fileType;

    Feature(FileType fileType) {
        this.fileType = fileType;
    }

    /** Which CSV file this feature is written to. */
    public FileType getFileType() {
        return fileType;
    }

    /** Total number of features — use for array sizing. */
    public static final int COUNT = values().length;
}

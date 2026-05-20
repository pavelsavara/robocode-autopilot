package cz.zamboch.autopilot.core;

/**
 * Feature enum. Each entry is an array index into Whiteboard's feature store.
 * Each feature declares which CSV file it belongs to via {@link FileType}.
 * Features are either "input" (set by event handlers or pipeline) or "computed"
 * (derived by IInGameFeatures during process()).
 */
public enum Feature {
    // --- Input: spatial (set by onStatus / pipeline) ---
    OUR_X(FileType.TICKS),
    OUR_Y(FileType.TICKS),
    OUR_HEADING(FileType.TICKS),
    OUR_VELOCITY(FileType.TICKS),
    OUR_ENERGY(FileType.TICKS),
    GUN_HEAT(FileType.TICKS),
    GUN_HEADING(FileType.TICKS),
    RADAR_HEADING(FileType.TICKS),
    TICK(FileType.TICKS),
    BATTLEFIELD_WIDTH(FileType.TICKS),
    BATTLEFIELD_HEIGHT(FileType.TICKS),

    // --- Input: scan (set by onScannedRobot / pipeline) ---
    DISTANCE(FileType.TICKS),
    BEARING_RADIANS(FileType.TICKS),
    OPPONENT_HEADING(FileType.TICKS),
    OPPONENT_VELOCITY(FileType.TICKS),
    OPPONENT_ENERGY(FileType.TICKS),
    LAST_SCAN_TICK(FileType.TICKS),

    // --- Input: bullet & ram events (accumulated between scans) ---
    OUR_BULLET_DAMAGE_TO_OPPONENT(FileType.TICKS),
    OPPONENT_BULLET_ENERGY_GAIN(FileType.TICKS),
    RAM_DAMAGE_TO_OPPONENT(FileType.TICKS),

    // --- Computed: spatial ---
    OPPONENT_BEARING_ABSOLUTE(FileType.TICKS),

    // --- Computed: movement ---
    OPPONENT_LATERAL_VELOCITY(FileType.TICKS),
    OPPONENT_ADVANCING_VELOCITY(FileType.TICKS),

    // --- Computed: timing ---
    TICKS_SINCE_SCAN(FileType.TICKS),

    // --- Computed: fire detection ---
    OPPONENT_FIRE_POWER(FileType.WAVES),
    PREV_SCAN_OPPONENT_ENERGY(FileType.TICKS),

    // --- Round result ---
    ROUND_RESULT(FileType.SCORES);

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

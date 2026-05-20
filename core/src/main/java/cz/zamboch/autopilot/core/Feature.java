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
    OPPONENT_X(FileType.TICKS),
    OPPONENT_Y(FileType.TICKS),

    // --- Computed: movement ---
    OPPONENT_LATERAL_VELOCITY(FileType.TICKS),
    OPPONENT_ADVANCING_VELOCITY(FileType.TICKS),

    // --- Computed: timing ---
    TICKS_SINCE_SCAN(FileType.TICKS),

    // --- Computed: fire detection ---
    THEIR_FIRE_POWER(FileType.THEIR_WAVES),
    PREV_SCAN_OPPONENT_ENERGY(FileType.TICKS),

    // --- Computed: identity ---
    OPPONENT_ID(FileType.TICKS),
    OPPONENT_ID_HASH(FileType.TICKS),

    // --- Our gun waves: fire-time features (set when we fire) ---
    OUR_FIRE_DISTANCE(FileType.OUR_WAVES),
    OUR_FIRE_LATERAL_VELOCITY(FileType.OUR_WAVES),
    OUR_FIRE_ADVANCING_VELOCITY(FileType.OUR_WAVES),
    OUR_FIRE_BULLET_SPEED(FileType.OUR_WAVES),
    OUR_FIRE_MEA(FileType.OUR_WAVES),
    OUR_FIRE_DIRECTION(FileType.OUR_WAVES),
    OUR_FIRE_BEARING_ABSOLUTE(FileType.OUR_WAVES),
    OUR_FIRE_X(FileType.OUR_WAVES),
    OUR_FIRE_Y(FileType.OUR_WAVES),
    OUR_FIRE_OPPONENT_X(FileType.OUR_WAVES),
    OUR_FIRE_OPPONENT_Y(FileType.OUR_WAVES),
    OUR_FIRE_POWER(FileType.OUR_WAVES),
    OUR_FIRE_TICK(FileType.OUR_WAVES),

    // --- Our gun waves: break-time features (set at wave resolution) ---
    OUR_BREAK_TICK(FileType.OUR_WAVES),
    OUR_BREAK_GF(FileType.OUR_WAVES),
    OUR_BREAK_BEARING_OFFSET(FileType.OUR_WAVES),
    OUR_BREAK_OPPONENT_X(FileType.OUR_WAVES),
    OUR_BREAK_OPPONENT_Y(FileType.OUR_WAVES),
    OUR_BREAK_HIT(FileType.OUR_WAVES),

    // --- Round result ---
    ROUND_HIT_RATE(FileType.SCORES),
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

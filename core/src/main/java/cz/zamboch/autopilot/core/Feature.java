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

    // --- Input: scan (set by onScannedRobot / pipeline) ---
    DISTANCE(FileType.TICKS),
    BEARING_RADIANS(FileType.TICKS),
    OPPONENT_HEADING(FileType.TICKS),
    OPPONENT_VELOCITY(FileType.TICKS),
    OPPONENT_ENERGY(FileType.TICKS),
    LAST_SCAN_TICK(FileType.TICKS),

    // --- Input: bullet & ram events (accumulated between scans) ---
    // FileType.NONE: inter-tick accumulators consumed by FireFeatures; not dataset
    // features, so they are kept in the whiteboard but never written to CSV.
    OUR_BULLET_DAMAGE_TO_OPPONENT(FileType.NONE),
    OPPONENT_BULLET_ENERGY_GAIN(FileType.NONE),
    RAM_DAMAGE_TO_OPPONENT(FileType.NONE),
    OPPONENT_RAM_ENERGY_GAIN(FileType.NONE),
    OPPONENT_WALL_HIT_DAMAGE(FileType.NONE),

    // --- Computed: spatial ---
    OPPONENT_BEARING_ABSOLUTE(FileType.TICKS),
    OPPONENT_X(FileType.TICKS),
    OPPONENT_Y(FileType.TICKS),

    // --- Computed: movement ---
    OPPONENT_LATERAL_VELOCITY(FileType.TICKS),
    OPPONENT_ADVANCING_VELOCITY(FileType.TICKS),

    // --- Computed: gun aim (from GF strategy) ---
    // FileType.NONE: robot-side gun decision, not engine ground truth, so excluded
    // from the dataset (the god-view cannot reproduce it without a gun strategy).
    GUN_AIM_POWER(FileType.NONE),
    GUN_AIM_ANGLE(FileType.NONE),
    GUN_AIM_GF(FileType.NONE),

    // --- Computed: timing ---
    TICKS_SINCE_SCAN(FileType.TICKS),

    // --- Computed: fire detection ---
    THEIR_FIRE_POWER(FileType.THEIR_WAVES),
    THEIR_FIRE_TICK(FileType.THEIR_WAVES),
    THEIR_FIRE_X(FileType.THEIR_WAVES),
    THEIR_FIRE_Y(FileType.THEIR_WAVES),
    THEIR_BULLET_SPEED(FileType.THEIR_WAVES),
    THEIR_FIRE_BEARING(FileType.THEIR_WAVES),
    THEIR_FIRE_DISTANCE(FileType.THEIR_WAVES),
    THEIR_FIRE_OUR_X(FileType.THEIR_WAVES),
    THEIR_FIRE_OUR_Y(FileType.THEIR_WAVES),
    // Aim-time geometry: the tick BEFORE their fire tick (T-1), i.e. two ticks
    // before we detect the energy drop. That is when the opponent's gun was
    // actually aimed, so it attributes the aiming decision to the proper tick.
    THEIR_AIM_X(FileType.THEIR_WAVES),
    THEIR_AIM_Y(FileType.THEIR_WAVES),
    THEIR_AIM_OUR_X(FileType.THEIR_WAVES),
    THEIR_AIM_OUR_Y(FileType.THEIR_WAVES),
    THEIR_AIM_DISTANCE(FileType.THEIR_WAVES),
    THEIR_AIM_BEARING(FileType.THEIR_WAVES),
    THEIR_BREAK_TICK(FileType.THEIR_WAVES),
    THEIR_BREAK_OUR_X(FileType.THEIR_WAVES),
    THEIR_BREAK_OUR_Y(FileType.THEIR_WAVES),
    THEIR_BREAK_GF(FileType.THEIR_WAVES),
    THEIR_BREAK_BEARING_OFFSET(FileType.THEIR_WAVES),
    THEIR_HIT_US(FileType.THEIR_WAVES),
    // FileType.NONE: inter-tick state for fire detection; not a dataset feature.
    PREV_SCAN_OPPONENT_ENERGY(FileType.NONE),

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
    OUR_FIRE_BULLET_ID(FileType.OUR_WAVES),
    OUR_FIRE_AIM_GF(FileType.OUR_WAVES),
    OUR_FIRE_IS_REAL(FileType.OUR_WAVES),

    // --- Our gun waves: aim-time features (the tick BEFORE we fired, T-1) ---
    // The gun was aimed reacting to the world state one tick before the fire
    // command executed, so these attribute the aiming decision to that tick.
    OUR_AIM_X(FileType.OUR_WAVES),
    OUR_AIM_Y(FileType.OUR_WAVES),
    OUR_AIM_OPPONENT_X(FileType.OUR_WAVES),
    OUR_AIM_OPPONENT_Y(FileType.OUR_WAVES),
    OUR_AIM_DISTANCE(FileType.OUR_WAVES),
    OUR_AIM_BEARING_ABSOLUTE(FileType.OUR_WAVES),

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

    /**
     * Column index within this feature's table (TickRing, OurWaveTable, etc.).
     * Computed from declaration order within each FileType group.
     */
    public int columnIndex() {
        return COLUMN_INDICES[ordinal()];
    }

    private static final int[] COLUMN_INDICES;
    static {
        COLUMN_INDICES = new int[COUNT];
        int[] perType = new int[FileType.values().length];
        for (Feature f : values()) {
            COLUMN_INDICES[f.ordinal()] = perType[f.getFileType().ordinal()]++;
        }
    }
}

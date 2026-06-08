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

    // --- Scan rows: raw scan, derived scan geometry, and consumed energy ledger ---
    SCAN_TICK(FileType.SCAN),
    DISTANCE(FileType.SCAN),
    BEARING_RADIANS(FileType.SCAN),
    OPPONENT_HEADING(FileType.SCAN),
    OPPONENT_VELOCITY(FileType.SCAN),
    OPPONENT_ENERGY(FileType.SCAN),
    OPPONENT_ID(FileType.SCAN),
    OPPONENT_ID_HASH(FileType.SCAN),
    OPPONENT_BEARING_ABSOLUTE(FileType.SCAN),
    OPPONENT_X(FileType.SCAN),
    OPPONENT_Y(FileType.SCAN),
    OPPONENT_LATERAL_VELOCITY(FileType.SCAN),
    OPPONENT_ADVANCING_VELOCITY(FileType.SCAN),
    TICKS_SINCE_SCAN(FileType.SCAN),
    PREV_SCAN_OPPONENT_ENERGY(FileType.SCAN),
    OUR_BULLET_DAMAGE_TO_OPPONENT(FileType.SCAN),
    OPPONENT_BULLET_ENERGY_GAIN(FileType.SCAN),
    RAM_DAMAGE_TO_OPPONENT(FileType.SCAN),
    OPPONENT_WALL_HIT_DAMAGE(FileType.SCAN),
    THEIR_GUN_HEAT(FileType.SCAN),
    THEIR_INACTIVITY_ZAP_ACTIVE(FileType.SCAN),
    THEIR_ENERGY_DROP_ADJUSTED(FileType.SCAN),

    // --- Computed: gun aim (from GF strategy) ---
    // FileType.DECISIONS: robot-side gun decision, not engine ground truth, so excluded
    // from the dataset (the god-view cannot reproduce it without a gun strategy).
    GUN_AIM_POWER(FileType.DECISIONS),
    GUN_AIM_ANGLE(FileType.DECISIONS),
    GUN_AIM_GF(FileType.DECISIONS),

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
    // Lag-1 dodge context: the developing guess factor of the most-recent still
    // active real wave, evaluated against the opponent position at aim time. The
    // VcsStore bins this raw GF into a lag-1 slice; here it flows as a plain GF.
    OUR_AIM_LAG1_GF(FileType.OUR_WAVES),

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


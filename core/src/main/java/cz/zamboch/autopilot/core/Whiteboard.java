package cz.zamboch.autopilot.core;

import java.util.Arrays;

/**
 * Central state store for one robot's perspective during a battle.
 * <p>
 * Storage is structured into per-table arrays:
 * <ul>
 * <li>TickRing (depth=3): current + two previous ticks of features</li>
 * <li>Scan ring buffer: one row per radar scan event</li>
 * <li>OurWave ring buffer (capacity=64): pre-allocated wave lifecycle
 * storage</li>
 * <li>TheirWave staging: single row for pipeline fire detection</li>
 * <li>ScoreRow: per-round results</li>
 * </ul>
 * <p>
 * The public {@link #getFeature}/{@link #setFeature} API dispatches to the
 * appropriate table based on the feature's {@link FileType}.
 * <p>
 * The Transformer is a private implementation detail: registered
 * IInGameFeatures are executed in dependency order when {@link #process()} is
 * called.
 */
public final class Whiteboard {

    // --- Tick ring (depth=3: current + two previous ticks) ---
    // Depth >= 3 is required so two-ticks-ago values are reachable: incoming-fire
    // (their-wave) detection happens one tick after the fire tick, and the
    // aiming decision is one tick before the fire tick — i.e. two ticks before
    // detection. See getFeatureNTicksAgo.
    public static final int TICK_RING_DEPTH = 3;
    private final double[][] tickRing = new double[TICK_RING_DEPTH][TickColumn.COUNT];
    private int tickHead = 0;
    private long lastTick = Long.MIN_VALUE;

    private static final Feature[] DAMAGE_ACCUMULATOR_FEATURES = {
            Feature.OUR_BULLET_DAMAGE_TO_OPPONENT,
            Feature.OPPONENT_BULLET_ENERGY_GAIN,
            Feature.RAM_DAMAGE_TO_OPPONENT,
            Feature.OPPONENT_WALL_HIT_DAMAGE
    };

    // --- Scan ring: one row per ScannedRobotEvent ---
    public static final int SCAN_RING_CAPACITY = 64;
    private final double[][] scanRing = new double[SCAN_RING_CAPACITY][ScanColumn.COUNT];
    private final String[] scanOpponentIds = new String[SCAN_RING_CAPACITY];
    private int scanHead = -1;
    private int scanCount = 0;

    // Scan-window state accumulated between scan rows and copied into the scan row
    // after all scan consumers have had a chance to add/read it.
    private final double[] damageAccumulatorState = new double[Feature.COUNT];

    // --- Decision ring (depth=3): whiteboard-internal robot decision outputs. ---
    private final double[][] decisionRing = new double[TICK_RING_DEPTH][DecisionColumn.COUNT];

    // --- Our wave ring buffer ---
    public static final int OUR_WAVE_CAPACITY = 64;
    public static final byte WAVE_FREE = 0;
    public static final byte WAVE_ACTIVE = 1;
    public static final byte WAVE_RESOLVED = 2;

    private static final OurWaveColumn[] JUST_RESOLVED_BREAK_COLUMNS = {
            OurWaveColumn.BREAK_TICK, OurWaveColumn.BREAK_GF,
            OurWaveColumn.BREAK_BEARING_OFFSET, OurWaveColumn.BREAK_OPPONENT_X,
            OurWaveColumn.BREAK_OPPONENT_Y, OurWaveColumn.BREAK_HIT,
            OurWaveColumn.IS_REAL
    };

    private final double[][] ourWaves = new double[OUR_WAVE_CAPACITY][OurWaveColumn.COUNT];
    private final byte[] ourWaveState = new byte[OUR_WAVE_CAPACITY];
    private int ourWaveHead = 0;

    // --- Their wave ring buffer ---
    public static final int THEIR_WAVE_CAPACITY = 512;

    private final double[][] theirWaves = new double[THEIR_WAVE_CAPACITY][TheirWaveColumn.COUNT];
    private final byte[] theirWaveState = new byte[THEIR_WAVE_CAPACITY];
    private int theirWaveHead = 0;

    // --- OUR_WAVES staging (for pipeline backward compat via
    // getFeature/setFeature) ---
    private final double[] ourWaveStaging = new double[OurWaveColumn.COUNT];

    // --- Their wave staging ---
    private final double[] theirWaveStaging = new double[TheirWaveColumn.COUNT];

    // --- Score row ---
    private final double[] scoreRow = new double[ScoreColumn.COUNT];

    // --- String features (OPPONENT_ID) ---
    private final String[] stringFeatures = new String[Feature.COUNT];

    // --- Infrastructure ---
    private final Transformer transformer = new Transformer();
    private VcsStore vcsStore;
    private ModelSelector modelSelector;

    public Whiteboard() {
        clearFeatures();
    }

    public static Feature[] damageAccumulatorFeatures() {
        return Arrays.copyOf(DAMAGE_ACCUMULATOR_FEATURES, DAMAGE_ACCUMULATOR_FEATURES.length);
    }

    // ========== Public Feature API (backward compatible) ==========

    /** Register feature processors. Call before first process(). */
    public void registerFeatures(IInGameFeatures... featureSets) {
        for (IInGameFeatures f : featureSets) {
            transformer.register(f);
        }
        transformer.resolveDependencies();
    }

    /** Execute all registered feature processors in dependency order. */
    public void process() {
        transformer.process(this);
    }

    /** Set a feature value. Throws if value is infinite. */
    public void setFeature(Feature f, double value) {
        if (Double.isInfinite(value)) {
            throw new IllegalArgumentException(
                    "Infinite value for feature " + f.name());
        }
        int col = f.columnIndex();
        switch (f.getFileType()) {
            case TICKS:
                if (f == Feature.TICK && !Double.isNaN(value)) {
                    long newTick = (long) value;
                    if (newTick != lastTick && lastTick != Long.MIN_VALUE) {
                        rotateTickRing();
                    }
                    lastTick = newTick;
                }
                tickRing[tickHead][col] = value;
                break;
            case SCAN:
                if (isDamageAccumulator(f)) {
                    damageAccumulatorState[f.ordinal()] = value;
                } else {
                    ensureCurrentScanRow();
                    scanRing[scanHead][col] = value;
                }
                break;
            case DECISIONS:
                decisionRing[tickHead][col] = value;
                break;
            case OUR_WAVES:
                ourWaveStaging[col] = value;
                break;
            case THEIR_WAVES:
                theirWaveStaging[col] = value;
                break;
            case SCORES:
                scoreRow[col] = value;
                break;
        }
    }

    private void rotateTickRing() {
        tickHead = nextRingIndex(tickHead, TICK_RING_DEPTH);
        Arrays.fill(tickRing[tickHead], Double.NaN);
        Arrays.fill(decisionRing[tickHead], Double.NaN);
    }

    /** Get a feature value. Returns NaN if not yet set. */
    public double getFeature(Feature f) {
        int col = f.columnIndex();
        switch (f.getFileType()) {
            case TICKS:
                return tickRing[tickHead][col];
            case SCAN:
                if (isDamageAccumulator(f)) {
                    double state = damageAccumulatorState[f.ordinal()];
                    if (!Double.isNaN(state)) {
                        return state;
                    }
                }
                if (!hasCurrentScan()) {
                    return Double.NaN;
                }
                return scanRing[scanHead][col];
            case DECISIONS:
                return decisionRing[tickHead][col];
            case OUR_WAVES:
                return ourWaveStaging[col];
            case THEIR_WAVES:
                return theirWaveStaging[col];
            case SCORES:
                return scoreRow[col];
            default:
                return Double.NaN;
        }
    }

    /** Get a tick feature's value from the previous tick. */
    public double getPreviousTickFeature(Feature f) {
        return getFeatureNTicksAgo(f, 1);
    }

    /**
     * Get a tick feature's value from {@code n} ticks ago (0 = current tick,
     * 1 = previous tick, 2 = two ticks ago). {@code n} must be in
     * {@code [0, TICK_RING_DEPTH - 1]}. Works for {@link FileType#TICKS} and
     * {@link FileType#DECISIONS} features (both share the tick ring rotation). For
     * {@link FileType#SCAN}, returns the previous scan row.
     */
    public double getFeatureNTicksAgo(Feature f, int n) {
        FileType ft = f.getFileType();
        if (ft == FileType.SCAN) {
            if (n != 1) {
                throw new IllegalArgumentException("Only previous scan row is supported for scan features: " + n);
            }
            return getPreviousScanFeature(f);
        }
        if (ft != FileType.TICKS && ft != FileType.DECISIONS) {
            throw new IllegalArgumentException("Not a tick-ring feature: " + f.name());
        }
        if (n < 0 || n >= TICK_RING_DEPTH) {
            throw new IllegalArgumentException(
                    "n out of range [0, " + (TICK_RING_DEPTH - 1) + "]: " + n);
        }
        int idx = ringIndex(tickHead, n, TICK_RING_DEPTH);
        double[][] ring = (ft == FileType.DECISIONS) ? decisionRing : tickRing;
        return ring[idx][f.columnIndex()];
    }

    /** Reset all features to NaN. Typically called at round start. */
    public void clearFeatures() {
        clearRows(tickRing);
        clearRows(decisionRing);
        clearRows(scanRing);
        for (int i = 0; i < scanOpponentIds.length; i++) {
            scanOpponentIds[i] = null;
        }
        scanHead = -1;
        scanCount = 0;
        Arrays.fill(damageAccumulatorState, Double.NaN);
        Arrays.fill(ourWaveStaging, Double.NaN);
        Arrays.fill(theirWaveStaging, Double.NaN);
        Arrays.fill(scoreRow, Double.NaN);
        clearRows(ourWaves);
        Arrays.fill(ourWaveState, WAVE_FREE);
        ourWaveHead = 0;
        clearRows(theirWaves);
        Arrays.fill(theirWaveState, WAVE_FREE);
        theirWaveHead = 0;
        Arrays.fill(stringFeatures, null);
        tickHead = 0;
        lastTick = Long.MIN_VALUE;
    }

    /** Set a string feature value. */
    public void setStringFeature(Feature f, String value) {
        if (f.getFileType() == FileType.SCAN) {
            ensureCurrentScanRow();
            if (f == Feature.OPPONENT_ID) {
                scanOpponentIds[scanHead] = value;
            }
            return;
        }
        stringFeatures[f.ordinal()] = value;
    }

    /** Get a string feature value. Returns null if not set. */
    public String getStringFeature(Feature f) {
        if (f.getFileType() == FileType.SCAN) {
            if (scanHead < 0 || f != Feature.OPPONENT_ID) {
                return null;
            }
            return scanOpponentIds[scanHead];
        }
        return stringFeatures[f.ordinal()];
    }

    // ========== Scan Ring Buffer API ==========

    /** Allocate and initialize a scan row for one ScannedRobotEvent. */
    public void beginScanRow(double scanTick) {
        scanHead = nextRingIndex(scanHead, SCAN_RING_CAPACITY);
        scanCount = Math.min(scanCount + 1, SCAN_RING_CAPACITY);
        Arrays.fill(scanRing[scanHead], Double.NaN);
        scanOpponentIds[scanHead] = null;
        scanRing[scanHead][Feature.SCAN_TICK.columnIndex()] = scanTick;
    }

    /** True when the latest scan row belongs to the current tick. */
    public boolean hasCurrentScan() {
        if (scanHead < 0) {
            return false;
        }
        double tick = tickRing[tickHead][Feature.TICK.columnIndex()];
        double scanTick = scanRing[scanHead][Feature.SCAN_TICK.columnIndex()];
        return !Double.isNaN(tick) && !Double.isNaN(scanTick) && tick == scanTick;
    }

    public void setCurrentScanFeature(Feature f, double value) {
        ensureScanFeature(f);
        ensureCurrentScanRow();
        scanRing[scanHead][f.columnIndex()] = value;
    }

    public double getLatestScanFeature(Feature f) {
        ensureScanFeature(f);
        if (scanHead < 0) {
            return Double.NaN;
        }
        return scanRing[scanHead][f.columnIndex()];
    }

    public double getPreviousScanFeature(Feature f) {
        ensureScanFeature(f);
        if (scanCount < 2) {
            return Double.NaN;
        }
        int idx = ringIndex(scanHead, 1, SCAN_RING_CAPACITY);
        return scanRing[idx][f.columnIndex()];
    }

    public double getScanFeatureAtOrBeforeTick(Feature f, double targetTick) {
        ensureScanFeature(f);
        if (Double.isNaN(targetTick)) {
            return Double.NaN;
        }
        for (int n = 0; n < scanCount; n++) {
            int idx = ringIndex(scanHead, n, SCAN_RING_CAPACITY);
            double scanTick = scanRing[idx][Feature.SCAN_TICK.columnIndex()];
            if (!Double.isNaN(scanTick) && scanTick <= targetTick) {
                return scanRing[idx][f.columnIndex()];
            }
        }
        return Double.NaN;
    }

    private void ensureCurrentScanRow() {
        if (hasCurrentScan()) {
            return;
        }
        beginScanRow(tickRing[tickHead][Feature.TICK.columnIndex()]);
    }

    private static void ensureScanFeature(Feature f) {
        if (f.getFileType() != FileType.SCAN) {
            throw new IllegalArgumentException("Not a scan feature: " + f.name());
        }
    }

    public void copyDamageAccumulatorsToCurrentScanRow() {
        ensureCurrentScanRow();
        for (Feature f : DAMAGE_ACCUMULATOR_FEATURES) {
            scanRing[scanHead][f.columnIndex()] = damageAccumulatorState[f.ordinal()];
        }
    }

    public void clearDamageAccumulatorFeatures() {
        for (Feature f : DAMAGE_ACCUMULATOR_FEATURES) {
            damageAccumulatorState[f.ordinal()] = Double.NaN;
        }
    }

    private static boolean isDamageAccumulator(Feature f) {
        for (Feature accumulator : DAMAGE_ACCUMULATOR_FEATURES) {
            if (accumulator == f) {
                return true;
            }
        }
        return false;
    }

    private static int nextRingIndex(int head, int capacity) {
        return (head + 1) % capacity;
    }

    private static int ringIndex(int head, int offset, int capacity) {
        return Math.floorMod(head - offset, capacity);
    }

    private static void clearRows(double[][] rows) {
        for (double[] row : rows) {
            Arrays.fill(row, Double.NaN);
        }
    }

    private static int countState(byte[] states, byte state) {
        int count = 0;
        for (byte value : states) {
            if (value == state) {
                count++;
            }
        }
        return count;
    }

    private static void clearWaveSlot(double[][] waves, byte[] states, int slot, int zeroColumn) {
        Arrays.fill(waves[slot], Double.NaN);
        waves[slot][zeroColumn] = 0;
        states[slot] = WAVE_FREE;
    }

    // ========== VCS ==========

    /** Get the VCS store (may be null before first load). */
    public VcsStore getVcsStore() {
        return vcsStore;
    }

    /** Set the VCS store (loaded from persistence or newly created). */
    public void setVcsStore(VcsStore store) {
        this.vcsStore = store;
    }

    // ========== Model Selector ==========

    /** Get the model selector (may be null before setup). */
    public ModelSelector getModelSelector() {
        return modelSelector;
    }

    /** Set the model selector. */
    public void setModelSelector(ModelSelector selector) {
        this.modelSelector = selector;
    }

    // ========== Our Wave Ring Buffer API ==========

    /**
     * Allocate a new slot in the our-wave ring buffer.
     * Clears the slot and sets BREAK_HIT to 0.
     *
     * @return the allocated slot index
     * @throws IllegalStateException if the slot to overwrite is still ACTIVE
     */
    public int allocateOurWave() {
        int slot = ourWaveHead;
        if (ourWaveState[slot] == WAVE_ACTIVE) {
            throw new IllegalStateException(
                    "Our wave ring buffer overflow at slot " + slot);
        }
        clearWaveSlot(ourWaves, ourWaveState, slot, OurWaveColumn.BREAK_HIT.ordinal());
        ourWaveHead = nextRingIndex(ourWaveHead, OUR_WAVE_CAPACITY);
        return slot;
    }

    /** Set a column value in a specific our-wave slot. */
    public void setOurWave(int slot, OurWaveColumn col, double value) {
        ourWaves[slot][col.ordinal()] = value;
    }

    /** Get a column value from a specific our-wave slot. */
    public double getOurWave(int slot, OurWaveColumn col) {
        return ourWaves[slot][col.ordinal()];
    }

    /** Get the state of an our-wave slot (FREE, ACTIVE, RESOLVED). */
    public byte getOurWaveState(int slot) {
        return ourWaveState[slot];
    }

    /** Set the state of an our-wave slot. */
    public void setOurWaveState(int slot, byte state) {
        ourWaveState[slot] = state;
    }

    /**
     * Mark a bullet ID as having hit the opponent.
     * Searches active wave slots in the ring buffer.
     */
    public void markBulletHit(int bulletId) {
        if (bulletId == 0)
            return;
        for (int i = 0; i < OUR_WAVE_CAPACITY; i++) {
            if (ourWaveState[i] == WAVE_ACTIVE
                    && (int) ourWaves[i][OurWaveColumn.FIRE_BULLET_ID.ordinal()] == bulletId) {
                ourWaves[i][OurWaveColumn.BREAK_HIT.ordinal()] = 1.0;
                return;
            }
        }
    }

    /** Count the number of active (in-flight) wave slots. */
    public int getActiveWaveCount() {
        return countState(ourWaveState, WAVE_ACTIVE);
    }

    /**
     * Emit every ALIVE (in-flight) our-wave as a set of debug properties. Each
     * wave column is published under the key {@code COLUMN_NAME/waveId}; the value
     * is the numeric column value, or {@code "NaN"}. The {@code WAVE_ID} column is
     * itself omitted (it is encoded in the key). Used by Layer 0 fidelity
     * validation to compare the full set of in-flight waves between the live robot
     * and the observer shadow, matched by stable wave id.
     */
    public void forEachAliveWaveProperty(java.util.function.BiConsumer<String, String> sink) {
        for (int i = 0; i < OUR_WAVE_CAPACITY; i++) {
            if (ourWaveState[i] != WAVE_ACTIVE) {
                continue;
            }
            long waveId = (long) ourWaves[i][OurWaveColumn.WAVE_ID.ordinal()];
            for (OurWaveColumn c : OurWaveColumn.values()) {
                if (c == OurWaveColumn.WAVE_ID) {
                    continue;
                }
                double v = ourWaves[i][c.ordinal()];
                sink.accept(c.name() + "/" + waveId, Double.isNaN(v) ? "NaN" : String.valueOf(v));
            }
        }
    }

    /**
     * Emit the BREAK_* columns of any wave that RESOLVED on the current tick
     * (BREAK_TICK == TICK), keyed {@code RES_COLUMN/waveId}. Lets Layer 0 compare the
     * resolving-tick break geometry between live and observer by stable wave id, which
     * is invisible to {@link #forEachAliveWaveProperty} because a resolved wave has
     * already left the alive set when validation runs. This is the only validation of
     * the virtual waves' break geometry, so it is a permanent part of the fidelity check.
     */
    public void forEachJustResolvedWaveBreak(java.util.function.BiConsumer<String, String> sink) {
        double tick = tickRing[tickHead][Feature.TICK.columnIndex()];
        if (Double.isNaN(tick)) {
            return;
        }
        for (int i = 0; i < OUR_WAVE_CAPACITY; i++) {
            if (ourWaveState[i] != WAVE_RESOLVED) {
                continue;
            }
            double breakTick = ourWaves[i][OurWaveColumn.BREAK_TICK.ordinal()];
            if (Double.isNaN(breakTick) || Math.abs(breakTick - tick) > 1e-4) {
                continue;
            }
            long waveId = (long) ourWaves[i][OurWaveColumn.WAVE_ID.ordinal()];
            for (OurWaveColumn c : JUST_RESOLVED_BREAK_COLUMNS) {
                double v = ourWaves[i][c.ordinal()];
                sink.accept("RES_" + c.name() + "/" + waveId, Double.isNaN(v) ? "NaN" : String.valueOf(v));
            }
        }
    }

    // ========== Their Wave Ring Buffer API ==========

    /**
     * Allocate a new slot in the their-wave ring buffer.
     * Clears the slot and sets HIT_US to 0.
     *
     * @return the allocated slot index
     * @throws IllegalStateException if the slot to overwrite is still ACTIVE
     */
    public int allocateTheirWave() {
        int slot = theirWaveHead;
        if (theirWaveState[slot] == WAVE_ACTIVE) {
            throw new IllegalStateException(
                    "Their wave ring buffer overflow at slot " + slot);
        }
        clearWaveSlot(theirWaves, theirWaveState, slot, TheirWaveColumn.HIT_US.ordinal());
        theirWaveHead = nextRingIndex(theirWaveHead, THEIR_WAVE_CAPACITY);
        return slot;
    }

    /** Set a column value in a specific their-wave slot. */
    public void setTheirWave(int slot, TheirWaveColumn col, double value) {
        theirWaves[slot][col.ordinal()] = value;
    }

    /** Get a column value from a specific their-wave slot. */
    public double getTheirWave(int slot, TheirWaveColumn col) {
        return theirWaves[slot][col.ordinal()];
    }

    /** Get the state of a their-wave slot (FREE, ACTIVE, RESOLVED). */
    public byte getTheirWaveState(int slot) {
        return theirWaveState[slot];
    }

    /** Set the state of a their-wave slot. */
    public void setTheirWaveState(int slot, byte state) {
        theirWaveState[slot] = state;
    }

    /** Count the number of active (in-flight) their-wave slots. */
    public int getActiveTheirWaveCount() {
        return countState(theirWaveState, WAVE_ACTIVE);
    }

}

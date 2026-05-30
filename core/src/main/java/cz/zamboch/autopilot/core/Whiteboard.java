package cz.zamboch.autopilot.core;

import java.util.Arrays;

/**
 * Central state store for one robot's perspective during a battle.
 * <p>
 * Storage is structured into per-table arrays:
 * <ul>
 * <li>TickRing (depth=3): current + two previous ticks of features</li>
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

    // --- None ring (depth=3): whiteboard-internal features (FileType.NONE).
    // Robot-side decision outputs + inter-tick accumulators. Shares the tick
    // ring's head/rotation so it carries the same per-tick / N-ticks-ago
    // semantics, but is never written to any CSV. ---
    private final double[][] noneRing = new double[TICK_RING_DEPTH][NoneColumn.COUNT];

    // --- Our wave ring buffer ---
    public static final int OUR_WAVE_CAPACITY = 64;
    public static final byte WAVE_FREE = 0;
    public static final byte WAVE_ACTIVE = 1;
    public static final byte WAVE_RESOLVED = 2;

    private final double[][] ourWaves = new double[OUR_WAVE_CAPACITY][OurWaveColumn.COUNT];
    private final byte[] ourWaveState = new byte[OUR_WAVE_CAPACITY];
    private int ourWaveHead = 0;

    // --- Their wave ring buffer ---
    public static final int THEIR_WAVE_CAPACITY = 32;

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
                        tickHead = (tickHead + 1) % TICK_RING_DEPTH;
                        // Clear new slot — will be overwritten this tick
                        Arrays.fill(tickRing[tickHead], Double.NaN);
                        Arrays.fill(noneRing[tickHead], Double.NaN);
                    }
                    lastTick = newTick;
                }
                tickRing[tickHead][col] = value;
                break;
            case NONE:
                noneRing[tickHead][col] = value;
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

    /** Get a feature value. Returns NaN if not yet set. */
    public double getFeature(Feature f) {
        int col = f.columnIndex();
        switch (f.getFileType()) {
            case TICKS:
                return tickRing[tickHead][col];
            case NONE:
                return noneRing[tickHead][col];
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
     * {@link FileType#NONE} features (both share the tick ring rotation).
     */
    public double getFeatureNTicksAgo(Feature f, int n) {
        FileType ft = f.getFileType();
        if (ft != FileType.TICKS && ft != FileType.NONE) {
            throw new IllegalArgumentException("Not a tick-ring feature: " + f.name());
        }
        if (n < 0 || n >= TICK_RING_DEPTH) {
            throw new IllegalArgumentException(
                    "n out of range [0, " + (TICK_RING_DEPTH - 1) + "]: " + n);
        }
        int idx = ((tickHead - n) % TICK_RING_DEPTH + TICK_RING_DEPTH) % TICK_RING_DEPTH;
        double[][] ring = (ft == FileType.NONE) ? noneRing : tickRing;
        return ring[idx][f.columnIndex()];
    }

    /**
     * Get a tick feature's most recent KNOWN (non-NaN) value at or before
     * {@code startN} ticks ago, walking deeper into the ring until a value is
     * found. Returns NaN only if no slot in {@code [startN, TICK_RING_DEPTH)}
     * holds a value.
     * <p>
     * Used for aim-time opponent geometry: the gun aims at the opponent's most
     * recently scanned position, which may be a few ticks stale when the opponent
     * was not freshly scanned on the exact aim tick (radar-lock gap). Using the
     * raw aim-tick value would yield NaN on those ticks.
     */
    public double getLastKnownFeatureNTicksAgo(Feature f, int startN) {
        for (int n = startN; n < TICK_RING_DEPTH; n++) {
            double v = getFeatureNTicksAgo(f, n);
            if (!Double.isNaN(v)) {
                return v;
            }
        }
        return Double.NaN;
    }

    /** Reset all features to NaN. Typically called at round start. */
    public void clearFeatures() {
        for (int i = 0; i < TICK_RING_DEPTH; i++) {
            Arrays.fill(tickRing[i], Double.NaN);
            Arrays.fill(noneRing[i], Double.NaN);
        }
        Arrays.fill(ourWaveStaging, Double.NaN);
        Arrays.fill(theirWaveStaging, Double.NaN);
        Arrays.fill(scoreRow, Double.NaN);
        for (int i = 0; i < OUR_WAVE_CAPACITY; i++) {
            Arrays.fill(ourWaves[i], Double.NaN);
            ourWaveState[i] = WAVE_FREE;
        }
        ourWaveHead = 0;
        for (int i = 0; i < THEIR_WAVE_CAPACITY; i++) {
            Arrays.fill(theirWaves[i], Double.NaN);
            theirWaveState[i] = WAVE_FREE;
        }
        theirWaveHead = 0;
        Arrays.fill(stringFeatures, null);
        tickHead = 0;
        lastTick = Long.MIN_VALUE;
    }

    /** Set a string feature value. */
    public void setStringFeature(Feature f, String value) {
        stringFeatures[f.ordinal()] = value;
    }

    /** Get a string feature value. Returns null if not set. */
    public String getStringFeature(Feature f) {
        return stringFeatures[f.ordinal()];
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
        Arrays.fill(ourWaves[slot], Double.NaN);
        ourWaves[slot][OurWaveColumn.BREAK_HIT.ordinal()] = 0;
        ourWaveState[slot] = WAVE_FREE;
        ourWaveHead = (ourWaveHead + 1) % OUR_WAVE_CAPACITY;
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
        int count = 0;
        for (int i = 0; i < OUR_WAVE_CAPACITY; i++) {
            if (ourWaveState[i] == WAVE_ACTIVE)
                count++;
        }
        return count;
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
        double tick = getFeature(Feature.TICK);
        if (Double.isNaN(tick)) {
            return;
        }
        OurWaveColumn[] breakCols = {
                OurWaveColumn.BREAK_TICK, OurWaveColumn.BREAK_GF,
                OurWaveColumn.BREAK_BEARING_OFFSET, OurWaveColumn.BREAK_OPPONENT_X,
                OurWaveColumn.BREAK_OPPONENT_Y, OurWaveColumn.BREAK_HIT,
                OurWaveColumn.IS_REAL
        };
        for (int i = 0; i < OUR_WAVE_CAPACITY; i++) {
            if (ourWaveState[i] != WAVE_RESOLVED) {
                continue;
            }
            double breakTick = ourWaves[i][OurWaveColumn.BREAK_TICK.ordinal()];
            if (Double.isNaN(breakTick) || Math.abs(breakTick - tick) > 1e-4) {
                continue;
            }
            long waveId = (long) ourWaves[i][OurWaveColumn.WAVE_ID.ordinal()];
            for (OurWaveColumn c : breakCols) {
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
        Arrays.fill(theirWaves[slot], Double.NaN);
        theirWaves[slot][TheirWaveColumn.HIT_US.ordinal()] = 0;
        theirWaveState[slot] = WAVE_FREE;
        theirWaveHead = (theirWaveHead + 1) % THEIR_WAVE_CAPACITY;
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
        int count = 0;
        for (int i = 0; i < THEIR_WAVE_CAPACITY; i++) {
            if (theirWaveState[i] == WAVE_ACTIVE)
                count++;
        }
        return count;
    }

    /**
     * Mark that the opponent's bullet hit us.
     * Finds the oldest active their-wave matching the given power.
     */
    public void markTheirBulletHitUs(double power) {
        for (int i = 0; i < THEIR_WAVE_CAPACITY; i++) {
            if (theirWaveState[i] == WAVE_ACTIVE
                    && Math.abs(theirWaves[i][TheirWaveColumn.FIRE_POWER.ordinal()] - power) < 0.001) {
                theirWaves[i][TheirWaveColumn.HIT_US.ordinal()] = 1.0;
                return;
            }
        }
    }
}

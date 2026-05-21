package cz.zamboch.autopilot.core;

import java.util.Arrays;

/**
 * Central state store for one robot's perspective during a battle.
 * <p>
 * Storage is structured into per-table arrays:
 * <ul>
 * <li>TickRing (depth=2): current + previous tick features</li>
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

    // --- Tick ring (depth=2: current + previous) ---
    private final double[][] tickRing = new double[2][TickColumn.COUNT];
    private int tickHead = 0;
    private long lastTick = Long.MIN_VALUE;

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
                        tickHead = 1 - tickHead;
                        // Clear new slot — will be overwritten this tick
                        Arrays.fill(tickRing[tickHead], Double.NaN);
                    }
                    lastTick = newTick;
                }
                tickRing[tickHead][col] = value;
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
        if (f.getFileType() != FileType.TICKS) {
            throw new IllegalArgumentException("Not a tick feature: " + f.name());
        }
        return tickRing[1 - tickHead][f.columnIndex()];
    }

    /** Reset all features to NaN. Typically called at round start. */
    public void clearFeatures() {
        Arrays.fill(tickRing[0], Double.NaN);
        Arrays.fill(tickRing[1], Double.NaN);
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

package cz.zamboch.autopilot.core.ml;

import cz.zamboch.autopilot.core.persistence.IPersistable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Adaptive tree budget for GBM inference. Tracks per-tick execution time
 * and dynamically adjusts how many trees each predictor may evaluate.
 *
 * <p>On skipped turns, the budget is halved. On successful ticks, it
 * slowly recovers (5% per tick). The ceiling converges to the highest
 * sustainable tree count for this machine.</p>
 *
 * <p>Persists the ceiling across battles so we don't re-discover
 * the CPU limit every time.</p>
 */
public final class TickBudget implements IPersistable {

    public static final int SECTION_ID = 3;

    /** Maximum trees per predictor (full model). */
    private final int maxTrees;

    /** Current budget per predictor. */
    private volatile int currentBudget;

    /** The ceiling we recover toward. Lowered on skip, never exceeds maxTrees. */
    private int ceiling;

    /** Nano time at tick start. */
    private long tickStartNanos;

    /** Last measured tick duration in microseconds. */
    private long lastTickMicros;

    public TickBudget(int maxTrees) {
        this.maxTrees = maxTrees;
        this.currentBudget = maxTrees;
        this.ceiling = maxTrees;
    }

    /** Call at the start of each tick (onStatus). */
    public void tickStart() {
        tickStartNanos = System.nanoTime();
    }

    /** Call at the end of each tick (after execute()). Records elapsed time. */
    public void tickEnd() {
        lastTickMicros = (System.nanoTime() - tickStartNanos) / 1000;

        // Slowly recover toward ceiling (not maxTrees)
        if (currentBudget < ceiling) {
            currentBudget = Math.min(ceiling, currentBudget + Math.max(1, ceiling / 20));
        }
    }

    /** Call when a turn is skipped. Halves both budget and ceiling. */
    public void onSkippedTurn() {
        // The ceiling was too high — lower it
        ceiling = Math.max(10, currentBudget - 1);
        currentBudget = Math.max(10, currentBudget / 2);
    }

    /** Get the current tree budget per predictor. */
    public int getBudget() {
        return currentBudget;
    }

    /** Last measured tick duration in microseconds. */
    public long getLastTickMicros() {
        return lastTickMicros;
    }

    // === IPersistable — save/restore ceiling across battles ===

    @Override
    public int getSectionId() { return SECTION_ID; }

    @Override
    public void writeTo(DataOutputStream out) throws IOException {
        out.writeInt(ceiling);
    }

    @Override
    public void readFrom(DataInputStream in, int length) throws IOException {
        int saved = in.readInt();
        if (saved >= 10 && saved <= maxTrees) {
            ceiling = saved;
            currentBudget = saved;
        }
    }
}

package cz.zamboch.autopilot.core.ml;

import cz.zamboch.autopilot.core.persistence.IPersistable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Adaptive tree budget for GBM inference. Tracks per-tick execution time
 * and dynamically adjusts how many trees each predictor may evaluate.
 *
 * <p>On skipped turns (outside startup), the budget is halved. On successful
 * ticks, it slowly recovers toward the ceiling. The ceiling itself recovers
 * upward (+1 every 200 skip-free ticks) so the system self-heals after
 * transient slowdowns.</p>
 *
 * <p>Skips during round 0, tick &lt; 10 are ignored — these are caused by
 * class loading and Base64 decode, not by model inference cost.</p>
 *
 * <p>Persists the ceiling across battles.</p>
 */
public final class TickBudget implements IPersistable {

    public static final int SECTION_ID = 3;

    /** Maximum trees per predictor (full model). */
    private final int maxTrees;

    /** Current budget per predictor. */
    private volatile int currentBudget;

    /** The ceiling we recover toward. Can recover upward over time. */
    private int ceiling;

    /** Nano time at tick start. */
    private long tickStartNanos;

    /** Last measured tick duration in microseconds. */
    private long lastTickMicros;

    /** Ticks since last skip — drives upward ceiling recovery. */
    private int ticksSinceSkip;

    /** Upward recovery interval: try +1 ceiling after this many skip-free ticks. */
    private static final int RECOVERY_INTERVAL = 200;

    /** Minimum floor for budget and ceiling. */
    private static final int MIN_BUDGET = 10;

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

        // Recover currentBudget toward ceiling
        if (currentBudget < ceiling) {
            currentBudget = Math.min(ceiling, currentBudget + Math.max(1, ceiling / 20));
        }

        // Upward ceiling recovery: if no skips for RECOVERY_INTERVAL ticks,
        // nudge ceiling up by 1. This self-heals after transient slowdowns.
        ticksSinceSkip++;
        if (ticksSinceSkip >= RECOVERY_INTERVAL && ceiling < maxTrees) {
            ceiling = Math.min(maxTrees, ceiling + 1);
            ticksSinceSkip = 0;
        }
    }

    /**
     * Call when a turn is skipped. Halves both budget and ceiling.
     * @param round current round number (0-based)
     * @param tick current tick within the round
     */
    public void onSkippedTurn(int round, long tick) {
        // Ignore skips during startup (round 0, first 10 ticks) —
        // these are caused by class loading / Base64 decode, not model cost.
        if (round == 0 && tick < 10) {
            return;
        }
        // The ceiling was too high — lower it
        ceiling = Math.max(MIN_BUDGET, currentBudget - 1);
        currentBudget = Math.max(MIN_BUDGET, currentBudget / 2);
        ticksSinceSkip = 0;
    }

    /** @deprecated Use {@link #onSkippedTurn(int, long)} instead. */
    public void onSkippedTurn() {
        onSkippedTurn(1, 999); // legacy: treat as non-startup skip
    }

    /** Get the current tree budget per predictor. */
    public int getBudget() {
        return currentBudget;
    }

    /** Get the ceiling (max sustainable tree count discovered so far). */
    public int getCeiling() {
        return ceiling;
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

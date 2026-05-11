package cz.zamboch.autopilot.core.movement;

import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.persistence.IPersistable;
import cz.zamboch.autopilot.core.strategy.IMovementStrategy;
import cz.zamboch.autopilot.core.strategy.MovementCommand;
import cz.zamboch.autopilot.core.strategy.StrategyParams;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Tracks multiple movement strategies and selects the best performer
 * via round-level damage comparison. Persists across rounds and battles.
 */
public final class MovementStrategyManager implements IPersistable {

    public static final int SECTION_ID = 2;

    private static final double EMA_ALPHA = 0.3;

    private final List<IMovementStrategy> strategies;
    private final double[] avgDamage;
    private final boolean[] initialized;
    private final MovementCommand sharedCommand = new MovementCommand();
    private int activeIndex;
    private int roundsPlayed;

    public MovementStrategyManager(List<IMovementStrategy> strategies) {
        this.strategies = strategies;
        this.avgDamage = new double[strategies.size()];
        this.initialized = new boolean[strategies.size()];
        this.activeIndex = 0;
    }

    /** Get the command from the currently active strategy. Zero allocation. */
    public MovementCommand getActiveCommand(Whiteboard wb, StrategyParams params) {
        strategies.get(activeIndex).getCommand(wb, params, sharedCommand);
        return sharedCommand;
    }

    /**
     * Called at round end. Records damage taken by the active strategy
     * and selects the best one for the next round.
     */
    public void onRoundEnd(Whiteboard wb) {
        // Update EMA for the active strategy
        double dmg = wb.getDamageReceivedThisRound();
        if (!initialized[activeIndex]) {
            avgDamage[activeIndex] = dmg;
            initialized[activeIndex] = true;
        } else {
            avgDamage[activeIndex] = EMA_ALPHA * dmg + (1.0 - EMA_ALPHA) * avgDamage[activeIndex];
        }
        roundsPlayed++;

        if (roundsPlayed <= strategies.size()) {
            // First pass: rotate through all strategies
            activeIndex = roundsPlayed % strategies.size();
        } else {
            // After trying all, pick the one with lowest average damage
            double bestDamage = Double.MAX_VALUE;
            int bestIdx = 0;
            for (int i = 0; i < strategies.size(); i++) {
                if (initialized[i] && avgDamage[i] < bestDamage) {
                    bestDamage = avgDamage[i];
                    bestIdx = i;
                }
            }
            activeIndex = bestIdx;
        }
    }

    /** Reset for a new round. Active strategy selection persists across rounds. */
    public void onRoundStart() {
        // Active strategy selection and damage stats persist
    }

    // === Debug accessors ===

    /** Index of the currently active movement strategy. */
    public int getActiveIndex() { return activeIndex; }

    /** Name of the currently active movement strategy. */
    public String getActiveName() {
        return strategies.get(activeIndex).getName();
    }

    /** Rounds of data collected so far. */
    public int getRoundsPlayed() { return roundsPlayed; }

    /** EMA average damage for strategy at index i (NaN if not initialized). */
    public double getAvgDamage(int i) {
        return initialized[i] ? avgDamage[i] : Double.NaN;
    }

    /** Number of registered strategies. */
    public int getStrategyCount() { return strategies.size(); }

    /** Name of strategy at index i. */
    public String getStrategyName(int i) { return strategies.get(i).getName(); }

    // === IPersistable (cross-battle persistence) ===

    @Override
    public int getSectionId() { return SECTION_ID; }

    @Override
    public void writeTo(DataOutputStream out) throws IOException {
        int n = strategies.size();
        out.writeInt(n);
        out.writeInt(activeIndex);
        out.writeInt(roundsPlayed);
        for (int i = 0; i < n; i++) {
            out.writeDouble(avgDamage[i]);
            out.writeBoolean(initialized[i]);
        }
    }

    @Override
    public void readFrom(DataInputStream in, int length) throws IOException {
        int n = in.readInt();
        activeIndex = in.readInt();
        roundsPlayed = in.readInt();
        int count = Math.min(n, strategies.size());
        for (int i = 0; i < count; i++) {
            avgDamage[i] = in.readDouble();
            initialized[i] = in.readBoolean();
        }
        for (int i = count; i < n; i++) {
            in.readDouble(); // skip extra
            in.readBoolean();
        }
    }
}

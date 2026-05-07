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

    private final List<IMovementStrategy> strategies;
    private final double[] damagePerRound;
    private final MovementCommand sharedCommand = new MovementCommand();
    private int activeIndex;
    private int roundsPlayed;

    public MovementStrategyManager(List<IMovementStrategy> strategies) {
        this.strategies = strategies;
        this.damagePerRound = new double[strategies.size()];
        this.activeIndex = 0;
    }

    /** Get the command from the currently active strategy. Zero allocation. */
    public MovementCommand getActiveCommand(Whiteboard wb, StrategyParams params) {
        strategies.get(activeIndex).getCommand(wb, params, sharedCommand);
        return sharedCommand;
    }

    /** Get the currently active strategy name. */
    public String getActiveStrategyName() {
        return strategies.get(activeIndex).getName();
    }

    /**
     * Called at round end. Records damage taken by the active strategy
     * and selects the best one for the next round.
     */
    public void onRoundEnd(Whiteboard wb) {
        // Record damage for the active strategy this round
        damagePerRound[activeIndex] = wb.getDamageReceivedThisRound();
        roundsPlayed++;

        if (roundsPlayed <= strategies.size()) {
            // First pass: rotate through all strategies
            activeIndex = roundsPlayed % strategies.size();
        } else {
            // After trying all, pick the one with lowest damage
            double bestDamage = Double.MAX_VALUE;
            int bestIdx = 0;
            for (int i = 0; i < strategies.size(); i++) {
                if (damagePerRound[i] < bestDamage) {
                    bestDamage = damagePerRound[i];
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
            out.writeDouble(damagePerRound[i]);
        }
    }

    @Override
    public void readFrom(DataInputStream in, int length) throws IOException {
        int n = in.readInt();
        activeIndex = in.readInt();
        roundsPlayed = in.readInt();
        int count = Math.min(n, strategies.size());
        for (int i = 0; i < count; i++) {
            damagePerRound[i] = in.readDouble();
        }
        for (int i = count; i < n; i++) {
            in.readDouble(); // skip extra
        }
    }
}

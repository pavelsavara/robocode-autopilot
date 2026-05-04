package cz.zamboch.autopilot.core.movement;

import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.IMovementStrategy;
import cz.zamboch.autopilot.core.strategy.MovementCommand;
import cz.zamboch.autopilot.core.strategy.StrategyParams;

import java.util.List;

/**
 * Tracks multiple movement strategies and selects the best performer
 * via round-level damage comparison.
 */
public final class MovementStrategyManager {

    private final List<IMovementStrategy> strategies;
    private final double[] damagePerRound;
    private int activeIndex;
    private int roundsPlayed;

    public MovementStrategyManager(List<IMovementStrategy> strategies) {
        this.strategies = strategies;
        this.damagePerRound = new double[strategies.size()];
        this.activeIndex = 0;
    }

    /** Get the command from the currently active strategy. */
    public MovementCommand getActiveCommand(Whiteboard wb, StrategyParams params) {
        return strategies.get(activeIndex).getCommand(wb, params);
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

    /** Reset for a new round. */
    public void onRoundStart() {
        // Active strategy selection persists across rounds
    }
}

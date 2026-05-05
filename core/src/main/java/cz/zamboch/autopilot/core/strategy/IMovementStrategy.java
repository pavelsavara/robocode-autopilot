package cz.zamboch.autopilot.core.strategy;

import cz.zamboch.autopilot.core.Whiteboard;

/**
 * A movement strategy. Multiple strategies compete; the best performer
 * (measured by round-level damage taken) is selected as the active one.
 */
public interface IMovementStrategy {

    /**
     * Compute the movement command for this tick. Writes into {@code out}
     * to avoid per-tick allocation.
     */
    void getCommand(Whiteboard wb, StrategyParams params, MovementCommand out);

    /** Human-readable name. */
    String getName();
}

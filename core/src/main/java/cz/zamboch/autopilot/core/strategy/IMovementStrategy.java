package cz.zamboch.autopilot.core.strategy;

import cz.zamboch.autopilot.core.Whiteboard;

/**
 * A movement strategy. Multiple strategies compete; the best performer
 * (measured by round-level damage taken) is selected as the active one.
 */
public interface IMovementStrategy {

    /**
     * Compute the movement command for this tick.
     * The manager calls all strategies but only executes the active one.
     */
    MovementCommand getCommand(Whiteboard wb, StrategyParams params);

    /** Human-readable name. */
    String getName();
}

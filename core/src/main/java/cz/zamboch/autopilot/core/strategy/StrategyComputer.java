package cz.zamboch.autopilot.core.strategy;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Computes strategic parameters from predictor outputs.
 * 4-axis system: aggression, range, counter-strategy, phase.
 * Not final — designed for inheritance by trivial/distilled implementations.
 */
public abstract class StrategyComputer {

    /**
     * Recompute strategy params. Called every N ticks (not every tick).
     */
    public abstract StrategyParams compute(Whiteboard wb);
}

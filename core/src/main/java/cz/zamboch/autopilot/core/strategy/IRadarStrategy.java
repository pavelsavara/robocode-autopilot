package cz.zamboch.autopilot.core.strategy;

import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Radar control strategy. In 1v1 Robocode, the optimal radar is a narrow
 * oscillating lock on the opponent.
 */
public interface IRadarStrategy {

    /** Compute the radar turn angle in radians. */
    double getRadarTurn(Whiteboard wb);

    /** Human-readable name. */
    String getName();
}

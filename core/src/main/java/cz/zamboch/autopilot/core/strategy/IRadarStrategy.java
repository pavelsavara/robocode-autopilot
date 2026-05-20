package cz.zamboch.autopilot.core.strategy;

/**
 * Radar control strategy.
 */
public interface IRadarStrategy {

    /** Compute the radar turn angle in radians. */
    double getRadarTurn();

    /** Human-readable name. */
    String getName();
}

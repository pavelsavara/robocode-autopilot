package cz.zamboch.autopilot.core.strategy;

/**
 * Radar control strategy. Assumes the robot has called
 * {@code setAdjustRadarForRobotTurn(true)} and
 * {@code setAdjustRadarForGunTurn(true)} so the radar turn is independent of
 * body and gun motion.
 */
public interface IRadarStrategy {

    /** Compute the radar turn (radians) for this tick. */
    double getRadarTurn();

    /** Human-readable name. */
    String getName();
}

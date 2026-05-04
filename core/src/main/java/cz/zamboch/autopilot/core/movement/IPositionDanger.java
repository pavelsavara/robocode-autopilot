package cz.zamboch.autopilot.core.movement;

import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Scores absolute position danger independent of waves.
 * Considers wall proximity, corner proximity, distance to enemy,
 * battlefield control.
 */
public interface IPositionDanger {

    /**
     * @return danger in [0, 1]: 0 = safe, 1 = maximum danger
     */
    double danger(double x, double y, Whiteboard wb);
}

package cz.zamboch.autopilot.core.util;

/**
 * Math utilities for robocode geometry.
 */
public final class RoboMath {

    private RoboMath() {}

    /** Normalize angle to [0, 2*PI). */
    public static double normalAbsoluteAngle(double angle) {
        double result = angle % (2 * Math.PI);
        if (result < 0) {
            result += 2 * Math.PI;
        }
        return result;
    }

    /** Normalize angle to [-PI, PI). */
    public static double normalRelativeAngle(double angle) {
        double result = angle % (2 * Math.PI);
        if (result >= Math.PI) {
            result -= 2 * Math.PI;
        } else if (result < -Math.PI) {
            result += 2 * Math.PI;
        }
        return result;
    }
}

package cz.zamboch.autopilot.core;

/**
 * Robocode math utilities — angle normalization and common geometric helpers.
 * All angles are in radians.
 */
public final class RoboMath {

    private RoboMath() {
    }

    /**
     * Normalize angle to [-π, π].
     * Equivalent to Robocode's robocode.util.Utils.normalRelativeAngle.
     */
    public static double normalRelativeAngle(double angle) {
        angle %= (2 * Math.PI);
        if (angle >= 0)
            return (angle < Math.PI) ? angle : angle - 2 * Math.PI;
        else
            return (angle >= -Math.PI) ? angle : angle + 2 * Math.PI;
    }

    /**
     * Normalize angle to [0, 2π].
     * Equivalent to Robocode's robocode.util.Utils.normalAbsoluteAngle.
     */
    public static double normalAbsoluteAngle(double angle) {
        angle %= (2 * Math.PI);
        return angle >= 0 ? angle : angle + 2 * Math.PI;
    }

    /** FNV-1a 32-bit hash of a string (for opponent identification). */
    public static int fnv1a32(String s) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= 0x01000193;
        }
        return hash;
    }
}

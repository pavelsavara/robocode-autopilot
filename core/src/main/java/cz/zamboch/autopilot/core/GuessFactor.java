package cz.zamboch.autopilot.core;

/**
 * Static utilities for GuessFactor gun calculations.
 */
public final class GuessFactor {

    public static final int NUM_BINS = 31;
    public static final int ZERO_BIN = NUM_BINS / 2; // 15
    public static final int DISTANCE_SEGMENTS = 5;
    public static final int LAT_VEL_SEGMENTS = 5;

    private GuessFactor() {
    }

    /** Maximum escape angle for a given bullet speed. */
    public static double maxEscapeAngle(double bulletSpeed) {
        return Math.asin(8.0 / bulletSpeed);
    }

    /** Bullet speed from fire power. */
    public static double bulletSpeed(double power) {
        return 20.0 - 3.0 * power;
    }

    /**
     * Compute guess factor from angle offset, MEA, and direction.
     * Returns value in [-1, 1].
     */
    public static double guessFactor(double angleOffset, double mea, int direction) {
        double gf = (angleOffset / mea) * direction;
        return Math.max(-1.0, Math.min(1.0, gf));
    }

    /** Convert a GF value [-1, 1] to a bin index [0, numBins-1]. */
    public static int gfToBinIndex(double gf, int numBins) {
        int center = numBins / 2;
        int index = (int) Math.round(gf * center) + center;
        return Math.max(0, Math.min(numBins - 1, index));
    }

    /** Convert a bin index [0, numBins-1] back to GF value [-1, 1]. */
    public static double binIndexToGf(int index, int numBins) {
        int center = numBins / 2;
        return (double) (index - center) / center;
    }

    /** Distance segment index [0-4]. */
    public static int distanceSegment(double distance) {
        if (distance < 200)
            return 0;
        if (distance < 400)
            return 1;
        if (distance < 600)
            return 2;
        if (distance < 800)
            return 3;
        return 4;
    }

    /** Lateral velocity segment index [0-4]. Uses absolute value. */
    public static int lateralVelocitySegment(double latVel) {
        double abs = Math.abs(latVel);
        if (abs < 1.5)
            return 0;
        if (abs < 4.0)
            return 1;
        if (abs < 6.0)
            return 2;
        if (abs < 7.5)
            return 3;
        return 4;
    }

    /** Direction sign: +1 if lateral velocity >= 0 (CW), -1 otherwise (CCW). */
    public static int direction(double lateralVelocity) {
        return lateralVelocity >= 0 ? 1 : -1;
    }

}

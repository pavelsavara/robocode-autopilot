package cz.zamboch.autopilot.core;

/**
 * Static utilities for GuessFactor gun calculations.
 */
public final class GuessFactor {

    public static final int NUM_BINS = 31;
    public static final int ZERO_BIN = NUM_BINS / 2; // 15
    public static final int DISTANCE_SEGMENTS = 5;
    public static final int LAT_VEL_SEGMENTS = 5;
    /** Lag-1 dodge-context segments (coarse sign of the developing GF). */
    public static final int LAG1_SEGMENTS = 3;
    /** Threshold separating the center (no-context) lag-1 segment from the signed ones. */
    private static final double LAG1_THRESHOLD = 0.15;

    private GuessFactor() {
    }

    /** Maximum escape angle for a given bullet speed. */
    public static double maxEscapeAngle(double bulletSpeed) {
        return Math.asin(8.0 / bulletSpeed);
    }

    /** Half-width of the robot bounding box (px) — the GF hit tolerance source. */
    public static final double BOT_HALF_WIDTH = 18.0;

    /**
     * Precise maximum escape angle: the angular offset a target can reach by moving
     * perpendicular at max velocity for the bullet's full flight time, measured at
     * the intercept. Slightly wider than the linear {@link #maxEscapeAngle} because
     * the target's range grows as it flees, extending the flight time. Falls back to
     * the linear MEA when distance is unknown.
     */
    public static double preciseMaxEscapeAngle(double bulletSpeed, double distance) {
        if (Double.isNaN(distance) || distance <= 0 || bulletSpeed <= 0) {
            return maxEscapeAngle(bulletSpeed);
        }
        final double maxVelocity = 8.0;
        double lateral = 0.0;
        for (int tick = 1; tick <= 256; tick++) {
            lateral += maxVelocity;
            double range = Math.hypot(lateral, distance);
            if (bulletSpeed * tick >= range) {
                return Math.atan2(lateral, distance);
            }
        }
        return maxEscapeAngle(bulletSpeed);
    }

    /**
     * Half-width (in GF bins) of the bullet's hit band at a given distance/MEA, so a
     * gun can aim where the box-convolved hit count peaks instead of the raw bin
     * mode. The hittable GF half-band is {@code BOT_HALF_WIDTH/(distance*mea)}; this
     * maps it to bin units (GF in [-1,1] over {@code numBins}). Always at least 1 so
     * a lone saturation spike cannot capture the aim.
     */
    public static int gfBoxWindowBins(double distance, double mea, int numBins) {
        if (Double.isNaN(distance) || Double.isNaN(mea) || distance <= 0 || mea <= 0) {
            return 0;
        }
        double boxTolGf = BOT_HALF_WIDTH / (distance * mea);
        int half = numBins / 2;
        int w = (int) Math.round(boxTolGf * half);
        return Math.max(1, Math.min(half, w));
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

    /**
     * Lag-1 dodge-context segment index [0, {@link #LAG1_SEGMENTS}-1] from the
     * developing GF of the most-recent in-flight wave. Coarse 3-level sign split:
     * 0 = developing toward negative GF, 1 = center / no context, 2 = positive.
     * A NaN developing GF (no active prior wave) maps to the center segment.
     */
    public static int lag1Segment(double developingGf) {
        if (Double.isNaN(developingGf)) {
            return 1;
        }
        if (developingGf < -LAG1_THRESHOLD) {
            return 0;
        }
        if (developingGf > LAG1_THRESHOLD) {
            return 2;
        }
        return 1;
    }

    /**
     * Recorded-convention guess factor of an in-flight wave evaluated against a
     * target position — the "developing GF". Mirrors {@link Wave#computeGuessFactor}
     * so the lag-1 dimension is consistent with how break GF is recorded.
     */
    public static double developingGuessFactor(double fireX, double fireY,
            double fireBearing, double mea, int direction, double oppX, double oppY) {
        double actualBearing = Math.atan2(oppX - fireX, oppY - fireY);
        double angleOffset = RoboMath.normalRelativeAngle(actualBearing - fireBearing);
        return guessFactor(angleOffset, mea, direction);
    }

}

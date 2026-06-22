package cz.zamboch.autopilot.core;

/**
 * Data class for an in-flight bullet wave.
 * Created when we fire; resolved when wave reaches opponent.
 */
public final class Wave {
    public final double fireX;
    public final double fireY;
    public final long fireTick;
    public final double fireBearing;
    public final double bulletSpeed;
    public final int direction;
    public final int distanceSegment;
    public final int latVelSegment;
    public final double mea;
    /** Bullet ID for hit correlation (from Bullet.hashCode()). */
    public final int bulletId;
    /** Set to true when onBulletHit fires for this wave's bullet. */
    public boolean hit;
    /**
     * Lag-1 dodge-context developing guess factor captured at aim time (the raw
     * GF of the most-recent in-flight wave). NaN means "no context"; the VcsStore
     * bins it into the center slice. Set by the wave creator.
     */
    public double lag1Gf = Double.NaN;

    public Wave(double fireX, double fireY, long fireTick, double fireBearing,
            double bulletSpeed, int direction, int distanceSegment,
            int latVelSegment) {
        this(fireX, fireY, fireTick, fireBearing, bulletSpeed, direction, distanceSegment, latVelSegment, 0);
    }

    public Wave(double fireX, double fireY, long fireTick, double fireBearing,
            double bulletSpeed, int direction, int distanceSegment,
            int latVelSegment, int bulletId) {
        this(fireX, fireY, fireTick, fireBearing, bulletSpeed, direction,
                distanceSegment, latVelSegment, bulletId, Double.NaN);
    }

    public Wave(double fireX, double fireY, long fireTick, double fireBearing,
            double bulletSpeed, int direction, int distanceSegment,
            int latVelSegment, int bulletId, double distance) {
        this.fireX = fireX;
        this.fireY = fireY;
        this.fireTick = fireTick;
        this.fireBearing = fireBearing;
        this.bulletSpeed = bulletSpeed;
        this.direction = direction;
        this.distanceSegment = distanceSegment;
        this.latVelSegment = latVelSegment;
        this.mea = GuessFactor.preciseMaxEscapeAngle(bulletSpeed, distance);
        this.bulletId = bulletId;
    }

    /**
     * Distance the wave has travelled from origin at the given tick.
     */
    public double distanceTravelled(long currentTick) {
        return (currentTick - fireTick) * bulletSpeed;
    }

    /**
     * Check if wave has reached (passed) the target position.
     */
    public boolean hasReached(double targetX, double targetY, long currentTick) {
        double dx = targetX - fireX;
        double dy = targetY - fireY;
        double distToTarget = Math.sqrt(dx * dx + dy * dy);
        return distanceTravelled(currentTick) >= distToTarget;
    }

    /**
     * Compute the guess factor for where the opponent actually was when the
     * wave reached them. Returns value in [-1, 1].
     */
    public double computeGuessFactor(double targetX, double targetY) {
        return computeGuessFactor(targetX, targetY, fireBearing);
    }

    /**
     * Compute the guess factor using an explicit GF=0 baseline bearing instead of
     * {@link #fireBearing}. The pre-aim convention anchors the baseline at the
     * fire origin pointing to the aim-time opponent reference, so the realized GF
     * matches the GF the gun intended when it pre-aimed at T-1. Returns [-1, 1].
     */
    public double computeGuessFactor(double targetX, double targetY, double baseBearing) {
        double dx = targetX - fireX;
        double dy = targetY - fireY;
        // Angle to actual position (Robocode: 0=north, CW)
        double actualBearing = Math.atan2(dx, dy);
        double angleOffset = RoboMath.normalRelativeAngle(actualBearing - baseBearing);
        return GuessFactor.guessFactor(angleOffset, mea, direction);
    }

    /**
     * Compute the guess factor with both an explicit GF=0 baseline bearing and an
     * explicit direction (the aim-time direction the gun keyed its GF sign on), so
     * the realized GF matches the intended aim GF even if the opponent's lateral
     * motion reversed between aim and fire. Returns [-1, 1].
     */
    public double computeGuessFactor(double targetX, double targetY,
            double baseBearing, int direction) {
        double dx = targetX - fireX;
        double dy = targetY - fireY;
        double actualBearing = Math.atan2(dx, dy);
        double angleOffset = RoboMath.normalRelativeAngle(actualBearing - baseBearing);
        return GuessFactor.guessFactor(angleOffset, mea, direction);
    }
}

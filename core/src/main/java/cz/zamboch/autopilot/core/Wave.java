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

    public Wave(double fireX, double fireY, long fireTick, double fireBearing,
            double bulletSpeed, int direction, int distanceSegment,
            int latVelSegment) {
        this.fireX = fireX;
        this.fireY = fireY;
        this.fireTick = fireTick;
        this.fireBearing = fireBearing;
        this.bulletSpeed = bulletSpeed;
        this.direction = direction;
        this.distanceSegment = distanceSegment;
        this.latVelSegment = latVelSegment;
        this.mea = GuessFactor.maxEscapeAngle(bulletSpeed);
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
        double dx = targetX - fireX;
        double dy = targetY - fireY;
        // Angle to actual position (Robocode: 0=north, CW)
        double actualBearing = Math.atan2(dx, dy);
        double angleOffset = RoboMath.normalRelativeAngle(actualBearing - fireBearing);
        return GuessFactor.guessFactor(angleOffset, mea, direction);
    }
}

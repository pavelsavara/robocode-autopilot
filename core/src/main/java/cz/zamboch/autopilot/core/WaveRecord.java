package cz.zamboch.autopilot.core;

/**
 * Immutable record of a detected bullet wave. Tracks an in-flight bullet
 * from fire detection until it passes the target.
 *
 * Used for multi-wave tracking in Whiteboard. Both "our waves" (bullets we
 * fired) and "opponent waves" (detected via energy drop) are stored.
 */
public final class WaveRecord {
    /** Firer's X position at fire time. */
    public final double originX;
    /** Firer's Y position at fire time. */
    public final double originY;
    /** Bullet speed: 20 - 3 * power. */
    public final double bulletSpeed;
    /** Bullet power [0.1, 3.0]. */
    public final double bulletPower;
    /** Tick when the bullet was fired. */
    public final long fireTick;
    /** Distance from firer to target at fire time. */
    public final double fireDistance;
    /** Bearing from firer to target at fire time (rad). Used for GF computation at wave break. */
    public final double fireBearing;
    /** Target's lateral direction at fire time (+1/-1). Used for VCS segmentation at wave break. */
    public final int fireLateralDir;
    /** Magnitude of target's lateral velocity at fire time (px/tick, ≥0). Used for VCS velocity bucket. */
    public final double fireLateralVelMag;

    public WaveRecord(double originX, double originY, double bulletSpeed,
                      double bulletPower, long fireTick, double fireDistance,
                      double fireBearing, int fireLateralDir, double fireLateralVelMag) {
        this.originX = originX;
        this.originY = originY;
        this.bulletSpeed = bulletSpeed;
        this.bulletPower = bulletPower;
        this.fireTick = fireTick;
        this.fireDistance = fireDistance;
        this.fireBearing = fireBearing;
        this.fireLateralDir = fireLateralDir;
        this.fireLateralVelMag = fireLateralVelMag;
    }

    /** Backward-compatible constructor — fireLateralVelMag defaults to 0. */
    public WaveRecord(double originX, double originY, double bulletSpeed,
                      double bulletPower, long fireTick, double fireDistance,
                      double fireBearing, int fireLateralDir) {
        this(originX, originY, bulletSpeed, bulletPower, fireTick, fireDistance,
                fireBearing, fireLateralDir, 0.0);
    }

    /** Backward-compatible constructor — fireLateralDir defaults to 1. */
    public WaveRecord(double originX, double originY, double bulletSpeed,
                      double bulletPower, long fireTick, double fireDistance,
                      double fireBearing) {
        this(originX, originY, bulletSpeed, bulletPower, fireTick, fireDistance,
                fireBearing, 1, 0.0);
    }

    /** Backward-compatible constructor — fireBearing defaults to NaN, fireLateralDir to 1. */
    public WaveRecord(double originX, double originY, double bulletSpeed,
                      double bulletPower, long fireTick, double fireDistance) {
        this(originX, originY, bulletSpeed, bulletPower, fireTick, fireDistance,
                Double.NaN, 1, 0.0);
    }

    /** Damage this bullet would deal on hit. */
    public double damage() {
        return (bulletPower <= 1.0)
                ? 4.0 * bulletPower
                : 6.0 * bulletPower - 2.0;
    }

    /** Current wave radius at the given tick. */
    public double radius(long currentTick) {
        return (currentTick - fireTick) * bulletSpeed;
    }

    /** Has this wave passed beyond a target at the given distance? */
    public boolean hasPassed(double targetDistance, long currentTick) {
        return radius(currentTick) > targetDistance + 18; // 18 = robot half-size
    }
}

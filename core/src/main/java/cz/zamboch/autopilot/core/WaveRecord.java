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

    public WaveRecord(double originX, double originY, double bulletSpeed,
                      double bulletPower, long fireTick, double fireDistance) {
        this.originX = originX;
        this.originY = originY;
        this.bulletSpeed = bulletSpeed;
        this.bulletPower = bulletPower;
        this.fireTick = fireTick;
        this.fireDistance = fireDistance;
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

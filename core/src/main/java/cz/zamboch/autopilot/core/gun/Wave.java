package cz.zamboch.autopilot.core.gun;

/**
 * Incoming bullet wave tracking. Tracks an opponent's bullet from
 * fire detection until it passes our position.
 */
public final class Wave {

    /** Opponent position at fire time. */
    public final double originX, originY;
    /** Bullet speed: 20 - 3 * power. */
    public final double bulletSpeed;
    /** Inferred bullet power from energy drop. */
    public final double bulletPower;
    /** Tick when fired. */
    public final long fireTick;
    /** Bearing from opponent to us at fire time. */
    public final double fireAngle;

    public Wave(double originX, double originY, double bulletSpeed,
                double bulletPower, long fireTick, double fireAngle) {
        this.originX = originX;
        this.originY = originY;
        this.bulletSpeed = bulletSpeed;
        this.bulletPower = bulletPower;
        this.fireTick = fireTick;
        this.fireAngle = fireAngle;
    }

    /** Damage this bullet would deal on hit. */
    public double damage() {
        return (bulletPower <= 1.0)
                ? 4.0 * bulletPower
                : 6.0 * bulletPower - 2.0;
    }

    /** Current radius of the expanding wave. */
    public double radius(long currentTick) {
        return (currentTick - fireTick) * bulletSpeed;
    }

    /** Has this wave passed beyond our position? */
    public boolean hasPassed(double ourX, double ourY, long currentTick) {
        double dist = Math.hypot(ourX - originX, ourY - originY);
        return radius(currentTick) > dist + 18; // 18 = robot half-size
    }
}

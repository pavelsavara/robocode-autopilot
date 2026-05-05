package cz.zamboch.autopilot.core.strategy;

/**
 * Tracking state for a virtual bullet fired by a gun strategy.
 * Used by VirtualGunManager to evaluate hit rates.
 *
 * <p>Mutable — can be reused via {@link #set} to avoid per-scan allocation.
 */
public final class VirtualBullet {

    /** Firer's X position at fire time. */
    public double startX;
    /** Firer's Y position at fire time. */
    public double startY;
    /** Absolute angle the bullet travels. */
    public double heading;
    /** Bullet speed (20 - 3 * power). */
    public double speed;
    /** Tick when the virtual bullet was created. */
    public long fireTick;
    /** Distance to opponent at fire time. */
    public double fireDistance;
    /** Whether this slot is in use. */
    public boolean active;

    public VirtualBullet() {}

    public VirtualBullet(double startX, double startY, double heading,
                         double speed, long fireTick, double fireDistance) {
        set(startX, startY, heading, speed, fireTick, fireDistance);
    }

    /** Overwrite all fields and mark active. */
    public void set(double startX, double startY, double heading,
                    double speed, long fireTick, double fireDistance) {
        this.startX = startX;
        this.startY = startY;
        this.heading = heading;
        this.speed = speed;
        this.fireTick = fireTick;
        this.fireDistance = fireDistance;
        this.active = true;
    }

    /** Current distance the bullet has traveled. */
    public double distanceTraveled(long currentTick) {
        return (currentTick - fireTick) * speed;
    }

    /** Has this bullet traveled past the opponent's distance? */
    public boolean hasPassed(long currentTick) {
        return distanceTraveled(currentTick) > fireDistance + 18;
    }
}

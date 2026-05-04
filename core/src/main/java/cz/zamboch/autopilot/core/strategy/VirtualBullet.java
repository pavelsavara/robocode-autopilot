package cz.zamboch.autopilot.core.strategy;

/**
 * Tracking state for a virtual bullet fired by a gun strategy.
 * Used by VirtualGunManager to evaluate hit rates.
 */
public final class VirtualBullet {

    /** Firer's X position at fire time. */
    public final double startX;
    /** Firer's Y position at fire time. */
    public final double startY;
    /** Absolute angle the bullet travels. */
    public final double heading;
    /** Bullet speed (20 - 3 * power). */
    public final double speed;
    /** Tick when the virtual bullet was created. */
    public final long fireTick;
    /** Distance to opponent at fire time. */
    public final double fireDistance;

    public VirtualBullet(double startX, double startY, double heading,
                         double speed, long fireTick, double fireDistance) {
        this.startX = startX;
        this.startY = startY;
        this.heading = heading;
        this.speed = speed;
        this.fireTick = fireTick;
        this.fireDistance = fireDistance;
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

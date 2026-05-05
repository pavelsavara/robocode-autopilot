package cz.zamboch.autopilot.core.gun;

import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Double-stack fire plan: fires a slow bullet (power 3.0, speed 11)
 * followed by a fast bullet (power 0.1, speed 19.7). The two bullets
 * converge at ~400 px. Each aimed independently by the current best
 * gun strategy at its respective fire tick.
 */
public final class DoubleStackPlan implements IFirePlan {

    private static final double POWER_HEAVY = 3.0;
    private static final double POWER_LIGHT = 0.1;
    private static final double[] POWERS = {POWER_HEAVY, POWER_LIGHT};

    private final AngleSupplier angleSupplier;
    private int shotIndex;

    /**
     * @param angleSupplier callback returning the current best fire angle
     */
    public DoubleStackPlan(AngleSupplier angleSupplier) {
        this.angleSupplier = angleSupplier;
    }

    @Override
    public boolean hasNextShot() {
        return shotIndex < POWERS.length;
    }

    @Override
    public double getNextPower() {
        return POWERS[shotIndex];
    }

    @Override
    public double getNextAngle(Whiteboard wb) {
        return angleSupplier.getBestAngle(wb);
    }

    @Override
    public void onShotFired() {
        shotIndex++;
    }

    @Override
    public void reset() {
        shotIndex = 0;
    }

    @Override
    public String getName() {
        return "double-stack";
    }

    /** Callback interface to get the current best fire angle. */
    public interface AngleSupplier {
        double getBestAngle(Whiteboard wb);
    }
}

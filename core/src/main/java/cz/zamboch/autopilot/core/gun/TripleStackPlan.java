package cz.zamboch.autopilot.core.gun;

import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Triple-stack fire plan: heavy (3.0) + medium (~1.85) + light (0.1).
 * Heavy+medium converge at ~400-500 px; light chaser arrives ~8 ticks later.
 * Each shot aimed independently at its fire tick.
 */
public final class TripleStackPlan implements IFirePlan {

    private static final double POWER_HEAVY = 3.0;
    private static final double POWER_MEDIUM = 1.85;
    private static final double POWER_LIGHT = 0.1;
    private static final double[] POWERS = {POWER_HEAVY, POWER_MEDIUM, POWER_LIGHT};

    private final DoubleStackPlan.AngleSupplier angleSupplier;
    private int shotIndex;

    /**
     * @param angleSupplier callback returning the current best fire angle
     */
    public TripleStackPlan(DoubleStackPlan.AngleSupplier angleSupplier) {
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
        return "triple-stack";
    }
}

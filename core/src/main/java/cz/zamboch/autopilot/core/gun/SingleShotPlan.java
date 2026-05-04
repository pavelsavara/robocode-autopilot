package cz.zamboch.autopilot.core.gun;

import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.IGunStrategy;

/**
 * Single-shot fire plan — wraps an IGunStrategy for one-shot firing.
 * Power comes from StrategyParams.firePowerBudget.
 */
public final class SingleShotPlan implements IFirePlan {

    private final IGunStrategy strategy;
    private double firePower;
    private boolean fired;

    public SingleShotPlan(IGunStrategy strategy, double firePower) {
        this.strategy = strategy;
        this.firePower = firePower;
    }

    public void setFirePower(double firePower) {
        this.firePower = firePower;
    }

    @Override
    public boolean hasNextShot() {
        return !fired;
    }

    @Override
    public double getNextPower() {
        return firePower;
    }

    @Override
    public double getNextAngle(Whiteboard wb) {
        return strategy.getFireAngle(wb);
    }

    @Override
    public void onShotFired() {
        fired = true;
    }

    @Override
    public void reset() {
        fired = false;
    }

    @Override
    public String getName() {
        return "single-shot-" + strategy.getName();
    }
}

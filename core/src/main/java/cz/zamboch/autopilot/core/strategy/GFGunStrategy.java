package cz.zamboch.autopilot.core.strategy;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * GuessFactor gun strategy. Reads pre-computed GUN_AIM_ANGLE and GUN_AIM_POWER
 * features from the Whiteboard (produced by OurWaveFeatures).
 */
public final class GFGunStrategy implements IGunStrategy {

    private final Whiteboard wb;

    public GFGunStrategy(Whiteboard wb) {
        this.wb = wb;
    }

    @Override
    public void getFireCommand(FireCommand out) {
        double aimAngle = wb.getFeature(Feature.GUN_AIM_ANGLE);
        double power = wb.getFeature(Feature.GUN_AIM_POWER);
        if (Double.isNaN(aimAngle)) {
            out.set(Double.NaN, 0);
            return;
        }
        out.set(aimAngle, Double.isNaN(power) ? 0 : power);
    }

    @Override
    public String getName() {
        return "GFGun";
    }
}

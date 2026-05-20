package cz.zamboch.autopilot.core.strategy;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Head-on targeting. Fires at opponent's current bearing.
 * Power inversely proportional to distance.
 */
public final class HeadOnGunStrategy implements IGunStrategy {

    private final Whiteboard wb;

    public HeadOnGunStrategy(Whiteboard wb) {
        this.wb = wb;
    }

    @Override
    public void getFireCommand(FireCommand out) {
        double distance = wb.getFeature(Feature.DISTANCE);
        if (Double.isNaN(distance)) {
            out.set(Double.NaN, 0);
            return;
        }
        double absoluteBearing = wb.getFeature(Feature.OPPONENT_BEARING_ABSOLUTE);
        double power = Math.min(3.0, Math.max(1.0, (400.0 - distance) / 100.0 + 1.0));
        double gunHeat = wb.getFeature(Feature.GUN_HEAT);
        if (gunHeat > 0) {
            power = 0;
        }
        out.set(absoluteBearing, power);
    }

    @Override
    public String getName() {
        return "HeadOnGun";
    }
}

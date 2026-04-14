package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Opponent prediction features: wall-ahead ray-cast distance, inferred gun heat.
 * Depends on OPPONENT_FIRED and OPPONENT_FIRE_POWER from EnergyFeatures.
 */
public class OpponentPredictionFeatures implements IInGameFeatures {

    private long lastDetectedFireTick = -1;
    private double lastDetectedFirePower = 0;

    private static final Feature[] OUTPUTS = {
            Feature.OPPONENT_WALL_AHEAD_DISTANCE,
            Feature.OPPONENT_INFERRED_GUN_HEAT
    };

    private static final Feature[] DEPS = {
            Feature.OPPONENT_FIRED,
            Feature.OPPONENT_FIRE_POWER
    };

    public Feature[] getOutputFeatures() {
        return OUTPUTS;
    }

    public Feature[] getDependencies() {
        return DEPS;
    }

    public void process(Whiteboard wb) {
        if (!wb.isScanAvailableThisTick()) {
            return;
        }

        // Update fire detection state
        if (wb.hasFeature(Feature.OPPONENT_FIRED) && wb.getFeature(Feature.OPPONENT_FIRED) > 0.5) {
            lastDetectedFireTick = wb.getTick();
            lastDetectedFirePower = wb.getFeature(Feature.OPPONENT_FIRE_POWER);
        }

        // Wall ahead distance: ray-cast from opponent position along opponent heading
        double oppX = wb.getOpponentX();
        double oppY = wb.getOpponentY();
        double oppHeading = wb.getOpponentHeading();
        int bfW = wb.getBattlefieldWidth();
        int bfH = wb.getBattlefieldHeight();

        // Direction vector (robocode heading: 0=north, CW)
        double dx = Math.sin(oppHeading);
        double dy = Math.cos(oppHeading);

        // Distance to each wall along this heading (18px robot offset)
        double distToWall = Double.MAX_VALUE;
        if (dx > 0) {
            distToWall = Math.min(distToWall, (bfW - 18 - oppX) / dx);
        } else if (dx < 0) {
            distToWall = Math.min(distToWall, (18 - oppX) / dx);
        }
        if (dy > 0) {
            distToWall = Math.min(distToWall, (bfH - 18 - oppY) / dy);
        } else if (dy < 0) {
            distToWall = Math.min(distToWall, (18 - oppY) / dy);
        }
        wb.setFeature(Feature.OPPONENT_WALL_AHEAD_DISTANCE,
                distToWall == Double.MAX_VALUE ? 0 : distToWall);

        // Inferred gun heat: max(0, (1 + firepower/5) - elapsed * coolingRate)
        double coolingRate = wb.getGunCoolingRate();
        if (lastDetectedFireTick >= 0) {
            long elapsed = wb.getTick() - lastDetectedFireTick;
            double heatFromFire = 1.0 + lastDetectedFirePower / 5.0;
            wb.setFeature(Feature.OPPONENT_INFERRED_GUN_HEAT,
                    Math.max(0, heatFromFire - elapsed * coolingRate));
        } else {
            // No fire detected yet — initial gun heat cools from 3.0 (robocode default)
            wb.setFeature(Feature.OPPONENT_INFERRED_GUN_HEAT,
                    Math.max(0, 3.0 - wb.getTick() * coolingRate));
        }
    }
}

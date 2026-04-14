package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.util.RoboMath;

/**
 * State normalisation features: energy ratio, our lateral velocity, our wall distance.
 * Depends on BEARING_TO_OPPONENT_ABS from SpatialFeatures.
 */
public class StateNormalizationFeatures implements IInGameFeatures {

    private static final Feature[] OUTPUTS = {
            Feature.ENERGY_RATIO,
            Feature.OUR_LATERAL_VELOCITY,
            Feature.OUR_DIST_TO_WALL_MIN
    };

    private static final Feature[] DEPS = {
            Feature.BEARING_TO_OPPONENT_ABS
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

        // Energy ratio: our / (our + opponent)
        double ourEnergy = wb.getOurEnergy();
        double oppEnergy = wb.getOpponentEnergy();
        double total = ourEnergy + oppEnergy;
        wb.setFeature(Feature.ENERGY_RATIO, total > 0 ? ourEnergy / total : 0.5);

        // Our lateral velocity relative to bearing line to opponent
        double absBearing = wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS);
        double ourHeading = wb.getOurHeading();
        double ourVelocity = wb.getOurVelocity();
        double relativeHeading = RoboMath.normalRelativeAngle(ourHeading - absBearing);
        wb.setFeature(Feature.OUR_LATERAL_VELOCITY, ourVelocity * Math.sin(relativeHeading));

        // Our distance to nearest wall (18px robot half-width offset)
        double ourX = wb.getOurX();
        double ourY = wb.getOurY();
        int bfW = wb.getBattlefieldWidth();
        int bfH = wb.getBattlefieldHeight();
        double distN = bfH - ourY - 18;
        double distS = ourY - 18;
        double distE = bfW - ourX - 18;
        double distW = ourX - 18;
        wb.setFeature(Feature.OUR_DIST_TO_WALL_MIN,
                Math.min(Math.min(distN, distS), Math.min(distE, distW)));
    }
}

package cz.zamboch.trivial;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Trivial movement predictor — persists current lateral velocity.
 */
public final class TrivialMovementPredictor implements IInGameFeatures {

    @Override
    public Feature[] getOutputFeatures() {
        return new Feature[]{
                Feature.PREDICTED_LAT_VEL_5,
                Feature.PREDICTED_LAT_VEL_5_CONFIDENCE
        };
    }

    @Override
    public Feature[] getDependencies() {
        return new Feature[]{Feature.OPPONENT_LATERAL_VELOCITY};
    }

    @Override
    public void process(Whiteboard wb) {
        double latVel = wb.getFeature(Feature.OPPONENT_LATERAL_VELOCITY);
        wb.setFeature(Feature.PREDICTED_LAT_VEL_5, latVel);
        wb.setFeature(Feature.PREDICTED_LAT_VEL_5_CONFIDENCE, 0.5);
    }
}

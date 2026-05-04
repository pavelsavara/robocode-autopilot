package cz.zamboch.trivial;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Trivial fire timing predictor — always returns base rate 0.07.
 */
public final class TrivialFireTimingPredictor implements IInGameFeatures {

    @Override
    public Feature[] getOutputFeatures() {
        return new Feature[]{
                Feature.PREDICTED_OPPONENT_FIRES_3,
                Feature.PREDICTED_OPPONENT_FIRES_3_CONFIDENCE
        };
    }

    @Override
    public Feature[] getDependencies() {
        return new Feature[]{Feature.OPPONENT_INFERRED_GUN_HEAT};
    }

    @Override
    public void process(Whiteboard wb) {
        wb.setFeature(Feature.PREDICTED_OPPONENT_FIRES_3, 0.07);
        wb.setFeature(Feature.PREDICTED_OPPONENT_FIRES_3_CONFIDENCE, 0.0);
    }
}

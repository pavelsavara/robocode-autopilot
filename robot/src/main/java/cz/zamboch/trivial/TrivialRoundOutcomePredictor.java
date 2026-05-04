package cz.zamboch.trivial;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Trivial round outcome predictor — always returns 0.5 (uncertain).
 */
public final class TrivialRoundOutcomePredictor implements IInGameFeatures {

    @Override
    public Feature[] getOutputFeatures() {
        return new Feature[]{
                Feature.PREDICTED_WIN_PROBABILITY,
                Feature.PREDICTED_WIN_PROBABILITY_CONFIDENCE
        };
    }

    @Override
    public Feature[] getDependencies() {
        return new Feature[]{Feature.ENERGY_RATIO};
    }

    @Override
    public void process(Whiteboard wb) {
        wb.setFeature(Feature.PREDICTED_WIN_PROBABILITY, 0.5);
        wb.setFeature(Feature.PREDICTED_WIN_PROBABILITY_CONFIDENCE, 0.0);
    }
}

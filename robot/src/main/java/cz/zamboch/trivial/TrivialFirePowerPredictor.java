package cz.zamboch.trivial;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Trivial fire power predictor — returns random power in [1.0, 3.0].
 */
public final class TrivialFirePowerPredictor implements IInGameFeatures {

    @Override
    public Feature[] getOutputFeatures() {
        return new Feature[]{
                Feature.PREDICTED_FIRE_POWER,
                Feature.PREDICTED_FIRE_POWER_CONFIDENCE
        };
    }

    @Override
    public Feature[] getDependencies() {
        return new Feature[]{Feature.OPPONENT_ENERGY, Feature.ENERGY_RATIO};
    }

    @Override
    public void process(Whiteboard wb) {
        double power = 1.0 + Math.random() * 2.0;
        wb.setFeature(Feature.PREDICTED_FIRE_POWER, power);
        wb.setFeature(Feature.PREDICTED_FIRE_POWER_CONFIDENCE, 0.5);
    }
}

package cz.zamboch.distilled;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Distilled GBM fire power predictor skeleton.
 * Phase 8 will generate {@code GbmFirePowerModel.java} with the actual
 * tree if/else code from XGBoost export.
 *
 * <p>Reads ~80 tick features from Whiteboard, maps them to the model's
 * input vector, delegates to the generated model, and writes
 * {@link Feature#PREDICTED_FIRE_POWER}.</p>
 */
public final class GbmFirePowerPredictor implements IInGameFeatures {

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
        // Phase 8: extract features, call GbmFirePowerModel.predict(features)
        // For now, fall back to a simple heuristic
        double opponentEnergy = wb.getOpponentEnergy();
        double power;
        if (opponentEnergy > 50) {
            power = 2.0;
        } else if (opponentEnergy > 20) {
            power = 1.5;
        } else {
            power = 1.0;
        }
        wb.setFeature(Feature.PREDICTED_FIRE_POWER, power);
        wb.setFeature(Feature.PREDICTED_FIRE_POWER_CONFIDENCE, 0.0);
    }
}

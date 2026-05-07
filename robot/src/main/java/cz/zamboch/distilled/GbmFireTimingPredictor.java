package cz.zamboch.distilled;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Distilled GBM fire timing predictor skeleton.
 * Phase 8 will generate {@code GbmFireTimingModel.java} with the actual
 * tree if/else code from XGBoost export.
 *
 * <p>Predicts probability that the opponent fires within the next 3 ticks.
 * Reads tick features, delegates to generated model, writes
 * {@link Feature#PREDICTED_OPPONENT_FIRES_3}.</p>
 */
public final class GbmFireTimingPredictor implements IInGameFeatures {

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
        // Phase 8: extract features, call GbmFireTimingModel.predict(features)
        // For now, use gun heat as a simple heuristic
        double gunHeat = wb.hasFeature(Feature.OPPONENT_INFERRED_GUN_HEAT)
                ? wb.getFeature(Feature.OPPONENT_INFERRED_GUN_HEAT) : 1.0;
        double prob = gunHeat <= 0.0 ? 0.5 : 0.07;
        wb.setFeature(Feature.PREDICTED_OPPONENT_FIRES_3, prob);
        wb.setFeature(Feature.PREDICTED_OPPONENT_FIRES_3_CONFIDENCE, 0.0);
    }
}

package cz.zamboch.distilled;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Distilled GBM movement predictor skeleton.
 * Phase 8 will generate {@code GbmMovementModel.java} with the actual
 * tree if/else code from XGBoost export.
 *
 * <p>Predicts opponent lateral velocity at t+5. Reads tick features,
 * delegates to generated model, writes
 * {@link Feature#PREDICTED_LAT_VEL_5}.</p>
 */
public final class GbmMovementPredictor implements IInGameFeatures {

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
        // Phase 8: extract features, call GbmMovementModel.predict(features)
        // For now, persist current lateral velocity (trivial baseline)
        double latVel = wb.hasFeature(Feature.OPPONENT_LATERAL_VELOCITY)
                ? wb.getFeature(Feature.OPPONENT_LATERAL_VELOCITY) : 0;
        wb.setFeature(Feature.PREDICTED_LAT_VEL_5, latVel);
        wb.setFeature(Feature.PREDICTED_LAT_VEL_5_CONFIDENCE, 0.0);
    }
}

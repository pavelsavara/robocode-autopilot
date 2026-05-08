package cz.zamboch.distilled;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.ml.FeatureMapping;
import cz.zamboch.autopilot.core.ml.GbmTreeEnsemble;

/**
 * Distilled GBM movement predictor. Predicts opponent lateral velocity
 * at t+5 using a 200-tree XGBoost model loaded from a binary resource.
 *
 * <p>R²=0.739, MAE=2.31 (compact model, 200 trees × depth 6).</p>
 */
public final class GbmMovementPredictor implements IInGameFeatures {

    private GbmTreeEnsemble model;
    private Feature[] featureIndex;
    private double[] inputBuffer;
    private boolean loaded;

    /** Whether the binary model was successfully loaded (vs heuristic fallback). */
    public boolean isModelLoaded() { return model != null; }

    @Override
    public Feature[] getOutputFeatures() {
        return new Feature[]{
                Feature.PREDICTED_LAT_VEL_5,
                Feature.PREDICTED_LAT_VEL_5_CONFIDENCE
        };
    }

    @Override
    public Feature[] getDependencies() {
        return new Feature[]{Feature.OPPONENT_VELOCITY, Feature.DISTANCE_WMEAN};
    }

    @Override
    public void process(Whiteboard wb) {
        if (!loaded) {
            try {
                model = MovementData.load();
                featureIndex = FeatureMapping.buildIndex(MovementData.FEATURE_NAMES);
                inputBuffer = new double[MovementData.FEATURE_NAMES.length];
                loaded = true;
            } catch (Exception e) {
                loaded = true;
            }
        }

        if (model != null) {
            FeatureMapping.extract(wb, featureIndex, inputBuffer);
            double pred = model.predictRaw(inputBuffer);
            pred = Math.max(-8.0, Math.min(8.0, pred));
            wb.setFeature(Feature.PREDICTED_LAT_VEL_5, pred);
            wb.setFeature(Feature.PREDICTED_LAT_VEL_5_CONFIDENCE, 0.7);
        } else {
            double latVel = wb.hasFeature(Feature.OPPONENT_LATERAL_VELOCITY)
                    ? wb.getFeature(Feature.OPPONENT_LATERAL_VELOCITY) : 0;
            wb.setFeature(Feature.PREDICTED_LAT_VEL_5, latVel);
            wb.setFeature(Feature.PREDICTED_LAT_VEL_5_CONFIDENCE, 0.0);
        }
    }
}

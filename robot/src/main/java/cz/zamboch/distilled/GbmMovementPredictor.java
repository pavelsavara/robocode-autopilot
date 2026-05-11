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

    // Static model loaded at class-load time (during robot instantiation)
    private static final GbmTreeEnsemble STATIC_MODEL;
    private static final Feature[] STATIC_FEATURE_INDEX;
    static {
        GbmTreeEnsemble m = null;
        Feature[] fi = null;
        try {
            m = MovementData.load();
            fi = FeatureMapping.buildIndex(MovementData.FEATURE_NAMES);
            if (m != null) {
                m.validateFeatureDimension(MovementData.FEATURE_NAMES.length, "Movement");
            }
        } catch (Exception e) {
            // model stays null — heuristic fallback will be used
        }
        STATIC_MODEL = m;
        STATIC_FEATURE_INDEX = fi;
    }

    /** Force class loading (and thus static model init). Call from robot constructor. */
    public static void ensureLoaded() { /* touching this class triggers the static block */ }

    private final GbmTreeEnsemble model = STATIC_MODEL;
    private final Feature[] featureIndex = STATIC_FEATURE_INDEX;
    private final double[] inputBuffer = STATIC_MODEL != null ? new double[MovementData.FEATURE_NAMES.length] : null;

    /** Tree budget — set externally by Autopilot's TickBudget. */
    private int maxTrees = 200;

    /** Whether the binary model was successfully loaded (vs heuristic fallback). */
    public boolean isModelLoaded() { return model != null; }

    /** Set the maximum trees to evaluate per tick (for CPU throttling). */
    public void setMaxTrees(int n) { maxTrees = n; }

    /** @deprecated Use static ensureLoaded() instead. Kept for API compatibility. */
    public void loadModel() { /* no-op: model loaded statically */ }

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
        if (model != null) {
            FeatureMapping.extract(wb, featureIndex, inputBuffer);
            double pred = model.predictRaw(inputBuffer, maxTrees);
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

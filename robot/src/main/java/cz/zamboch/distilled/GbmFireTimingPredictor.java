package cz.zamboch.distilled;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.ml.FeatureMapping;
import cz.zamboch.autopilot.core.ml.GbmTreeEnsemble;

/**
 * Distilled GBM fire timing predictor. Predicts probability the opponent
 * fires within the next 3 ticks using a 200-tree XGBoost classifier
 * loaded from a binary resource.
 *
 * <p>AUC=0.773 (compact model, 200 trees × depth 6).</p>
 */
public final class GbmFireTimingPredictor implements IInGameFeatures {

    // Static model loaded at class-load time (during robot instantiation)
    private static final GbmTreeEnsemble STATIC_MODEL;
    private static final Feature[] STATIC_FEATURE_INDEX;
    static {
        GbmTreeEnsemble m = null;
        Feature[] fi = null;
        try {
            m = FireTimingData.load();
            fi = FeatureMapping.buildIndex(FireTimingData.FEATURE_NAMES);
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
    private final double[] inputBuffer = STATIC_MODEL != null ? new double[FireTimingData.FEATURE_NAMES.length] : null;

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
                Feature.PREDICTED_OPPONENT_FIRES_3,
                Feature.PREDICTED_OPPONENT_FIRES_3_CONFIDENCE
        };
    }

    @Override
    public Feature[] getDependencies() {
        return new Feature[]{Feature.OPPONENT_INFERRED_GUN_HEAT, Feature.DISTANCE_WMEAN};
    }

    @Override
    public void process(Whiteboard wb) {
        if (model != null) {
            FeatureMapping.extract(wb, featureIndex, inputBuffer);
            double raw = model.predictRaw(inputBuffer, maxTrees);
            double prob = GbmTreeEnsemble.sigmoid(raw);
            wb.setFeature(Feature.PREDICTED_OPPONENT_FIRES_3, prob);
            wb.setFeature(Feature.PREDICTED_OPPONENT_FIRES_3_CONFIDENCE, 0.8);
        } else {
            double gunHeat = wb.hasFeature(Feature.OPPONENT_INFERRED_GUN_HEAT)
                    ? wb.getFeature(Feature.OPPONENT_INFERRED_GUN_HEAT) : 1.0;
            double prob = gunHeat <= 0.0 ? 0.5 : 0.07;
            wb.setFeature(Feature.PREDICTED_OPPONENT_FIRES_3, prob);
            wb.setFeature(Feature.PREDICTED_OPPONENT_FIRES_3_CONFIDENCE, 0.0);
        }
    }
}

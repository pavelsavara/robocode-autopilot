package cz.zamboch.distilled;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.ml.FeatureMapping;
import cz.zamboch.autopilot.core.ml.GbmTreeEnsemble;

/**
 * Distilled GBM fire power predictor. Predicts opponent fire power
 * using a 200-tree XGBoost model loaded from a binary resource.
 *
 * <p>R²=0.906, MAE=0.148 (compact model, 200 trees × depth 6).</p>
 */
public final class GbmFirePowerPredictor implements IInGameFeatures {

    // Static model loaded at class-load time (during robot instantiation)
    private static final GbmTreeEnsemble STATIC_MODEL;
    private static final Feature[] STATIC_FEATURE_INDEX;
    static {
        GbmTreeEnsemble m = null;
        Feature[] fi = null;
        try {
            m = FirePowerData.load();
            fi = FeatureMapping.buildIndex(FirePowerData.FEATURE_NAMES);
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
    private final double[] inputBuffer = STATIC_MODEL != null ? new double[FirePowerData.FEATURE_NAMES.length] : null;

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
                Feature.PREDICTED_FIRE_POWER,
                Feature.PREDICTED_FIRE_POWER_CONFIDENCE
        };
    }

    @Override
    public Feature[] getDependencies() {
        return new Feature[]{Feature.OPPONENT_ENERGY, Feature.ENERGY_RATIO,
                Feature.DISTANCE_WMEAN};
    }

    @Override
    public void process(Whiteboard wb) {
        if (model != null) {
            FeatureMapping.extract(wb, featureIndex, inputBuffer);
            double pred = model.predictRaw(inputBuffer, maxTrees);
            pred = Math.max(0.1, Math.min(3.0, pred));
            wb.setFeature(Feature.PREDICTED_FIRE_POWER, pred);
            wb.setFeature(Feature.PREDICTED_FIRE_POWER_CONFIDENCE, 0.9);
        } else {
            // Fallback heuristic
            double opponentEnergy = wb.getOpponentEnergy();
            double power = opponentEnergy > 50 ? 2.0 : opponentEnergy > 20 ? 1.5 : 1.0;
            wb.setFeature(Feature.PREDICTED_FIRE_POWER, power);
            wb.setFeature(Feature.PREDICTED_FIRE_POWER_CONFIDENCE, 0.0);
        }
    }
}

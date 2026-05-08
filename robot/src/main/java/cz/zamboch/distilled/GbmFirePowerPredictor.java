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

    private GbmTreeEnsemble model;
    private Feature[] featureIndex;
    private double[] inputBuffer;
    private boolean loaded;

    /** Tree budget — set externally by Autopilot's TickBudget. */
    private int maxTrees = 200;

    /** Whether the binary model was successfully loaded (vs heuristic fallback). */
    public boolean isModelLoaded() { return model != null; }

    /** Set the maximum trees to evaluate per tick (for CPU throttling). */
    public void setMaxTrees(int n) { maxTrees = n; }

    /** Eagerly load the model. Call once at init instead of lazy-loading on first process(). */
    public void loadModel() {
        if (!loaded) {
            try {
                model = FirePowerData.load();
                featureIndex = FeatureMapping.buildIndex(FirePowerData.FEATURE_NAMES);
                inputBuffer = new double[FirePowerData.FEATURE_NAMES.length];
                loaded = true;
            } catch (Exception e) {
                loaded = true; // don't retry
            }
        }
    }

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
        if (!loaded) {
            loadModel();
        }

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

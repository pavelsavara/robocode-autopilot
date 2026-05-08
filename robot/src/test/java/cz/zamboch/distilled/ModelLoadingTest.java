package cz.zamboch.distilled;

import cz.zamboch.autopilot.core.ml.GbmTreeEnsemble;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that all 3 GBM model binary resources can be loaded and
 * produce predictions. Catches resource-not-found, corrupt binary,
 * and constructor mismatches that the smoke test would silently swallow
 * (predictors fall back to heuristics on load failure).
 */
class ModelLoadingTest {

    @Test
    void firePowerModelLoads() {
        GbmTreeEnsemble model = FirePowerData.load();
        assertNotNull(model, "FirePower model should load from fire-power.bin");
        assertEquals(FirePowerData.N_TREES, model.getNumTrees());
        assertEquals(FirePowerData.N_CLASSES, model.getNumClasses());

        // Predict on a zero-vector — should return something in [0, 5]
        double[] input = new double[FirePowerData.FEATURE_NAMES.length];
        double pred = model.predictRaw(input);
        assertTrue(Double.isFinite(pred), "Prediction should be finite, got: " + pred);
    }

    @Test
    void fireTimingModelLoads() {
        GbmTreeEnsemble model = FireTimingData.load();
        assertNotNull(model, "FireTiming model should load from fire-timing.bin");
        assertEquals(FireTimingData.N_TREES, model.getNumTrees());

        double[] input = new double[FireTimingData.FEATURE_NAMES.length];
        double raw = model.predictRaw(input);
        assertTrue(Double.isFinite(raw), "Raw prediction should be finite, got: " + raw);

        double prob = GbmTreeEnsemble.sigmoid(raw);
        assertTrue(prob >= 0 && prob <= 1, "Sigmoid output should be in [0,1], got: " + prob);
    }

    @Test
    void movementModelLoads() {
        GbmTreeEnsemble model = MovementData.load();
        assertNotNull(model, "Movement model should load from movement.bin");
        assertEquals(MovementData.N_TREES, model.getNumTrees());

        double[] input = new double[MovementData.FEATURE_NAMES.length];
        double pred = model.predictRaw(input);
        assertTrue(Double.isFinite(pred), "Prediction should be finite, got: " + pred);
    }

    @Test
    void featureNamesMappable() {
        // All feature names in each model should be recognized by FeatureMapping
        cz.zamboch.autopilot.core.Feature[] fpIndex =
                cz.zamboch.autopilot.core.ml.FeatureMapping.buildIndex(FirePowerData.FEATURE_NAMES);
        for (int i = 0; i < fpIndex.length; i++) {
            assertNotNull(fpIndex[i],
                    "FirePower feature '" + FirePowerData.FEATURE_NAMES[i]
                            + "' at index " + i + " has no Feature mapping");
        }

        cz.zamboch.autopilot.core.Feature[] ftIndex =
                cz.zamboch.autopilot.core.ml.FeatureMapping.buildIndex(FireTimingData.FEATURE_NAMES);
        for (int i = 0; i < ftIndex.length; i++) {
            assertNotNull(ftIndex[i],
                    "FireTiming feature '" + FireTimingData.FEATURE_NAMES[i]
                            + "' at index " + i + " has no Feature mapping");
        }

        cz.zamboch.autopilot.core.Feature[] mvIndex =
                cz.zamboch.autopilot.core.ml.FeatureMapping.buildIndex(MovementData.FEATURE_NAMES);
        for (int i = 0; i < mvIndex.length; i++) {
            assertNotNull(mvIndex[i],
                    "Movement feature '" + MovementData.FEATURE_NAMES[i]
                            + "' at index " + i + " has no Feature mapping");
        }
    }
}

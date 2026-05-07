package cz.zamboch.distilled;

import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.predictors.FingerprintResult;
import cz.zamboch.autopilot.core.predictors.IFingerprintPredictor;

/**
 * Distilled GBM fingerprint predictor skeleton.
 * Phase 8 will generate {@code GbmFingerprintModel.java} with the actual
 * tree if/else code from LightGBM export.
 *
 * <p>Classifies the opponent into one of N=20 known bot families based on
 * 18 behavioral features. Output: top class ID + probability distribution.</p>
 *
 * <p>Until the generated model is available, returns "unknown" (class 0)
 * with uniform probability.</p>
 */
public final class GbmFingerprint implements IFingerprintPredictor {

    /** Number of known opponent classes. */
    private static final int NUM_CLASSES = 20;

    private final FingerprintResult result = new FingerprintResult(NUM_CLASSES);

    @Override
    public FingerprintResult predict(Whiteboard wb) {
        // Phase 8: extract 18 features, call GbmFingerprintModel.predict(features)
        // For now, return uniform distribution (unknown opponent)
        double uniform = 1.0 / NUM_CLASSES;
        double[] probs = result.probabilities;
        for (int i = 0; i < probs.length; i++) {
            probs[i] = uniform;
        }
        result.set(0); // unknown
        return result;
    }

    @Override
    public void predictInto(Whiteboard wb, FingerprintResult out) {
        FingerprintResult r = predict(wb);
        out.set(r.classId);
        System.arraycopy(r.probabilities, 0, out.probabilities, 0,
                Math.min(r.probabilities.length, out.probabilities.length));
    }

    @Override
    public double confidence(Whiteboard wb) {
        return 0.0;
    }

    @Override
    public String name() {
        return "gbm-fingerprint";
    }
}

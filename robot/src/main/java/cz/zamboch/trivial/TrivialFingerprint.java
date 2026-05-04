package cz.zamboch.trivial;

import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.predictors.FingerprintResult;
import cz.zamboch.autopilot.core.predictors.IFingerprintPredictor;

/**
 * Trivial fingerprint predictor — class 0, uniform probabilities.
 */
public final class TrivialFingerprint implements IFingerprintPredictor {

    private static final int NUM_CLASSES = 44;

    @Override
    public FingerprintResult predict(Whiteboard wb) {
        double[] probs = new double[NUM_CLASSES];
        double uniform = 1.0 / NUM_CLASSES;
        for (int i = 0; i < NUM_CLASSES; i++) {
            probs[i] = uniform;
        }
        return new FingerprintResult(0, probs);
    }

    @Override
    public double confidence(Whiteboard wb) {
        return 1.0 / NUM_CLASSES;
    }

    @Override
    public String name() {
        return "trivial-fingerprint";
    }
}

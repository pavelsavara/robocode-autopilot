package cz.zamboch.autopilot.core.predictors;

import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Bot fingerprint predictor — classifies the opponent into one of K
 * known behavioral archetypes and returns class probabilities.
 */
public interface IFingerprintPredictor extends IPredictor<FingerprintResult> {

    /**
     * Zero-allocation variant: write into a pre-allocated result.
     * Default implementation delegates to {@link #predict} and copies.
     *
     * @param wb current state
     * @param out pre-allocated result — classId is set, probabilities overwritten
     */
    default void predictInto(Whiteboard wb, FingerprintResult out) {
        FingerprintResult r = predict(wb);
        out.set(r.classId);
        System.arraycopy(r.probabilities, 0, out.probabilities, 0,
                Math.min(r.probabilities.length, out.probabilities.length));
    }
}

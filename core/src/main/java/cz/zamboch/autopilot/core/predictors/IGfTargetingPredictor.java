package cz.zamboch.autopilot.core.predictors;

import cz.zamboch.autopilot.core.Whiteboard;

/**
 * GF targeting predictor — produces a 61-bin guess-factor distribution.
 * Each bin represents a GF in [-1, +1] with bin width 2/61 ≈ 0.0328.
 */
public interface IGfTargetingPredictor extends IPredictor<double[]> {

    /** Number of GF bins. */
    int NUM_BINS = 61;

    /**
     * Zero-allocation variant: write distribution into pre-allocated buffer.
     * Default implementation delegates to {@link #predict} and copies.
     *
     * @param wb current state
     * @param out pre-allocated buffer of length {@link #NUM_BINS}
     */
    default void predictInto(Whiteboard wb, double[] out) {
        double[] result = predict(wb);
        System.arraycopy(result, 0, out, 0, Math.min(result.length, out.length));
    }
}

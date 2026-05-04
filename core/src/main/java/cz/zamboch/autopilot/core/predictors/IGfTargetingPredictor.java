package cz.zamboch.autopilot.core.predictors;

/**
 * GF targeting predictor — produces a 61-bin guess-factor distribution.
 * Each bin represents a GF in [-1, +1] with bin width 2/61 ≈ 0.0328.
 */
public interface IGfTargetingPredictor extends IPredictor<double[]> {
}

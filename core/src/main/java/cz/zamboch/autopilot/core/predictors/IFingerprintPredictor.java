package cz.zamboch.autopilot.core.predictors;

/**
 * Bot fingerprint predictor — classifies the opponent into one of K
 * known behavioral archetypes and returns class probabilities.
 */
public interface IFingerprintPredictor extends IPredictor<FingerprintResult> {
}

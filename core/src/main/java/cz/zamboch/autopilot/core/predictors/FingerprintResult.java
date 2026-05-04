package cz.zamboch.autopilot.core.predictors;

/**
 * Result of bot fingerprint classification.
 */
public final class FingerprintResult {

    /** Predicted class index (0..numClasses-1). */
    public final int classId;

    /** Probability per class (sums to 1). */
    public final double[] probabilities;

    public FingerprintResult(int classId, double[] probabilities) {
        this.classId = classId;
        this.probabilities = probabilities;
    }
}

package cz.zamboch.autopilot.core.predictors;

/**
 * Result of bot fingerprint classification. Mutable — can be reused
 * via {@link #set} to avoid per-call allocation.
 */
public final class FingerprintResult {

    /** Predicted class index (0..numClasses-1). */
    public int classId;

    /** Probability per class (sums to 1). Pre-allocated, overwritten in place. */
    public final double[] probabilities;

    public FingerprintResult(int numClasses) {
        this.probabilities = new double[numClasses];
    }

    public FingerprintResult(int classId, double[] probabilities) {
        this.classId = classId;
        this.probabilities = probabilities;
    }

    /** Overwrite classId. Caller writes directly into {@link #probabilities}. */
    public void set(int classId) {
        this.classId = classId;
    }
}

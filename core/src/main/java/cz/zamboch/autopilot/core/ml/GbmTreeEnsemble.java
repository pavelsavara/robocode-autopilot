package cz.zamboch.autopilot.core.ml;

/**
 * Flat-array GBM tree ensemble interpreter. Evaluates XGBoost/LightGBM
 * tree models stored as compact contiguous arrays — no generated if/else code,
 * no compiler size limits, no runtime dependencies.
 *
 * <h3>Data layout</h3>
 * <p>All trees are flattened into contiguous parallel arrays. Each tree's
 * nodes start at {@code offsets[t]} and span {@code treeSizes[t]} entries.
 * Within each tree, nodes use local indices (0-based).</p>
 *
 * <p>For regression (fire power, movement): output = base_score + sum(tree outputs).</p>
 * <p>For binary classification (fire timing): output = sigmoid(base_score + sum).</p>
 * <p>For multi-class: one set of trees per class, softmax over sums.</p>
 *
 * <p>All arrays are pre-allocated at construction. Zero per-tick allocation.</p>
 */
public final class GbmTreeEnsemble {

    private final int nTrees;
    private final int[] offsets;         // start index of each tree in the flat arrays
    private final int[] featureIndex;    // split feature (−1 = leaf)
    private final double[] threshold;
    private final int[] leftChild;       // local child index within the tree
    private final int[] rightChild;
    private final double[] leafValue;
    private final double baseScore;
    private final int nClasses;          // 1 for regression/binary, N for multi-class

    /**
     * Construct from variable-length tree data.
     *
     * @param nTrees       number of trees
     * @param offsets      start offset of each tree in flat arrays, length = nTrees
     * @param featureIndex split feature (−1 = leaf)
     * @param threshold    split threshold
     * @param leftChild    left child (local index)
     * @param rightChild   right child (local index)
     * @param leafValue    leaf output
     * @param baseScore    global bias
     * @param nClasses     1 for regression/binary, N for multi-class
     */
    public GbmTreeEnsemble(int nTrees, int[] offsets,
                           int[] featureIndex, double[] threshold,
                           int[] leftChild, int[] rightChild,
                           double[] leafValue, double baseScore,
                           int nClasses) {
        if (featureIndex.length == 0) {
            throw new IllegalArgumentException("Empty tree data");
        }
        this.nTrees = nTrees;
        this.offsets = offsets;
        this.featureIndex = featureIndex;
        this.threshold = threshold;
        this.leftChild = leftChild;
        this.rightChild = rightChild;
        this.leafValue = leafValue;
        this.baseScore = baseScore;
        this.nClasses = nClasses;
    }

    /** Number of trees in this ensemble. */
    public int getNumTrees() { return nTrees; }
    /** Number of output classes (1 = regression/binary). */
    public int getNumClasses() { return nClasses; }

    /**
     * Validate that the feature array length matches what the model expects.
     * Call once at startup with the FEATURE_NAMES array.
     *
     * @param expectedFeatures number of features the model was trained with
     * @param modelName        human-readable name for error messages
     * @throws IllegalStateException if any tree references a feature index >= expectedFeatures
     */
    public void validateFeatureDimension(int expectedFeatures, String modelName) {
        for (int i = 0; i < featureIndex.length; i++) {
            if (featureIndex[i] >= expectedFeatures) {
                throw new IllegalStateException(
                    modelName + ": tree node " + i + " references feature index "
                    + featureIndex[i] + " but model only has " + expectedFeatures
                    + " features. Model and FEATURE_NAMES are out of sync.");
            }
        }
    }

    /**
     * Predict using ALL trees (full accuracy).
     */
    public double predictRaw(double[] features) {
        return predictRaw(features, nTrees);
    }

    /**
     * Predict using only the first {@code maxTrees} trees.
     * Truncating to fewer trees is the standard way to trade accuracy for speed
     * in boosted ensembles — early trees capture most variance.
     */
    public double predictRaw(double[] features, int maxTrees) {
        int limit = Math.min(maxTrees, nTrees);
        double sum = baseScore;
        for (int t = 0; t < limit; t++) {
            sum += evaluateTree(t, features);
        }
        return sum;
    }

    /**
     * Predict multi-class raw scores into pre-allocated output array.
     * Apply softmax externally if probabilities are needed.
     */
    public void predictMultiClassRaw(double[] features, double[] out) {
        for (int c = 0; c < nClasses; c++) {
            out[c] = baseScore;
        }
        for (int t = 0; t < nTrees; t++) {
            int cls = t % nClasses;
            out[cls] += evaluateTree(t, features);
        }
    }

    /** Evaluate a single tree, returning the leaf value. */
    private double evaluateTree(int treeIndex, double[] features) {
        int base = offsets[treeIndex];
        int node = 0;
        while (true) {
            int idx = base + node;
            int feat = featureIndex[idx];
            if (feat < 0) {
                return leafValue[idx];
            }
            double fval = feat < features.length ? features[feat] : Double.NaN;
            if (Double.isNaN(fval) || fval < threshold[idx]) {
                node = leftChild[idx];
            } else {
                node = rightChild[idx];
            }
        }
    }

    /** Apply sigmoid: 1 / (1 + exp(-x)). */
    public static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    /** Apply softmax in-place. */
    public static void softmax(double[] values) {
        double max = values[0];
        for (int i = 1; i < values.length; i++) {
            if (values[i] > max) max = values[i];
        }
        double sum = 0;
        for (int i = 0; i < values.length; i++) {
            values[i] = Math.exp(values[i] - max);
            sum += values[i];
        }
        if (sum > 0) {
            for (int i = 0; i < values.length; i++) {
                values[i] /= sum;
            }
        }
    }
}

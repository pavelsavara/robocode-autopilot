package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Computes 20-tick sliding window statistics (mean and std) for 10 base features.
 * These are the single most important innovation for all ML tasks — without them,
 * no model exceeds R²=0.07 on movement or R²=0.572 on fire power.
 *
 * <p>Uses pre-allocated ring buffers. Zero per-tick allocation.
 * Computes incrementally: O(1) per tick using running sum and sum-of-squares.</p>
 *
 * <p>Not final — pipeline subclass can add CSV output.</p>
 */
public class WindowFeatures implements IInGameFeatures {

    /** Window size in ticks. */
    private static final int WINDOW = 20;
    /** Minimum ticks before stats are meaningful. */
    private static final int MIN_TICKS = 5;
    /** Number of base features tracked. */
    private static final int N_FEATURES = 10;

    /** Base features to track (input). */
    private static final Feature[] BASE_FEATURES = {
            Feature.DISTANCE,
            Feature.BEARING_TO_OPPONENT_ABS,
            Feature.OPPONENT_DIST_TO_WALL_MIN,
            Feature.OUR_GUN_HEAT,
            Feature.TICKS_SINCE_SCAN,
            Feature.OPPONENT_ENERGY,
            Feature.OUR_X,
            Feature.OUR_Y,
            Feature.OUR_HEADING,
            Feature.OUR_VELOCITY,
    };

    /** Output features: mean then std for each base feature. */
    private static final Feature[] MEAN_FEATURES = {
            Feature.DISTANCE_WMEAN,
            Feature.BEARING_TO_OPPONENT_ABS_WMEAN,
            Feature.OPPONENT_DIST_TO_WALL_MIN_WMEAN,
            Feature.OUR_GUN_HEAT_WMEAN,
            Feature.TICKS_SINCE_SCAN_WMEAN,
            Feature.OPPONENT_ENERGY_WMEAN,
            Feature.OUR_X_WMEAN,
            Feature.OUR_Y_WMEAN,
            Feature.OUR_HEADING_WMEAN,
            Feature.OUR_VELOCITY_WMEAN,
    };

    private static final Feature[] STD_FEATURES = {
            Feature.DISTANCE_WSTD,
            Feature.BEARING_TO_OPPONENT_ABS_WSTD,
            Feature.OPPONENT_DIST_TO_WALL_MIN_WSTD,
            Feature.OUR_GUN_HEAT_WSTD,
            Feature.TICKS_SINCE_SCAN_WSTD,
            Feature.OPPONENT_ENERGY_WSTD,
            Feature.OUR_X_WSTD,
            Feature.OUR_Y_WSTD,
            Feature.OUR_HEADING_WSTD,
            Feature.OUR_VELOCITY_WSTD,
    };

    private static final Feature[] ALL_OUTPUTS;
    static {
        ALL_OUTPUTS = new Feature[N_FEATURES * 2];
        for (int i = 0; i < N_FEATURES; i++) {
            ALL_OUTPUTS[i * 2] = MEAN_FEATURES[i];
            ALL_OUTPUTS[i * 2 + 1] = STD_FEATURES[i];
        }
    }

    // Ring buffers for each base feature
    private final double[][] buffers = new double[N_FEATURES][WINDOW];
    private int head = 0;
    private int count = 0;

    // Running sums for O(1) incremental stats
    private final double[] sums = new double[N_FEATURES];
    private final double[] sumSqs = new double[N_FEATURES];

    public Feature[] getOutputFeatures() { return ALL_OUTPUTS; }

    public Feature[] getDependencies() { return BASE_FEATURES; }

    public void process(Whiteboard wb) {
        // Read base features (use 0 if not set yet)
        for (int i = 0; i < N_FEATURES; i++) {
            double val = wb.hasFeature(BASE_FEATURES[i])
                    ? wb.getFeature(BASE_FEATURES[i]) : 0.0;

            // Remove oldest value from running stats
            if (count >= WINDOW) {
                double old = buffers[i][head];
                sums[i] -= old;
                sumSqs[i] -= old * old;
            }

            // Add new value
            buffers[i][head] = val;
            sums[i] += val;
            sumSqs[i] += val * val;
        }

        // Advance ring buffer
        head = (head + 1) % WINDOW;
        if (count < WINDOW) count++;

        // Emit stats (using sample std with Bessel's correction to match
        // Python pandas rolling().std() which uses ddof=1 by default)
        if (count >= MIN_TICKS) {
            double invN = 1.0 / count;
            for (int i = 0; i < N_FEATURES; i++) {
                double mean = sums[i] * invN;
                double variance = (count > 1)
                        ? (sumSqs[i] - count * mean * mean) / (count - 1)
                        : 0;
                double std = variance > 0 ? Math.sqrt(variance) : 0;
                wb.setFeature(MEAN_FEATURES[i], mean);
                wb.setFeature(STD_FEATURES[i], std);
            }
        }
    }

    /** Reset window state for new round. */
    public void reset() {
        head = 0;
        count = 0;
        for (int i = 0; i < N_FEATURES; i++) {
            sums[i] = 0;
            sumSqs[i] = 0;
            for (int j = 0; j < WINDOW; j++) {
                buffers[i][j] = 0;
            }
        }
    }
}

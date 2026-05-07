package cz.zamboch.autopilot.core.gun;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.IGunStrategy;
import cz.zamboch.autopilot.core.util.RoboMath;

import java.util.Random;

/**
 * Visit Count Statistics gun — maintains a 61-bin GF histogram segmented
 * by distance (3 bins) and lateral velocity direction (2 bins).
 *
 * <p>Fires probabilistically: bins are smoothed with a Gaussian kernel
 * (σ=1.5 bins) then selected via weighted random sampling. This makes
 * our targeting harder to profile and avoids always firing at a single
 * noisy peak, while still concentrating fire near the most-visited GF.</p>
 *
 * <p>The histogram is stored in {@link Whiteboard#getGunVcsSegment} and
 * updated at wave-break time by Whiteboard's prunePassedWaves().</p>
 */
public final class VcsGun implements IGunStrategy {

    /** Gaussian kernel σ for bin smoothing (in bins, not GF). */
    private static final double SMOOTH_SIGMA = 1.5;
    /** Pre-computed kernel: smooth ±4 bins (covers >99% of Gaussian). */
    private static final int KERNEL_HALF = 4;

    private final Random rng = new Random();
    /** Scratch buffer for smoothed density — reused each call. */
    private final double[] smoothed = new double[Whiteboard.VCS_BINS];

    @Override
    public double getFireAngle(Whiteboard wb) {
        double bearing = wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS);
        if (!wb.hasFeature(Feature.MEA_FOR_OUR_BULLET)
                || !wb.hasFeature(Feature.OPPONENT_LATERAL_DIRECTION)
                || !wb.hasFeature(Feature.DISTANCE)) {
            return bearing;
        }

        double mea = wb.getFeature(Feature.MEA_FOR_OUR_BULLET);
        double distance = wb.getFeature(Feature.DISTANCE);
        int latDir = (int) wb.getFeature(Feature.OPPONENT_LATERAL_DIRECTION);
        if (latDir == 0) latDir = 1;

        int segment = Whiteboard.vcsSegment(distance, latDir);
        int[] hist = wb.getGunVcsSegment(segment);

        int selectedBin = sampleSmoothed(hist);

        double gf = Whiteboard.binToGf(selectedBin);
        double offset = gf * mea * latDir;
        return RoboMath.normalAbsoluteAngle(bearing + offset);
    }

    /**
     * Smooth the raw histogram with a Gaussian kernel, then sample a bin
     * with probability proportional to the smoothed density.
     * Falls back to GF=0 (bin 30) when the histogram is empty.
     */
    private int sampleSmoothed(int[] hist) {
        // Gaussian-smooth into scratch buffer
        double total = 0;
        for (int i = 0; i < Whiteboard.VCS_BINS; i++) {
            double sum = 0;
            for (int k = -KERNEL_HALF; k <= KERNEL_HALF; k++) {
                int j = i + k;
                if (j >= 0 && j < Whiteboard.VCS_BINS) {
                    double w = Math.exp(-0.5 * (k * k) / (SMOOTH_SIGMA * SMOOTH_SIGMA));
                    sum += hist[j] * w;
                }
            }
            smoothed[i] = sum;
            total += sum;
        }

        if (total <= 0) {
            // No observations yet — fall back to GF=0
            return Whiteboard.VCS_BINS / 2;
        }

        // Weighted random selection
        double r = rng.nextDouble() * total;
        double cumulative = 0;
        for (int i = 0; i < Whiteboard.VCS_BINS; i++) {
            cumulative += smoothed[i];
            if (cumulative >= r) {
                return i;
            }
        }
        return Whiteboard.VCS_BINS - 1;
    }

    @Override
    public double getConfidence(Whiteboard wb) {
        if (!wb.hasFeature(Feature.DISTANCE)
                || !wb.hasFeature(Feature.OPPONENT_LATERAL_DIRECTION)) {
            return 0.0;
        }
        double distance = wb.getFeature(Feature.DISTANCE);
        int latDir = (int) wb.getFeature(Feature.OPPONENT_LATERAL_DIRECTION);
        if (latDir == 0) latDir = 1;

        int segment = Whiteboard.vcsSegment(distance, latDir);
        int[] hist = wb.getGunVcsSegment(segment);

        int total = 0;
        for (int v : hist) total += v;
        return Math.min(1.0, total / 50.0);
    }

    @Override
    public String getName() {
        return "vcs";
    }
}

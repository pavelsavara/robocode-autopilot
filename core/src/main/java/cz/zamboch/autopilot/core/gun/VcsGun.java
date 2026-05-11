package cz.zamboch.autopilot.core.gun;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.IGunStrategy;
import cz.zamboch.autopilot.core.util.RoboMath;

/**
 * Visit Count Statistics gun (peak-firing mode) — fires at the GF bin
 * with the highest smoothed density. This maximizes accuracy against
 * opponents with a consistent aiming pattern.
 *
 * <p>For the anti-profiling variant that samples probabilistically,
 * see {@link VcsSamplingGun}.</p>
 */
public final class VcsGun implements IGunStrategy {

    /** Gaussian kernel σ for bin smoothing (in bins, not GF). */
    private static final double SMOOTH_SIGMA = 1.5;
    /** Pre-computed kernel: smooth ±4 bins (covers >99% of Gaussian). */
    private static final int KERNEL_HALF = 4;

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

        int selectedBin = peakSmoothed(hist);

        double gf = Whiteboard.binToGf(selectedBin);
        double offset = gf * mea * latDir;
        return RoboMath.normalAbsoluteAngle(bearing + offset);
    }

    /**
     * Smooth the raw histogram with a Gaussian kernel, then return the bin
     * with the highest smoothed density (argmax / peak-firing).
     * Falls back to GF=0 (bin 30) when the histogram is empty.
     */
    private int peakSmoothed(int[] hist) {
        double bestVal = -1;
        int bestBin = Whiteboard.VCS_BINS / 2;
        boolean hasData = false;
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
            if (sum > 0) hasData = true;
            if (sum > bestVal) {
                bestVal = sum;
                bestBin = i;
            }
        }

        if (!hasData) {
            return Whiteboard.VCS_BINS / 2;
        }
        return bestBin;
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

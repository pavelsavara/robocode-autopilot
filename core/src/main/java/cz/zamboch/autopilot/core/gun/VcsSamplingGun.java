package cz.zamboch.autopilot.core.gun;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.IGunStrategy;
import cz.zamboch.autopilot.core.util.RoboMath;

import java.util.Random;

/**
 * Visit Count Statistics gun (sampling mode) — fires probabilistically
 * from the smoothed GF histogram. This makes our targeting harder to
 * profile by adaptive opponents that model our aim pattern.
 *
 * <p>Competes in the VirtualGunManager alongside {@link VcsGun} (peak-firing).
 * The VGM will select whichever variant has the higher hit rate — peak-firing
 * wins against non-adaptive opponents, sampling wins against profilers.</p>
 */
public final class VcsSamplingGun implements IGunStrategy {

    private static final double SMOOTH_SIGMA = 1.5;
    private static final int KERNEL_HALF = 4;

    private final Random rng = new Random();
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

    /** Smooth histogram then select a bin via weighted random sampling. */
    private int sampleSmoothed(int[] hist) {
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
            return Whiteboard.VCS_BINS / 2;
        }

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
        // Slightly lower confidence than VcsGun (peak) so peak wins ties
        return Math.min(0.95, total / 50.0);
    }

    @Override
    public String getName() {
        return "vcs-sampling";
    }
}

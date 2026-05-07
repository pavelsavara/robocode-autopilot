package cz.zamboch.autopilot.core.gun;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.IGunStrategy;
import cz.zamboch.autopilot.core.util.RoboMath;

/**
 * Visit Count Statistics gun — maintains a 61-bin GF histogram segmented
 * by distance (3 bins) and lateral velocity direction (2 bins).
 * Fires at the peak bin.
 *
 * <p>The histogram is stored in {@link Whiteboard#getGunVcsSegment} and
 * updated at wave-break time by Whiteboard's prunePassedWaves().</p>
 */
public final class VcsGun implements IGunStrategy {

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

        // Find peak bin (default to GF=0 = bin 30)
        int peakBin = Whiteboard.VCS_BINS / 2;
        int peakVal = 0;
        for (int i = 0; i < hist.length; i++) {
            if (hist[i] > peakVal) {
                peakVal = hist[i];
                peakBin = i;
            }
        }

        double gf = Whiteboard.binToGf(peakBin);
        double offset = gf * mea * latDir;
        return RoboMath.normalAbsoluteAngle(bearing + offset);
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

        // Confidence scales with total observations in this segment
        int total = 0;
        for (int v : hist) total += v;
        // Ramp from 0 to 1 over ~50 observations
        return Math.min(1.0, total / 50.0);
    }

    @Override
    public String getName() {
        return "vcs";
    }
}

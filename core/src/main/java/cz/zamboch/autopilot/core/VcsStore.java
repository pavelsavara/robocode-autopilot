package cz.zamboch.autopilot.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Visit Count Stats histogram: distance × lateralVelocity × lag-1 × GF bins.
 * Compact int[5][5][3][31] = 2325 bins total.
 * <p>
 * The lag-1 axis segments the developing GF of the most-recent in-flight wave
 * (recent-dodge context). When a lag-1 slice has no samples yet, lookups fall
 * back to the aggregate over all lag-1 slices, so cold-start behaviour matches
 * the previous 2D gun.
 * <p>
 * Implements {@link IOnlineModel} so it can be used interchangeably with
 * future targeting models (pattern matchers, neural nets, etc.).
 */
public final class VcsStore implements IOnlineModel {
    private final int[][][][] data = new int[GuessFactor.DISTANCE_SEGMENTS][GuessFactor.LAT_VEL_SEGMENTS][GuessFactor.LAG1_SEGMENTS][GuessFactor.NUM_BINS];

    /**
     * Increment the bin for a resolved wave. The lag-1 axis is addressed by the
     * raw developing guess factor; the segmentation happens here, inside the VCS.
     */
    public void increment(int distSeg, int latVelSeg, double lag1Gf, int binIndex) {
        data[distSeg][latVelSeg][GuessFactor.lag1Segment(lag1Gf)][binIndex]++;
    }

    /**
     * Get the bin index with the highest visit count for a segment pair plus the
     * lag-1 slice selected by the raw developing guess factor (segmented here).
     * Falls back to the aggregate across all lag-1 slices when the requested
     * slice is empty, and returns ZERO_BIN if the whole segment pair is empty.
     */
    public int getBestBin(int distSeg, int latVelSeg, double lag1Gf) {
        int lag1Seg = GuessFactor.lag1Segment(lag1Gf);
        int[] bins = data[distSeg][latVelSeg][lag1Seg];
        int bestIndex = GuessFactor.ZERO_BIN;
        int bestCount = 0;
        for (int i = 0; i < bins.length; i++) {
            if (bins[i] > bestCount) {
                bestCount = bins[i];
                bestIndex = i;
            }
        }
        if (bestCount > 0) {
            return bestIndex;
        }
        // Sparse lag-1 slice: fall back to the aggregate over all lag-1 slices.
        int[][] slices = data[distSeg][latVelSeg];
        for (int b = 0; b < GuessFactor.NUM_BINS; b++) {
            int sum = 0;
            for (int s = 0; s < GuessFactor.LAG1_SEGMENTS; s++) {
                sum += slices[s][b];
            }
            if (sum > bestCount) {
                bestCount = sum;
                bestIndex = b;
            }
        }
        return bestIndex;
    }

    /** Get the visit count for a specific bin (lag-1 slice chosen by raw GF). */
    public int getCount(int distSeg, int latVelSeg, double lag1Gf, int binIndex) {
        return data[distSeg][latVelSeg][GuessFactor.lag1Segment(lag1Gf)][binIndex];
    }

    /** Clear all bins to zero. */
    public void clear() {
        for (int d = 0; d < GuessFactor.DISTANCE_SEGMENTS; d++) {
            for (int l = 0; l < GuessFactor.LAT_VEL_SEGMENTS; l++) {
                for (int g = 0; g < GuessFactor.LAG1_SEGMENTS; g++) {
                    for (int b = 0; b < GuessFactor.NUM_BINS; b++) {
                        data[d][l][g][b] = 0;
                    }
                }
            }
        }
    }

    /** Write binary data to stream. */
    public void save(DataOutputStream out) throws IOException {
        for (int d = 0; d < GuessFactor.DISTANCE_SEGMENTS; d++) {
            for (int l = 0; l < GuessFactor.LAT_VEL_SEGMENTS; l++) {
                for (int g = 0; g < GuessFactor.LAG1_SEGMENTS; g++) {
                    for (int b = 0; b < GuessFactor.NUM_BINS; b++) {
                        out.writeInt(data[d][l][g][b]);
                    }
                }
            }
        }
    }

    /** Read binary data from stream. */
    public void load(DataInputStream in) throws IOException {
        for (int d = 0; d < GuessFactor.DISTANCE_SEGMENTS; d++) {
            for (int l = 0; l < GuessFactor.LAT_VEL_SEGMENTS; l++) {
                for (int g = 0; g < GuessFactor.LAG1_SEGMENTS; g++) {
                    for (int b = 0; b < GuessFactor.NUM_BINS; b++) {
                        data[d][l][g][b] = in.readInt();
                    }
                }
            }
        }
    }

    /** Total byte size of serialized data: 5 × 5 × 3 × 31 × 4 bytes. */
    public static int serializedSize() {
        return GuessFactor.DISTANCE_SEGMENTS * GuessFactor.LAT_VEL_SEGMENTS
                * GuessFactor.LAG1_SEGMENTS * GuessFactor.NUM_BINS * 4;
    }

    // ========== IOnlineModel implementation ==========

    @Override
    public double predict(Whiteboard wb, int slot) {
        double distance = wb.getOurWave(slot, OurWaveColumn.FIRE_DISTANCE);
        double latVel = wb.getOurWave(slot, OurWaveColumn.FIRE_LATERAL_VELOCITY);
        int distSeg = GuessFactor.distanceSegment(distance);
        int latVelSeg = GuessFactor.lateralVelocitySegment(
                Double.isNaN(latVel) ? 0 : latVel);
        double lag1Gf = wb.getOurWave(slot, OurWaveColumn.AIM_LAG1_GF);
        int bestBin = getBestBin(distSeg, latVelSeg, lag1Gf);
        return GuessFactor.binIndexToGf(bestBin, GuessFactor.NUM_BINS);
    }

    @Override
    public void update(Whiteboard wb, int slot, double breakGf) {
        double distance = wb.getOurWave(slot, OurWaveColumn.FIRE_DISTANCE);
        double latVel = wb.getOurWave(slot, OurWaveColumn.FIRE_LATERAL_VELOCITY);
        int distSeg = GuessFactor.distanceSegment(distance);
        int latVelSeg = GuessFactor.lateralVelocitySegment(
                Double.isNaN(latVel) ? 0 : latVel);
        double lag1Gf = wb.getOurWave(slot, OurWaveColumn.AIM_LAG1_GF);
        int binIndex = GuessFactor.gfToBinIndex(breakGf, GuessFactor.NUM_BINS);
        increment(distSeg, latVelSeg, lag1Gf, binIndex);
    }

    @Override
    public String getName() {
        return "VCS";
    }
}

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
        return getBestBinBoxed(distSeg, latVelSeg, lag1Gf, 0);
    }

    /**
     * Box-aware best bin: returns the bin maximizing the count summed over a
     * +-{@code halfWindowBins} window — i.e. where a bullet's hit band catches the
     * most opponent mass — instead of the raw mode. With {@code halfWindowBins <= 0}
     * this is the plain mode. Using the box window stops a lone GF saturation spike
     * (e.g. the +-1 reversal pile-up) from capturing the aim.
     */
    public int getBestBinBoxed(int distSeg, int latVelSeg, double lag1Gf, int halfWindowBins) {
        int[] bins = effectiveBins(distSeg, latVelSeg, lag1Gf);
        int bestIndex = GuessFactor.ZERO_BIN;
        long bestScore = 0;
        for (int i = 0; i < GuessFactor.NUM_BINS; i++) {
            long score;
            if (halfWindowBins <= 0) {
                score = bins[i];
            } else {
                score = 0;
                int lo = Math.max(0, i - halfWindowBins);
                int hi = Math.min(GuessFactor.NUM_BINS - 1, i + halfWindowBins);
                for (int j = lo; j <= hi; j++) {
                    score += bins[j];
                }
            }
            if (score > bestScore || (score == bestScore && score > 0
                    && bins[i] > bins[bestIndex])) {
                bestScore = score;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    /**
     * The bin array to read for a segment + lag-1 slice: the requested lag-1 slice
     * if it has any samples, otherwise the aggregate across all lag-1 slices (a
     * fresh array). An all-zero array yields ZERO_BIN from the callers.
     */
    private int[] effectiveBins(int distSeg, int latVelSeg, double lag1Gf) {
        int lag1Seg = GuessFactor.lag1Segment(lag1Gf);
        int[] slice = data[distSeg][latVelSeg][lag1Seg];
        for (int c : slice) {
            if (c > 0) {
                return slice;
            }
        }
        int[] agg = new int[GuessFactor.NUM_BINS];
        int[][] slices = data[distSeg][latVelSeg];
        for (int s = 0; s < GuessFactor.LAG1_SEGMENTS; s++) {
            for (int b = 0; b < GuessFactor.NUM_BINS; b++) {
                agg[b] += slices[s][b];
            }
        }
        return agg;
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
        double distance = aimOrFire(wb, slot, OurWaveColumn.AIM_DISTANCE, OurWaveColumn.FIRE_DISTANCE);
        double latVel = aimOrFire(wb, slot, OurWaveColumn.AIM_LATERAL_VELOCITY, OurWaveColumn.FIRE_LATERAL_VELOCITY);
        int distSeg = GuessFactor.distanceSegment(distance);
        int latVelSeg = GuessFactor.lateralVelocitySegment(
                Double.isNaN(latVel) ? 0 : latVel);
        double lag1Gf = wb.getOurWave(slot, OurWaveColumn.AIM_LAG1_GF);
        int bestBin = getBestBin(distSeg, latVelSeg, lag1Gf);
        return GuessFactor.binIndexToGf(bestBin, GuessFactor.NUM_BINS);
    }

    @Override
    public void update(Whiteboard wb, int slot, double breakGf) {
        double distance = aimOrFire(wb, slot, OurWaveColumn.AIM_DISTANCE, OurWaveColumn.FIRE_DISTANCE);
        double latVel = aimOrFire(wb, slot, OurWaveColumn.AIM_LATERAL_VELOCITY, OurWaveColumn.FIRE_LATERAL_VELOCITY);
        int distSeg = GuessFactor.distanceSegment(distance);
        int latVelSeg = GuessFactor.lateralVelocitySegment(
                Double.isNaN(latVel) ? 0 : latVel);
        double lag1Gf = wb.getOurWave(slot, OurWaveColumn.AIM_LAG1_GF);
        int binIndex = GuessFactor.gfToBinIndex(breakGf, GuessFactor.NUM_BINS);
        increment(distSeg, latVelSeg, lag1Gf, binIndex);
    }

    /**
     * Read an aim-time wave column, falling back to the fire-time column when the
     * aim-time value is unavailable (e.g. synthetic waves staged directly in unit
     * tests). The gun predicts on aim-time segments, so training keys on them too.
     */
    private static double aimOrFire(Whiteboard wb, int slot,
            OurWaveColumn aimCol, OurWaveColumn fireCol) {
        double aim = wb.getOurWave(slot, aimCol);
        return Double.isNaN(aim) ? wb.getOurWave(slot, fireCol) : aim;
    }

    @Override
    public String getName() {
        return "VCS";
    }
}

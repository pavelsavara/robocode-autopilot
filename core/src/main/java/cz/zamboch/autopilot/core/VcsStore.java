package cz.zamboch.autopilot.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Visit Count Stats histogram: distance × lateralVelocity × GF bins.
 * Compact int[5][5][31] = 775 bins total.
 * <p>
 * Implements {@link IOnlineModel} so it can be used interchangeably with
 * future targeting models (pattern matchers, neural nets, etc.).
 */
public final class VcsStore implements IOnlineModel {
    private final int[][][] data = new int[GuessFactor.DISTANCE_SEGMENTS][GuessFactor.LAT_VEL_SEGMENTS][GuessFactor.NUM_BINS];

    /** Increment the bin for a resolved wave. */
    public void increment(int distSeg, int latVelSeg, int binIndex) {
        data[distSeg][latVelSeg][binIndex]++;
    }

    /**
     * Get the bin index with the highest visit count for a segment pair. Returns
     * ZERO_BIN if all zero.
     */
    public int getBestBin(int distSeg, int latVelSeg) {
        int[] bins = data[distSeg][latVelSeg];
        int bestIndex = GuessFactor.ZERO_BIN;
        int bestCount = 0;
        for (int i = 0; i < bins.length; i++) {
            if (bins[i] > bestCount) {
                bestCount = bins[i];
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    /** Get the visit count for a specific bin. */
    public int getCount(int distSeg, int latVelSeg, int binIndex) {
        return data[distSeg][latVelSeg][binIndex];
    }

    /** Clear all bins to zero. */
    public void clear() {
        for (int d = 0; d < GuessFactor.DISTANCE_SEGMENTS; d++) {
            for (int l = 0; l < GuessFactor.LAT_VEL_SEGMENTS; l++) {
                for (int b = 0; b < GuessFactor.NUM_BINS; b++) {
                    data[d][l][b] = 0;
                }
            }
        }
    }

    /** Write binary data to stream. */
    public void save(DataOutputStream out) throws IOException {
        for (int d = 0; d < GuessFactor.DISTANCE_SEGMENTS; d++) {
            for (int l = 0; l < GuessFactor.LAT_VEL_SEGMENTS; l++) {
                for (int b = 0; b < GuessFactor.NUM_BINS; b++) {
                    out.writeInt(data[d][l][b]);
                }
            }
        }
    }

    /** Read binary data from stream. */
    public void load(DataInputStream in) throws IOException {
        for (int d = 0; d < GuessFactor.DISTANCE_SEGMENTS; d++) {
            for (int l = 0; l < GuessFactor.LAT_VEL_SEGMENTS; l++) {
                for (int b = 0; b < GuessFactor.NUM_BINS; b++) {
                    data[d][l][b] = in.readInt();
                }
            }
        }
    }

    /** Total byte size of serialized data: 5 × 5 × 31 × 4 bytes. */
    public static int serializedSize() {
        return GuessFactor.DISTANCE_SEGMENTS * GuessFactor.LAT_VEL_SEGMENTS
                * GuessFactor.NUM_BINS * 4;
    }

    // ========== IOnlineModel implementation ==========

    @Override
    public double predict(Whiteboard wb, int slot) {
        double distance = wb.getOurWave(slot, OurWaveColumn.FIRE_DISTANCE);
        double latVel = wb.getOurWave(slot, OurWaveColumn.FIRE_LATERAL_VELOCITY);
        int distSeg = GuessFactor.distanceSegment(distance);
        int latVelSeg = GuessFactor.lateralVelocitySegment(
                Double.isNaN(latVel) ? 0 : latVel);
        int bestBin = getBestBin(distSeg, latVelSeg);
        return GuessFactor.binIndexToGf(bestBin, GuessFactor.NUM_BINS);
    }

    @Override
    public void update(Whiteboard wb, int slot, double breakGf) {
        double distance = wb.getOurWave(slot, OurWaveColumn.FIRE_DISTANCE);
        double latVel = wb.getOurWave(slot, OurWaveColumn.FIRE_LATERAL_VELOCITY);
        int distSeg = GuessFactor.distanceSegment(distance);
        int latVelSeg = GuessFactor.lateralVelocitySegment(
                Double.isNaN(latVel) ? 0 : latVel);
        int binIndex = GuessFactor.gfToBinIndex(breakGf, GuessFactor.NUM_BINS);
        increment(distSeg, latVelSeg, binIndex);
    }

    @Override
    public String getName() {
        return "VCS";
    }
}

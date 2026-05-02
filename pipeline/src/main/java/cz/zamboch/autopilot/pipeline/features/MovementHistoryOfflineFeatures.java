package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.util.RingBuffer;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import cz.zamboch.autopilot.pipeline.IOfflineFeatures;

/**
 * Tier 2 movement history / segmentation features (per-tick, TICKS file).
 *
 * Maintains rolling history of opponent lateral velocity, velocity, and
 * heading-delta in {@link Whiteboard}'s ring buffers, computes rolling means
 * and std-devs over windows of 10 and 30 scans, and tracks long-running
 * segmentation counters (time since velocity change, distance since direction
 * change). Stateless: all inter-tick state lives in Whiteboard.
 */
public final class MovementHistoryOfflineFeatures implements IOfflineFeatures {

    /** Velocity change threshold (px/tick) — anything below is treated as steady-state. */
    private static final double VELOCITY_CHANGE_THRESHOLD = 1.0;

    private static final Feature[] OUTPUTS = {
            Feature.OPPONENT_AVG_LATERAL_VELOCITY_10,
            Feature.OPPONENT_AVG_LATERAL_VELOCITY_30,
            Feature.OPPONENT_HEADING_DELTA_VARIABILITY_10,
            Feature.OPPONENT_VELOCITY_VARIABILITY_10,
            Feature.OPPONENT_TIME_SINCE_VELOCITY_CHANGE,
            Feature.OPPONENT_DISTANCE_SINCE_DIRECTION_CHANGE
    };

    private static final Feature[] DEPS = {
            Feature.OPPONENT_LATERAL_VELOCITY,
            Feature.OPPONENT_VELOCITY,
            Feature.OPPONENT_LATERAL_DIRECTION
    };

    public Feature[] getOutputFeatures() { return OUTPUTS; }
    public Feature[] getDependencies() { return DEPS; }

    public void process(Whiteboard wb) {
        if (!wb.isScanAvailableThisTick()
                || !wb.hasFeature(Feature.OPPONENT_LATERAL_VELOCITY)
                || !wb.hasFeature(Feature.OPPONENT_VELOCITY)) {
            return;
        }

        double latVel = wb.getFeature(Feature.OPPONENT_LATERAL_VELOCITY);
        double vel = wb.getFeature(Feature.OPPONENT_VELOCITY);

        // Push current scan into rolling buffers.
        wb.getLatVelHistory30().add(latVel);
        wb.getVelHistory30().add(vel);
        if (wb.hasFeature(Feature.OPPONENT_HEADING_DELTA)) {
            wb.getHeadingDeltaHistory30().add(wb.getFeature(Feature.OPPONENT_HEADING_DELTA));
        }

        // Rolling means and std-devs.
        wb.setFeature(Feature.OPPONENT_AVG_LATERAL_VELOCITY_10,
                rollingMean(wb.getLatVelHistory30(), 10));
        wb.setFeature(Feature.OPPONENT_AVG_LATERAL_VELOCITY_30,
                rollingMean(wb.getLatVelHistory30(), 30));
        wb.setFeature(Feature.OPPONENT_VELOCITY_VARIABILITY_10,
                rollingStd(wb.getVelHistory30(), 10));
        wb.setFeature(Feature.OPPONENT_HEADING_DELTA_VARIABILITY_10,
                rollingStd(wb.getHeadingDeltaHistory30(), 10));

        // Time since velocity change (>= threshold).
        double prevSig = wb.getLastSignificantOpponentVelocity();
        long lastChangeTick = wb.getLastVelocityChangeTick();
        long tick = wb.getTick();
        if (Double.isNaN(prevSig)) {
            wb.setLastVelocityChange(tick, vel);
            wb.setFeature(Feature.OPPONENT_TIME_SINCE_VELOCITY_CHANGE, 0);
        } else if (Math.abs(vel - prevSig) >= VELOCITY_CHANGE_THRESHOLD) {
            wb.setLastVelocityChange(tick, vel);
            wb.setFeature(Feature.OPPONENT_TIME_SINCE_VELOCITY_CHANGE, 0);
        } else {
            wb.setFeature(Feature.OPPONENT_TIME_SINCE_VELOCITY_CHANGE,
                    tick - lastChangeTick);
        }

        // Distance since lateral-direction change. MovementSegmentationOfflineFeatures
        // already tracks prevLateralDirection / ticksSinceDirectionChange. We accumulate
        // |opponent_velocity| each scan; reset on direction flip.
        int latDir = (int) wb.getFeature(Feature.OPPONENT_LATERAL_DIRECTION);
        // Whiteboard.getPrevLateralDirection() at this point reflects the freshly-set
        // direction (MovementSegmentation runs before this feature in topological order).
        // We detect a flip by comparing OPPONENT_TIME_SINCE_DIRECTION_CHANGE: it resets
        // to 0 on the tick of a flip.
        long ticksSinceDir = wb.hasFeature(Feature.OPPONENT_TIME_SINCE_DIRECTION_CHANGE)
                ? (long) wb.getFeature(Feature.OPPONENT_TIME_SINCE_DIRECTION_CHANGE)
                : -1;
        double accum;
        if (ticksSinceDir == 0 && latDir != 0) {
            accum = Math.abs(vel);
        } else {
            accum = wb.getDistanceSinceDirChange() + Math.abs(vel);
        }
        wb.setDistanceSinceDirChange(accum);
        wb.setFeature(Feature.OPPONENT_DISTANCE_SINCE_DIRECTION_CHANGE, accum);
    }

    private static double rollingMean(RingBuffer<Double> buf, int window) {
        int n = Math.min(buf.size(), window);
        if (n == 0) return 0.0;
        double sum = 0;
        for (int i = 0; i < n; i++) sum += buf.get(i);
        return sum / n;
    }

    private static double rollingStd(RingBuffer<Double> buf, int window) {
        int n = Math.min(buf.size(), window);
        if (n < 2) return 0.0;
        double mean = rollingMean(buf, window);
        double sumSq = 0;
        for (int i = 0; i < n; i++) {
            double d = buf.get(i) - mean;
            sumSq += d * d;
        }
        return Math.sqrt(sumSq / n);
    }

    public FileType getFileType() { return FileType.TICKS; }

    public void writeColumnNames(CsvRowWriter row) {
        row.writeHeaders(
                "opponent_avg_lateral_velocity_10",
                "opponent_avg_lateral_velocity_30",
                "opponent_heading_delta_variability_10",
                "opponent_velocity_variability_10",
                "opponent_time_since_velocity_change",
                "opponent_distance_since_direction_change");
    }

    public void writeRowValues(CsvRowWriter row, Whiteboard wb) {
        row.writeDouble(wb, Feature.OPPONENT_AVG_LATERAL_VELOCITY_10, "%.4f");
        row.writeDouble(wb, Feature.OPPONENT_AVG_LATERAL_VELOCITY_30, "%.4f");
        row.writeDouble(wb, Feature.OPPONENT_HEADING_DELTA_VARIABILITY_10, "%.6f");
        row.writeDouble(wb, Feature.OPPONENT_VELOCITY_VARIABILITY_10, "%.4f");
        row.writeInt(wb, Feature.OPPONENT_TIME_SINCE_VELOCITY_CHANGE);
        row.writeDouble(wb, Feature.OPPONENT_DISTANCE_SINCE_DIRECTION_CHANGE, "%.2f");
    }
}

package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import cz.zamboch.autopilot.pipeline.IOfflineFeatures;

/**
 * Pipeline-only movement segmentation features.
 * Stateless — inter-tick state lives in Whiteboard (prevLateralDirection, ticksSinceDirectionChange).
 * Depends on OPPONENT_LATERAL_VELOCITY and OPPONENT_VELOCITY from MovementFeatures.
 */
public final class MovementSegmentationOfflineFeatures implements IOfflineFeatures {

    private static final Feature[] OUTPUTS = {
            Feature.OPPONENT_LATERAL_DIRECTION,
            Feature.OPPONENT_VELOCITY_DELTA,
            Feature.OPPONENT_IS_DECELERATING,
            Feature.OPPONENT_TIME_SINCE_DIRECTION_CHANGE
    };

    private static final Feature[] DEPS = {
            Feature.OPPONENT_LATERAL_VELOCITY,
            Feature.OPPONENT_VELOCITY
    };

    public Feature[] getOutputFeatures() {
        return OUTPUTS;
    }

    public Feature[] getDependencies() {
        return DEPS;
    }

    public void process(Whiteboard wb) {
        if (!wb.isScanAvailableThisTick()) {
            return;
        }

        double latVel = wb.getFeature(Feature.OPPONENT_LATERAL_VELOCITY);
        double vel = wb.getFeature(Feature.OPPONENT_VELOCITY);

        // Lateral direction: sign of lateral velocity
        int lateralDirection;
        if (latVel > 0) {
            lateralDirection = 1;
        } else if (latVel < 0) {
            lateralDirection = -1;
        } else {
            lateralDirection = 0;
        }
        wb.setFeature(Feature.OPPONENT_LATERAL_DIRECTION, lateralDirection);

        // Velocity delta (acceleration) — uses prevOpponentVelocity and prevScanTick from Whiteboard
        double prevVel = wb.getPrevOpponentVelocity();
        long prevScanTick = wb.getPrevScanTick();
        if (!Double.isNaN(prevVel) && prevScanTick >= 0) {
            long deltaTicks = wb.getTick() - prevScanTick;
            if (deltaTicks > 0) {
                wb.setFeature(Feature.OPPONENT_VELOCITY_DELTA, (vel - prevVel) / deltaTicks);
            }
        }

        // Is decelerating
        boolean isDecelerating = !Double.isNaN(prevVel) && Math.abs(vel) < Math.abs(prevVel);
        wb.setFeature(Feature.OPPONENT_IS_DECELERATING, isDecelerating ? 1.0 : 0.0);

        // Time since direction change — reads/writes Whiteboard counters
        int prevDir = wb.getPrevLateralDirection();
        long counter = wb.getTicksSinceDirectionChange();
        if (lateralDirection != 0 && prevDir != 0 && lateralDirection != prevDir) {
            counter = 0;
        } else {
            counter++;
        }
        wb.setFeature(Feature.OPPONENT_TIME_SINCE_DIRECTION_CHANGE, counter);
        wb.setPrevLateralDirection(lateralDirection);
        wb.setTicksSinceDirectionChange(counter);
    }

    public FileType getFileType() {
        return FileType.TICKS;
    }

    public void writeColumnNames(CsvRowWriter row) {
        row.writeHeaders("opponent_lateral_direction", "opponent_velocity_delta",
                "opponent_is_decelerating", "opponent_time_since_direction_change");
    }

    public void writeRowValues(CsvRowWriter row, Whiteboard wb) {
        row.writeInt(wb, Feature.OPPONENT_LATERAL_DIRECTION);
        row.writeDouble(wb, Feature.OPPONENT_VELOCITY_DELTA, "%.4f");
        row.writeBoolean(wb, Feature.OPPONENT_IS_DECELERATING);
        row.writeInt(wb, Feature.OPPONENT_TIME_SINCE_DIRECTION_CHANGE);
    }
}

package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.MovementSegmentationFeatures;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import cz.zamboch.autopilot.pipeline.IOfflineFeatures;

/**
 * Offline extension of MovementSegmentationFeatures — adds CSV output support.
 */
public final class MovementSegmentationOfflineFeatures extends MovementSegmentationFeatures implements IOfflineFeatures {

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

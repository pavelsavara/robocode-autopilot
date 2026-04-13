package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.MovementFeatures;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import cz.zamboch.autopilot.pipeline.IOfflineFeatures;

/**
 * Offline extension of MovementFeatures — adds CSV output support.
 */
public final class MovementOfflineFeatures extends MovementFeatures implements IOfflineFeatures {

    public FileType getFileType() {
        return FileType.TICKS;
    }

    public void writeColumnNames(CsvRowWriter row) {
        row.writeHeaders("opponent_velocity", "opponent_lateral_velocity",
                "opponent_advancing_velocity", "opponent_heading_delta");
    }

    public void writeRowValues(CsvRowWriter row, Whiteboard wb) {
        row.writeDouble(wb, Feature.OPPONENT_VELOCITY, "%.3f");
        row.writeDouble(wb, Feature.OPPONENT_LATERAL_VELOCITY, "%.3f");
        row.writeDouble(wb, Feature.OPPONENT_ADVANCING_VELOCITY, "%.3f");
        row.writeDouble(wb, Feature.OPPONENT_HEADING_DELTA, "%.4f");
    }
}

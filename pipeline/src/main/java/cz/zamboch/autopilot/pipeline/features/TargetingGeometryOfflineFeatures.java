package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.TargetingGeometryFeatures;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import cz.zamboch.autopilot.pipeline.IOfflineFeatures;

/**
 * Offline extension of TargetingGeometryFeatures — adds CSV output support.
 */
public final class TargetingGeometryOfflineFeatures extends TargetingGeometryFeatures implements IOfflineFeatures {

    public FileType getFileType() {
        return FileType.TICKS;
    }

    public void writeColumnNames(CsvRowWriter row) {
        row.writeHeaders("opponent_angular_velocity", "opponent_max_turn_rate", "distance_norm");
    }

    public void writeRowValues(CsvRowWriter row, Whiteboard wb) {
        row.writeDouble(wb, Feature.OPPONENT_ANGULAR_VELOCITY, "%.6f");
        row.writeDouble(wb, Feature.OPPONENT_MAX_TURN_RATE, "%.4f");
        row.writeDouble(wb, Feature.DISTANCE_NORM, "%.4f");
    }
}

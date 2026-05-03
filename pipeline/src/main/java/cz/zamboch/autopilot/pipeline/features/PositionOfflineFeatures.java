package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.PositionFeatures;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import cz.zamboch.autopilot.pipeline.IOfflineFeatures;

/**
 * Offline extension of PositionFeatures — adds CSV output for absolute positions.
 * Writes to ticks.csv.
 */
public final class PositionOfflineFeatures extends PositionFeatures implements IOfflineFeatures {

    public FileType getFileType() {
        return FileType.TICKS;
    }

    public void writeColumnNames(CsvRowWriter row) {
        row.writeHeaders("our_x", "our_y", "our_heading", "our_velocity",
                "opponent_x", "opponent_y", "opponent_heading");
    }

    public void writeRowValues(CsvRowWriter row, Whiteboard wb) {
        row.writeDouble(wb, Feature.OUR_X, "%.3f");
        row.writeDouble(wb, Feature.OUR_Y, "%.3f");
        row.writeDouble(wb, Feature.OUR_HEADING, "%.6f");
        row.writeDouble(wb, Feature.OUR_VELOCITY, "%.3f");
        row.writeDouble(wb, Feature.OPPONENT_X, "%.3f");
        row.writeDouble(wb, Feature.OPPONENT_Y, "%.3f");
        row.writeDouble(wb, Feature.OPPONENT_HEADING, "%.6f");
    }
}

package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.StateNormalizationFeatures;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import cz.zamboch.autopilot.pipeline.IOfflineFeatures;

/**
 * Offline extension of StateNormalizationFeatures — adds CSV output support.
 */
public final class StateNormalizationOfflineFeatures extends StateNormalizationFeatures implements IOfflineFeatures {

    public FileType getFileType() {
        return FileType.TICKS;
    }

    public void writeColumnNames(CsvRowWriter row) {
        row.writeHeaders("energy_ratio", "our_lateral_velocity", "our_dist_to_wall_min");
    }

    public void writeRowValues(CsvRowWriter row, Whiteboard wb) {
        row.writeDouble(wb, Feature.ENERGY_RATIO, "%.4f");
        row.writeDouble(wb, Feature.OUR_LATERAL_VELOCITY, "%.3f");
        row.writeDouble(wb, Feature.OUR_DIST_TO_WALL_MIN, "%.3f");
    }
}

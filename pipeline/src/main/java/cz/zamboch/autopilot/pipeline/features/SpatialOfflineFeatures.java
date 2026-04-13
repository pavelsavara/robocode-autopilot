package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.SpatialFeatures;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import cz.zamboch.autopilot.pipeline.IOfflineFeatures;

/**
 * Offline extension of SpatialFeatures — adds CSV output support.
 */
public final class SpatialOfflineFeatures extends SpatialFeatures implements IOfflineFeatures {

    public FileType getFileType() {
        return FileType.TICKS;
    }

    public void writeColumnNames(CsvRowWriter row) {
        row.writeHeaders("distance", "bearing_to_opponent_abs", "opponent_dist_to_wall_min");
    }

    public void writeRowValues(CsvRowWriter row, Whiteboard wb) {
        row.writeDouble(wb, Feature.DISTANCE, "%.3f");
        row.writeDouble(wb, Feature.BEARING_TO_OPPONENT_ABS, "%.4f");
        row.writeDouble(wb, Feature.OPPONENT_DIST_TO_WALL_MIN, "%.3f");
    }
}

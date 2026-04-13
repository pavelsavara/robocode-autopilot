package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.SpatialFeatures;
import cz.zamboch.autopilot.pipeline.IOfflineFeatureProcessor;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Offline extension of SpatialFeatures — adds CSV output support.
 */
public class SpatialOfflineFeatures extends SpatialFeatures implements IOfflineFeatureProcessor {

    public FileType getFileType() {
        return FileType.TICKS;
    }

    public void writeColumnNames(OutputStream out) throws IOException {
        out.write("distance,bearing_to_opponent_abs,opponent_dist_to_wall_min".getBytes());
    }

    public void writeRowValues(OutputStream out, Whiteboard wb) throws IOException {
        StringBuilder sb = new StringBuilder();
        CsvUtil.appendValue(sb, wb, Feature.DISTANCE, "%.3f");
        sb.append(',');
        CsvUtil.appendValue(sb, wb, Feature.BEARING_TO_OPPONENT_ABS, "%.4f");
        sb.append(',');
        CsvUtil.appendValue(sb, wb, Feature.OPPONENT_DIST_TO_WALL_MIN, "%.3f");
        out.write(sb.toString().getBytes());
    }
}

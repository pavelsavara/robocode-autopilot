package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.MovementFeatures;
import cz.zamboch.autopilot.pipeline.IOfflineFeatureProcessor;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Offline extension of MovementFeatures — adds CSV output support.
 */
public class MovementOfflineFeatures extends MovementFeatures implements IOfflineFeatureProcessor {

    public FileType getFileType() {
        return FileType.TICKS;
    }

    public void writeColumnNames(OutputStream out) throws IOException {
        out.write("opponent_velocity,opponent_lateral_velocity,opponent_advancing_velocity,opponent_heading_delta".getBytes());
    }

    public void writeRowValues(OutputStream out, Whiteboard wb) throws IOException {
        StringBuilder sb = new StringBuilder();
        CsvUtil.appendValue(sb, wb, Feature.OPPONENT_VELOCITY, "%.3f");
        sb.append(',');
        CsvUtil.appendValue(sb, wb, Feature.OPPONENT_LATERAL_VELOCITY, "%.3f");
        sb.append(',');
        CsvUtil.appendValue(sb, wb, Feature.OPPONENT_ADVANCING_VELOCITY, "%.3f");
        sb.append(',');
        CsvUtil.appendValue(sb, wb, Feature.OPPONENT_HEADING_DELTA, "%.4f");
        out.write(sb.toString().getBytes());
    }
}

package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.TimingFeatures;
import cz.zamboch.autopilot.pipeline.IOfflineFeatureProcessor;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Offline extension of TimingFeatures — adds CSV output support.
 */
public class TimingOfflineFeatures extends TimingFeatures implements IOfflineFeatureProcessor {

    public FileType getFileType() {
        return FileType.TICKS;
    }

    public void writeColumnNames(OutputStream out) throws IOException {
        out.write("our_gun_heat,ticks_since_scan".getBytes());
    }

    public void writeRowValues(OutputStream out, Whiteboard wb) throws IOException {
        StringBuilder sb = new StringBuilder();
        CsvUtil.appendValue(sb, wb, Feature.OUR_GUN_HEAT, "%.3f");
        sb.append(',');
        CsvUtil.appendInt(sb, wb, Feature.TICKS_SINCE_SCAN);
        out.write(sb.toString().getBytes());
    }
}

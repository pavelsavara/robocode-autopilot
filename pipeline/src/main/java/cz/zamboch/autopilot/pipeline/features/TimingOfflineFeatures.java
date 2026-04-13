package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.TimingFeatures;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import cz.zamboch.autopilot.pipeline.IOfflineFeatures;

/**
 * Offline extension of TimingFeatures — adds CSV output support.
 */
public final class TimingOfflineFeatures extends TimingFeatures implements IOfflineFeatures {

    public FileType getFileType() {
        return FileType.TICKS;
    }

    public void writeColumnNames(CsvRowWriter row) {
        row.writeHeaders("our_gun_heat", "ticks_since_scan");
    }

    public void writeRowValues(CsvRowWriter row, Whiteboard wb) {
        row.writeDouble(wb, Feature.OUR_GUN_HEAT, "%.3f");
        row.writeInt(wb, Feature.TICKS_SINCE_SCAN);
    }
}

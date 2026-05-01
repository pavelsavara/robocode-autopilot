package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.IdentityFeatures;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import cz.zamboch.autopilot.pipeline.IOfflineFeatures;

/**
 * Offline extension of IdentityFeatures — adds CSV output support.
 */
public final class IdentityOfflineFeatures extends IdentityFeatures implements IOfflineFeatures {

    public FileType getFileType() {
        return FileType.TICKS;
    }

    public void writeColumnNames(CsvRowWriter row) {
        row.writeHeaders("opponent_name_hash");
    }

    public void writeRowValues(CsvRowWriter row, Whiteboard wb) {
        row.writeInt(wb, Feature.OPPONENT_NAME_HASH);
    }
}

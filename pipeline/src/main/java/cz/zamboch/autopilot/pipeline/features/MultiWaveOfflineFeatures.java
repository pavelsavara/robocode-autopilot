package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.MultiWaveFeatures;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import cz.zamboch.autopilot.pipeline.IOfflineFeatures;

/**
 * Offline extension of MultiWaveFeatures — adds CSV output for wave counts.
 * Writes to ticks.csv.
 */
public final class MultiWaveOfflineFeatures extends MultiWaveFeatures implements IOfflineFeatures {

    public FileType getFileType() {
        return FileType.TICKS;
    }

    public void writeColumnNames(CsvRowWriter row) {
        row.writeHeaders("n_opponent_waves_in_flight", "n_our_waves_in_flight");
    }

    public void writeRowValues(CsvRowWriter row, Whiteboard wb) {
        row.writeInt(wb, Feature.N_OPPONENT_WAVES_IN_FLIGHT);
        row.writeInt(wb, Feature.N_OUR_WAVES_IN_FLIGHT);
    }
}

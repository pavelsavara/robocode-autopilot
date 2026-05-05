package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.MultiWaveFeatures;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import cz.zamboch.autopilot.pipeline.IOfflineFeatures;

/**
 * Offline extension of MultiWaveFeatures — adds CSV output for wave counts
 * and wave-pressure features. Writes to ticks.csv.
 */
public final class MultiWaveOfflineFeatures extends MultiWaveFeatures implements IOfflineFeatures {

    public FileType getFileType() {
        return FileType.TICKS;
    }

    public void writeColumnNames(CsvRowWriter row) {
        row.writeHeaders("n_opponent_waves_in_flight", "n_our_waves_in_flight",
                "nearest_opponent_wave_gap", "total_opponent_wave_damage",
                "nearest_our_wave_gap");
    }

    public void writeRowValues(CsvRowWriter row, Whiteboard wb) {
        row.writeInt(wb, Feature.N_OPPONENT_WAVES_IN_FLIGHT);
        row.writeInt(wb, Feature.N_OUR_WAVES_IN_FLIGHT);
        row.writeInt(wb, Feature.NEAREST_OPPONENT_WAVE_GAP);
        row.writeDouble(wb, Feature.TOTAL_OPPONENT_WAVE_DAMAGE, "%.2f");
        row.writeInt(wb, Feature.NEAREST_OUR_WAVE_GAP);
    }
}

package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.CombatProgressFeatures;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import cz.zamboch.autopilot.pipeline.IOfflineFeatures;

/**
 * Offline extension of CombatProgressFeatures — adds CSV output for
 * per-tick cumulative combat counters. Writes to ticks.csv.
 */
public final class CombatProgressOfflineFeatures extends CombatProgressFeatures implements IOfflineFeatures {

    public FileType getFileType() {
        return FileType.TICKS;
    }

    public void writeColumnNames(CsvRowWriter row) {
        row.writeHeaders("cumulative_damage_dealt", "cumulative_damage_received",
                "cumulative_our_hit_rate", "cumulative_opponent_hit_rate",
                "cumulative_our_shots_fired", "cumulative_opponent_shots_detected");
    }

    public void writeRowValues(CsvRowWriter row, Whiteboard wb) {
        row.writeDouble(wb, Feature.CUMULATIVE_DAMAGE_DEALT, "%.2f");
        row.writeDouble(wb, Feature.CUMULATIVE_DAMAGE_RECEIVED, "%.2f");
        row.writeDouble(wb, Feature.CUMULATIVE_OUR_HIT_RATE, "%.4f");
        row.writeDouble(wb, Feature.CUMULATIVE_OPPONENT_HIT_RATE, "%.4f");
        row.writeInt(wb, Feature.CUMULATIVE_OUR_SHOTS_FIRED);
        row.writeInt(wb, Feature.CUMULATIVE_OPPONENT_SHOTS_DETECTED);
    }
}

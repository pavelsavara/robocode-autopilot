package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.OpponentPredictionFeatures;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import cz.zamboch.autopilot.pipeline.IOfflineFeatures;

/**
 * Offline extension of OpponentPredictionFeatures — adds CSV output support.
 */
public final class OpponentPredictionOfflineFeatures extends OpponentPredictionFeatures implements IOfflineFeatures {

    public FileType getFileType() {
        return FileType.TICKS;
    }

    public void writeColumnNames(CsvRowWriter row) {
        row.writeHeaders("opponent_wall_ahead_distance", "opponent_inferred_gun_heat");
    }

    public void writeRowValues(CsvRowWriter row, Whiteboard wb) {
        row.writeDouble(wb, Feature.OPPONENT_WALL_AHEAD_DISTANCE, "%.3f");
        row.writeDouble(wb, Feature.OPPONENT_INFERRED_GUN_HEAT, "%.4f");
    }
}

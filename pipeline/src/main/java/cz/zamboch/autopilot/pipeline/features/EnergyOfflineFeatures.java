package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.EnergyFeatures;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import cz.zamboch.autopilot.pipeline.IOfflineFeatures;

/**
 * Offline extension of EnergyFeatures — adds CSV output support.
 */
public final class EnergyOfflineFeatures extends EnergyFeatures implements IOfflineFeatures {

    public FileType getFileType() {
        return FileType.TICKS;
    }

    public void writeColumnNames(CsvRowWriter row) {
        row.writeHeaders("opponent_energy", "opponent_fired", "opponent_fire_power");
    }

    public void writeRowValues(CsvRowWriter row, Whiteboard wb) {
        row.writeDouble(wb, Feature.OPPONENT_ENERGY, "%.3f");
        row.writeBoolean(wb, Feature.OPPONENT_FIRED);
        row.writeDouble(wb, Feature.OPPONENT_FIRE_POWER, "%.3f");
    }
}

package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.EnergyFeatures;
import cz.zamboch.autopilot.pipeline.IOfflineFeatureProcessor;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Offline extension of EnergyFeatures — adds CSV output support.
 */
public class EnergyOfflineFeatures extends EnergyFeatures implements IOfflineFeatureProcessor {

    public FileType getFileType() {
        return FileType.TICKS;
    }

    public void writeColumnNames(OutputStream out) throws IOException {
        out.write("opponent_energy,opponent_fired,opponent_fire_power".getBytes());
    }

    public void writeRowValues(OutputStream out, Whiteboard wb) throws IOException {
        StringBuilder sb = new StringBuilder();
        CsvUtil.appendValue(sb, wb, Feature.OPPONENT_ENERGY, "%.3f");
        sb.append(',');
        CsvUtil.appendBoolean(sb, wb, Feature.OPPONENT_FIRED);
        sb.append(',');
        CsvUtil.appendValue(sb, wb, Feature.OPPONENT_FIRE_POWER, "%.3f");
        out.write(sb.toString().getBytes());
    }
}

package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.EnvelopeFeatures;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import cz.zamboch.autopilot.pipeline.IOfflineFeatures;

/**
 * Offline extension of EnvelopeFeatures — adds CSV output for
 * envelope-derived features. Writes to ticks.csv.
 */
public final class EnvelopeOfflineFeatures extends EnvelopeFeatures implements IOfflineFeatures {

    public FileType getFileType() {
        return FileType.TICKS;
    }

    public void writeColumnNames(CsvRowWriter row) {
        row.writeHeaders("envelope_fill_ratio",
                "reachable_distance_min", "reachable_distance_max",
                "reachable_gf_range");
    }

    public void writeRowValues(CsvRowWriter row, Whiteboard wb) {
        row.writeDouble(wb, Feature.ENVELOPE_FILL_RATIO, "%.4f");
        row.writeDouble(wb, Feature.REACHABLE_DISTANCE_MIN, "%.2f");
        row.writeDouble(wb, Feature.REACHABLE_DISTANCE_MAX, "%.2f");
        row.writeDouble(wb, Feature.REACHABLE_GF_RANGE, "%.4f");
    }
}

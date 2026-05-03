package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.TargetingFeatures;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import cz.zamboch.autopilot.pipeline.IOfflineFeatures;

/**
 * Offline wrapper for targeting features. Extends the core {@link TargetingFeatures}
 * (which computes linear/circular targeting + GF coordinates) and adds CSV output.
 *
 * <p>All computation lives in the base class. This class only adds
 * {@link IOfflineFeatures} methods for file type and CSV serialization.</p>
 */
public final class TargetingOfflineFeatures extends TargetingFeatures implements IOfflineFeatures {

    public FileType getFileType() { return FileType.TICKS; }

    public void writeColumnNames(CsvRowWriter row) {
        row.writeHeaders(
                "linear_target_angle", "linear_target_offset",
                "circular_target_angle", "circular_target_offset",
                "gf_bearing_offset",
                "gf_current_at_power_1", "gf_current_at_power_1_5", "gf_current_at_power_2",
                "opponent_guess_factor");
    }

    public void writeRowValues(CsvRowWriter row, Whiteboard wb) {
        row.writeDouble(wb, Feature.LINEAR_TARGET_ANGLE, "%.6f");
        row.writeDouble(wb, Feature.LINEAR_TARGET_OFFSET, "%.6f");
        row.writeDouble(wb, Feature.CIRCULAR_TARGET_ANGLE, "%.6f");
        row.writeDouble(wb, Feature.CIRCULAR_TARGET_OFFSET, "%.6f");
        row.writeDouble(wb, Feature.GF_BEARING_OFFSET, "%.6f");
        row.writeDouble(wb, Feature.GF_CURRENT_AT_POWER_1, "%.4f");
        row.writeDouble(wb, Feature.GF_CURRENT_AT_POWER_1_5, "%.4f");
        row.writeDouble(wb, Feature.GF_CURRENT_AT_POWER_2, "%.4f");
        row.writeDouble(wb, Feature.OPPONENT_GUESS_FACTOR, "%.4f");
    }
}

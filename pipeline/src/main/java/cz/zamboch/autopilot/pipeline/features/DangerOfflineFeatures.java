package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import cz.zamboch.autopilot.pipeline.IOfflineFeatures;

/**
 * Tier 3 danger-assessment features (per-tick, TICKS file).
 *
 * Currently exposes only escape_angle_coverage. Other danger metrics from
 * the catalog (#117 our_reachable_gf_range) require a precise movement model
 * and are deferred until after a baseline GF predictor exists.
 */
public final class DangerOfflineFeatures implements IOfflineFeatures {

    private static final double MAX_VELOCITY = 8.0;

    private static final Feature[] OUTPUTS = {
            Feature.ESCAPE_ANGLE_COVERAGE
    };

    private static final Feature[] DEPS = {
            Feature.OPPONENT_WAVE_ETA,
            Feature.MEA_FOR_OPPONENT_BULLET,
            Feature.DISTANCE
    };

    public Feature[] getOutputFeatures() { return OUTPUTS; }
    public Feature[] getDependencies() { return DEPS; }

    public void process(Whiteboard wb) {
        if (!wb.hasFeature(Feature.OPPONENT_WAVE_ETA)
                || !wb.hasFeature(Feature.MEA_FOR_OPPONENT_BULLET)
                || !wb.hasFeature(Feature.DISTANCE)) {
            return;
        }
        double eta = wb.getFeature(Feature.OPPONENT_WAVE_ETA);
        double mea = wb.getFeature(Feature.MEA_FOR_OPPONENT_BULLET);
        double distance = wb.getFeature(Feature.DISTANCE);
        if (mea <= 0 || distance <= 0) {
            return;
        }
        // Fraction of MEA arc reachable before bullet arrives.
        double coverage = (eta * MAX_VELOCITY) / (mea * distance);
        if (coverage > 1.0) coverage = 1.0;
        if (coverage < 0.0) coverage = 0.0;
        wb.setFeature(Feature.ESCAPE_ANGLE_COVERAGE, coverage);
    }

    public FileType getFileType() { return FileType.TICKS; }

    public void writeColumnNames(CsvRowWriter row) {
        row.writeHeaders("escape_angle_coverage");
    }

    public void writeRowValues(CsvRowWriter row, Whiteboard wb) {
        row.writeDouble(wb, Feature.ESCAPE_ANGLE_COVERAGE, "%.4f");
    }
}

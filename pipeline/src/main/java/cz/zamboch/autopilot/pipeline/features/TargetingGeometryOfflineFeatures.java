package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import cz.zamboch.autopilot.pipeline.IOfflineFeatures;

/**
 * Pipeline-only targeting geometry features.
 * Stateless — all inputs from Whiteboard features.
 * Depends on OPPONENT_LATERAL_VELOCITY, DISTANCE, OPPONENT_VELOCITY.
 */
public final class TargetingGeometryOfflineFeatures implements IOfflineFeatures {

    private static final Feature[] OUTPUTS = {
            Feature.OPPONENT_ANGULAR_VELOCITY,
            Feature.OPPONENT_MAX_TURN_RATE,
            Feature.DISTANCE_NORM
    };

    private static final Feature[] DEPS = {
            Feature.OPPONENT_LATERAL_VELOCITY,
            Feature.DISTANCE,
            Feature.OPPONENT_VELOCITY
    };

    public Feature[] getOutputFeatures() {
        return OUTPUTS;
    }

    public Feature[] getDependencies() {
        return DEPS;
    }

    public void process(Whiteboard wb) {
        if (!wb.isScanAvailableThisTick()) {
            return;
        }

        double distance = wb.getFeature(Feature.DISTANCE);
        double latVel = wb.getFeature(Feature.OPPONENT_LATERAL_VELOCITY);
        double vel = wb.getFeature(Feature.OPPONENT_VELOCITY);

        // Angular velocity = lateral_velocity / distance
        if (distance > 0) {
            wb.setFeature(Feature.OPPONENT_ANGULAR_VELOCITY, latVel / distance);
        } else {
            wb.setFeature(Feature.OPPONENT_ANGULAR_VELOCITY, 0);
        }

        // Max turn rate (robocode formula): 10 - 0.75 * |velocity|, degrees → radians
        double maxTurnDeg = 10.0 - 0.75 * Math.abs(vel);
        wb.setFeature(Feature.OPPONENT_MAX_TURN_RATE, Math.toRadians(maxTurnDeg));

        // Normalised distance: distance / battlefield diagonal
        int bfW = wb.getBattlefieldWidth();
        int bfH = wb.getBattlefieldHeight();
        double diagonal = Math.hypot(bfW, bfH);
        wb.setFeature(Feature.DISTANCE_NORM, diagonal > 0 ? distance / diagonal : 0);
    }

    public FileType getFileType() {
        return FileType.TICKS;
    }

    public void writeColumnNames(CsvRowWriter row) {
        row.writeHeaders("opponent_angular_velocity", "opponent_max_turn_rate", "distance_norm");
    }

    public void writeRowValues(CsvRowWriter row, Whiteboard wb) {
        row.writeDouble(wb, Feature.OPPONENT_ANGULAR_VELOCITY, "%.6f");
        row.writeDouble(wb, Feature.OPPONENT_MAX_TURN_RATE, "%.4f");
        row.writeDouble(wb, Feature.DISTANCE_NORM, "%.4f");
    }
}

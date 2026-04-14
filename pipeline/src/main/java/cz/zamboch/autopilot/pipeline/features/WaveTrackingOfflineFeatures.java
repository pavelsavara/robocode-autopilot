package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import cz.zamboch.autopilot.pipeline.IOfflineFeatures;

/**
 * Pipeline-only wave features — one row per detected opponent fire event.
 * Stateless: reads fire state from Whiteboard and spatial features from current tick.
 * Only sets features on ticks where OPPONENT_FIRED=1.
 */
public final class WaveTrackingOfflineFeatures implements IOfflineFeatures {

    private static final Feature[] OUTPUTS = {
            Feature.WAVE_BULLET_POWER,
            Feature.WAVE_BULLET_SPEED,
            Feature.WAVE_FIRE_DISTANCE,
            Feature.WAVE_MEA,
            Feature.WAVE_FLIGHT_TIME,
            Feature.WAVE_LATERAL_VELOCITY_AT_FIRE
    };

    private static final Feature[] DEPS = {
            Feature.OPPONENT_FIRED,
            Feature.OPPONENT_FIRE_POWER,
            Feature.DISTANCE,
            Feature.OUR_LATERAL_VELOCITY
    };

    public Feature[] getOutputFeatures() {
        return OUTPUTS;
    }

    public Feature[] getDependencies() {
        return DEPS;
    }

    public void process(Whiteboard wb) {
        if (!wb.hasFeature(Feature.OPPONENT_FIRED)
                || wb.getFeature(Feature.OPPONENT_FIRED) != 1.0) {
            return;
        }

        double power = wb.getFeature(Feature.OPPONENT_FIRE_POWER);
        double speed = 20.0 - 3.0 * power;
        double distance = wb.getFeature(Feature.DISTANCE);
        double mea = Math.asin(8.0 / speed);
        double flightTime = distance / speed;
        double lateralVelocity = wb.hasFeature(Feature.OUR_LATERAL_VELOCITY)
                ? wb.getFeature(Feature.OUR_LATERAL_VELOCITY)
                : 0.0;

        wb.setFeature(Feature.WAVE_BULLET_POWER, power);
        wb.setFeature(Feature.WAVE_BULLET_SPEED, speed);
        wb.setFeature(Feature.WAVE_FIRE_DISTANCE, distance);
        wb.setFeature(Feature.WAVE_MEA, mea);
        wb.setFeature(Feature.WAVE_FLIGHT_TIME, flightTime);
        wb.setFeature(Feature.WAVE_LATERAL_VELOCITY_AT_FIRE, lateralVelocity);
    }

    public FileType getFileType() {
        return FileType.WAVES;
    }

    public void writeColumnNames(CsvRowWriter row) {
        row.writeHeaders(
                "wave_bullet_power", "wave_bullet_speed", "wave_fire_distance",
                "wave_mea", "wave_flight_time", "wave_lateral_velocity_at_fire");
    }

    public void writeRowValues(CsvRowWriter row, Whiteboard wb) {
        row.writeDouble(wb, Feature.WAVE_BULLET_POWER, "%.2f");
        row.writeDouble(wb, Feature.WAVE_BULLET_SPEED, "%.3f");
        row.writeDouble(wb, Feature.WAVE_FIRE_DISTANCE, "%.3f");
        row.writeDouble(wb, Feature.WAVE_MEA, "%.6f");
        row.writeDouble(wb, Feature.WAVE_FLIGHT_TIME, "%.2f");
        row.writeDouble(wb, Feature.WAVE_LATERAL_VELOCITY_AT_FIRE, "%.3f");
    }
}

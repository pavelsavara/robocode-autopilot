package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import cz.zamboch.autopilot.pipeline.IOfflineFeatures;

/**
 * Tier 1 wave / MEA / fire-timing features (per-tick, TICKS file).
 *
 * Computes closed-form properties of "our" hypothetical bullet and the opponent's
 * last detected bullet, plus elapsed-time markers since each fire event.
 *
 * Stateless: reads {@link Whiteboard#getLastOurFireTick()} /
 * {@link Whiteboard#getLastOurFirePower()} (set by Player on bullet detection)
 * and the corresponding opponent fields (set by EnergyFeatures on energy-drop).
 */
public final class WaveOfflineFeatures implements IOfflineFeatures {

    /** Default fire-power assumed when we have not fired yet. Mid-power. */
    private static final double DEFAULT_FIRE_POWER = 2.0;

    private static final Feature[] OUTPUTS = {
            Feature.OUR_BULLET_SPEED,
            Feature.OUR_BULLET_TRAVEL_TIME,
            Feature.MEA_FOR_OUR_BULLET,
            Feature.OPPONENT_BULLET_SPEED,
            Feature.MEA_FOR_OPPONENT_BULLET,
            Feature.TICKS_SINCE_WE_FIRED,
            Feature.TICKS_SINCE_OPPONENT_FIRED,
            Feature.OUR_WAVE_DISTANCE,
            Feature.OUR_WAVE_REMAINING,
            Feature.OPPONENT_WAVE_DISTANCE,
            Feature.OPPONENT_WAVE_REMAINING,
            Feature.OPPONENT_WAVE_ETA
    };

    private static final Feature[] DEPS = {
            Feature.DISTANCE
    };

    public Feature[] getOutputFeatures() { return OUTPUTS; }
    public Feature[] getDependencies() { return DEPS; }

    public void process(Whiteboard wb) {
        if (!wb.isScanAvailableThisTick() || !wb.hasFeature(Feature.DISTANCE)) {
            return;
        }
        double distance = wb.getFeature(Feature.DISTANCE);
        long tick = wb.getTick();

        // Our bullet (use last fired power if known; else default mid-power for analysis use)
        double ourPower = wb.getLastOurFireTick() >= 0
                ? wb.getLastOurFirePower()
                : DEFAULT_FIRE_POWER;
        double ourSpeed = 20.0 - 3.0 * ourPower;
        wb.setFeature(Feature.OUR_BULLET_SPEED, ourSpeed);
        wb.setFeature(Feature.OUR_BULLET_TRAVEL_TIME, distance / ourSpeed);
        wb.setFeature(Feature.MEA_FOR_OUR_BULLET, Math.asin(8.0 / ourSpeed));

        // Opponent bullet (only when at least one fire detected)
        if (wb.getLastOpponentFireTick() >= 0) {
            double oppPower = wb.getLastOpponentFirePower();
            double oppSpeed = 20.0 - 3.0 * oppPower;
            wb.setFeature(Feature.OPPONENT_BULLET_SPEED, oppSpeed);
            wb.setFeature(Feature.MEA_FOR_OPPONENT_BULLET, Math.asin(8.0 / oppSpeed));

            long ticksSinceOpp = tick - wb.getLastOpponentFireTick();
            wb.setFeature(Feature.TICKS_SINCE_OPPONENT_FIRED, ticksSinceOpp);

            double oppWaveDist = oppSpeed * ticksSinceOpp;
            wb.setFeature(Feature.OPPONENT_WAVE_DISTANCE, oppWaveDist);
            double oppRemaining = distance - oppWaveDist;
            wb.setFeature(Feature.OPPONENT_WAVE_REMAINING, oppRemaining);
            wb.setFeature(Feature.OPPONENT_WAVE_ETA, Math.max(0, oppRemaining / oppSpeed));
        }

        // Our wave timing (only when we have fired)
        if (wb.getLastOurFireTick() >= 0) {
            long ticksSinceUs = tick - wb.getLastOurFireTick();
            wb.setFeature(Feature.TICKS_SINCE_WE_FIRED, ticksSinceUs);
            double ourWaveDist = ourSpeed * ticksSinceUs;
            wb.setFeature(Feature.OUR_WAVE_DISTANCE, ourWaveDist);
            wb.setFeature(Feature.OUR_WAVE_REMAINING, distance - ourWaveDist);
        }
    }

    public FileType getFileType() { return FileType.TICKS; }

    public void writeColumnNames(CsvRowWriter row) {
        row.writeHeaders(
                "our_bullet_speed", "our_bullet_travel_time", "mea_for_our_bullet",
                "opponent_bullet_speed", "mea_for_opponent_bullet",
                "ticks_since_we_fired", "ticks_since_opponent_fired",
                "our_wave_distance", "our_wave_remaining",
                "opponent_wave_distance", "opponent_wave_remaining", "opponent_wave_eta");
    }

    public void writeRowValues(CsvRowWriter row, Whiteboard wb) {
        row.writeDouble(wb, Feature.OUR_BULLET_SPEED, "%.3f");
        row.writeDouble(wb, Feature.OUR_BULLET_TRAVEL_TIME, "%.3f");
        row.writeDouble(wb, Feature.MEA_FOR_OUR_BULLET, "%.6f");
        row.writeDouble(wb, Feature.OPPONENT_BULLET_SPEED, "%.3f");
        row.writeDouble(wb, Feature.MEA_FOR_OPPONENT_BULLET, "%.6f");
        row.writeInt(wb, Feature.TICKS_SINCE_WE_FIRED);
        row.writeInt(wb, Feature.TICKS_SINCE_OPPONENT_FIRED);
        row.writeDouble(wb, Feature.OUR_WAVE_DISTANCE, "%.3f");
        row.writeDouble(wb, Feature.OUR_WAVE_REMAINING, "%.3f");
        row.writeDouble(wb, Feature.OPPONENT_WAVE_DISTANCE, "%.3f");
        row.writeDouble(wb, Feature.OPPONENT_WAVE_REMAINING, "%.3f");
        row.writeDouble(wb, Feature.OPPONENT_WAVE_ETA, "%.3f");
    }
}

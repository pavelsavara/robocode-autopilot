package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.WaveRecord;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Tracks multi-wave state: adds newly detected opponent fires to the wave list,
 * prunes passed waves, and emits wave-count features.
 *
 * Depends on OPPONENT_FIRED, OPPONENT_FIRE_POWER, and DISTANCE (to know
 * when to add a wave and when to prune).
 */
public class MultiWaveFeatures implements IInGameFeatures {

    private static final Feature[] OUTPUTS = {
            Feature.N_OPPONENT_WAVES_IN_FLIGHT,
            Feature.N_OUR_WAVES_IN_FLIGHT
    };

    private static final Feature[] DEPS = {
            Feature.OPPONENT_FIRED,
            Feature.OPPONENT_FIRE_POWER,
            Feature.DISTANCE
    };

    public Feature[] getOutputFeatures() { return OUTPUTS; }

    public Feature[] getDependencies() { return DEPS; }

    public void process(Whiteboard wb) {
        // Add new opponent wave if fire detected this tick
        if (wb.hasFeature(Feature.OPPONENT_FIRED)
                && wb.getFeature(Feature.OPPONENT_FIRED) == 1.0
                && wb.hasFeature(Feature.DISTANCE)) {
            double power = wb.getFeature(Feature.OPPONENT_FIRE_POWER);
            double speed = 20.0 - 3.0 * power;
            double distance = wb.getFeature(Feature.DISTANCE);
            WaveRecord wave = new WaveRecord(
                    wb.getOpponentX(), wb.getOpponentY(),
                    speed, power, wb.getTick(), distance);
            wb.addOpponentWave(wave);
        }

        // Prune passed waves
        double distance = wb.hasFeature(Feature.DISTANCE)
                ? wb.getFeature(Feature.DISTANCE) : 0;
        wb.prunePassedWaves(distance);

        // Emit counts
        wb.setFeature(Feature.N_OPPONENT_WAVES_IN_FLIGHT,
                wb.getOpponentWaves().size());
        wb.setFeature(Feature.N_OUR_WAVES_IN_FLIGHT,
                wb.getOurWaves().size());
    }
}

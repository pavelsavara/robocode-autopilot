package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.WaveRecord;
import cz.zamboch.autopilot.core.Whiteboard;

import java.util.List;

/**
 * Tracks multi-wave state: adds newly detected opponent fires to the wave list,
 * prunes passed waves, and emits wave-count and wave-pressure features.
 *
 * Depends on OPPONENT_FIRED, OPPONENT_FIRE_POWER, and DISTANCE (to know
 * when to add a wave and when to prune).
 */
public class MultiWaveFeatures implements IInGameFeatures {

    private static final Feature[] OUTPUTS = {
            Feature.N_OPPONENT_WAVES_IN_FLIGHT,
            Feature.N_OUR_WAVES_IN_FLIGHT,
            Feature.NEAREST_OPPONENT_WAVE_GAP,
            Feature.TOTAL_OPPONENT_WAVE_DAMAGE,
            Feature.NEAREST_OUR_WAVE_GAP
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
            // Bearing from opponent (firer) to us (target) at fire time
            double fireBearing = Math.atan2(
                    wb.getOurX() - wb.getOpponentX(),
                    wb.getOurY() - wb.getOpponentY());
            WaveRecord wave = new WaveRecord(
                    wb.getOpponentX(), wb.getOpponentY(),
                    speed, power, wb.getTick(), distance, fireBearing);
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

        // Emit pressure features
        wb.setFeature(Feature.NEAREST_OPPONENT_WAVE_GAP,
                computeNearestGap(wb.getOpponentWaves()));
        wb.setFeature(Feature.TOTAL_OPPONENT_WAVE_DAMAGE,
                computeTotalDamage(wb.getOpponentWaves()));
        wb.setFeature(Feature.NEAREST_OUR_WAVE_GAP,
                computeNearestGap(wb.getOurWaves()));
    }

    /**
     * Compute the minimum tick-gap between adjacent waves (sorted by fire tick).
     * Returns 0 when fewer than 2 waves are in flight.
     */
    static long computeNearestGap(List<WaveRecord> waves) {
        int n = waves.size();
        if (n < 2) {
            return 0;
        }
        // Find the minimum fire-tick gap between any pair of waves.
        // Waves are roughly ordered by fire tick (added chronologically,
        // pruned from the front), so adjacent pairs dominate — but we
        // check all pairs for correctness with small N.
        long minGap = Long.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            long ti = waves.get(i).fireTick;
            for (int j = i + 1; j < n; j++) {
                long gap = Math.abs(waves.get(j).fireTick - ti);
                if (gap < minGap) {
                    minGap = gap;
                }
            }
        }
        return minGap;
    }

    /**
     * Sum of potential damage from all in-flight waves.
     */
    static double computeTotalDamage(List<WaveRecord> waves) {
        double total = 0;
        for (int i = 0; i < waves.size(); i++) {
            total += waves.get(i).damage();
        }
        return total;
    }
}

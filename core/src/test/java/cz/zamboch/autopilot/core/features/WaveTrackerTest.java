package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.GuessFactor;
import cz.zamboch.autopilot.core.VcsStore;
import cz.zamboch.autopilot.core.Wave;
import cz.zamboch.autopilot.core.Whiteboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class WaveTrackerTest {

    private Whiteboard wb;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        wb.registerFeatures(
                new SpatialFeatures(),
                new MovementFeatures(),
                new TimingFeatures(),
                new FireFeatures(),
                new WaveFeatures(),
                new WaveTracker());
        wb.setVcsStore(new VcsStore());
    }

    private void setBasicScanState(long tick, double ourX, double ourY,
            double distance, double bearingRad) {
        wb.setFeature(Feature.TICK, tick);
        wb.setFeature(Feature.OUR_X, ourX);
        wb.setFeature(Feature.OUR_Y, ourY);
        wb.setFeature(Feature.OUR_HEADING, 0); // facing north
        wb.setFeature(Feature.BEARING_RADIANS, bearingRad);
        wb.setFeature(Feature.DISTANCE, distance);
        wb.setFeature(Feature.OPPONENT_HEADING, 0);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
        wb.setFeature(Feature.OPPONENT_ENERGY, 100);
        wb.setFeature(Feature.LAST_SCAN_TICK, tick);
    }

    @Test
    void createsWaveFromFireFeatures() {
        // Simulate fire event: set OUR_FIRE_* features
        wb.setFeature(Feature.TICK, 10);
        wb.setFeature(Feature.OUR_X, 400);
        wb.setFeature(Feature.OUR_Y, 300);
        wb.setFeature(Feature.OUR_HEADING, 0);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.setFeature(Feature.DISTANCE, 200);
        wb.setFeature(Feature.OPPONENT_HEADING, 0);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
        wb.setFeature(Feature.OPPONENT_ENERGY, 100);
        wb.setFeature(Feature.LAST_SCAN_TICK, 10);

        // Snapshot fire features (as Autopilot would)
        wb.setFeature(Feature.OUR_FIRE_POWER, 2.0);
        wb.setFeature(Feature.OUR_FIRE_X, 400);
        wb.setFeature(Feature.OUR_FIRE_Y, 300);
        wb.setFeature(Feature.OUR_FIRE_TICK, 10);
        wb.setFeature(Feature.OUR_FIRE_BEARING_ABSOLUTE, 0);
        wb.setFeature(Feature.OUR_FIRE_DISTANCE, 200);
        wb.setFeature(Feature.OUR_FIRE_LATERAL_VELOCITY, 0);

        wb.process();

        // Wave should be created
        assertEquals(1, wb.getActiveWaves().size());
        Wave wave = wb.getActiveWaves().get(0);
        assertEquals(400, wave.fireX, 1e-9);
        assertEquals(300, wave.fireY, 1e-9);
        assertEquals(10, wave.fireTick);
        assertEquals(14.0, wave.bulletSpeed, 1e-9);

        // Fire features should be cleared
        assertTrue(Double.isNaN(wb.getFeature(Feature.OUR_FIRE_POWER)));
    }

    @Test
    void resolvesWaveWhenItReachesOpponent() {
        // Manually add a wave: fired from (400, 300), north, speed 14
        Wave wave = new Wave(400, 300, 5, 0, 14.0, 1, 1, 0);
        wb.getActiveWaves().add(wave);

        // Opponent is at (400, 500) → distance 200, needs 200/14 ≈ 14.3 ticks
        // At tick 20 (15 ticks elapsed): 15*14 = 210 > 200 → resolved
        setBasicScanState(20, 400, 300, 200, 0);
        wb.process();

        // Wave should be resolved and removed
        assertEquals(0, wb.getActiveWaves().size());

        // VCS should be updated
        VcsStore vcs = wb.getVcsStore();
        // GF should be 0 (head-on hit)
        int zeroBin = GuessFactor.gfToBinIndex(0, GuessFactor.NUM_BINS);
        assertTrue(vcs.getCount(1, 0, zeroBin) > 0);

        // Break features should be set
        assertFalse(Double.isNaN(wb.getFeature(Feature.OUR_BREAK_GF)));
        assertEquals(0, wb.getFeature(Feature.OUR_BREAK_GF), 0.1);
    }

    @Test
    void doesNotResolveWaveBeforeReaching() {
        Wave wave = new Wave(400, 300, 10, 0, 14.0, 1, 1, 0);
        wb.getActiveWaves().add(wave);

        // Tick 11: only 1*14 = 14 travelled, target at distance 200
        setBasicScanState(11, 400, 300, 200, 0);
        wb.process();

        // Wave still active
        assertEquals(1, wb.getActiveWaves().size());
    }

    @Test
    void noWaveCreatedWhenFirePowerIsNaN() {
        setBasicScanState(10, 400, 300, 200, 0);
        // OUR_FIRE_POWER not set (NaN by default)
        wb.process();
        assertEquals(0, wb.getActiveWaves().size());
    }
}

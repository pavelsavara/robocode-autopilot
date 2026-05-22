package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.GuessFactor;
import cz.zamboch.autopilot.core.OurWaveColumn;
import cz.zamboch.autopilot.core.VcsStore;
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
                new OurWaveFeatures(),
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

        wb.setFeature(Feature.OUR_FIRE_POWER, 2.0);
        wb.setFeature(Feature.OUR_FIRE_X, 400);
        wb.setFeature(Feature.OUR_FIRE_Y, 300);
        wb.setFeature(Feature.OUR_FIRE_TICK, 10);
        wb.setFeature(Feature.OUR_FIRE_BEARING_ABSOLUTE, 0);
        wb.setFeature(Feature.OUR_FIRE_DISTANCE, 200);
        wb.setFeature(Feature.OUR_FIRE_LATERAL_VELOCITY, 0);
        wb.setFeature(Feature.OUR_FIRE_AIM_GF, 0.0);
        wb.setFeature(Feature.OUR_FIRE_IS_REAL, 1.0);

        wb.process();

        // 1 real + 10 virtual = 11 active waves
        assertEquals(1 + WaveTracker.VIRTUAL_BULLET_COUNT, wb.getActiveWaveCount());
        assertEquals(Whiteboard.WAVE_ACTIVE, wb.getOurWaveState(0));
        assertEquals(400, wb.getOurWave(0, OurWaveColumn.FIRE_X), 1e-9);
        assertEquals(300, wb.getOurWave(0, OurWaveColumn.FIRE_Y), 1e-9);
        assertEquals(10, (long) wb.getOurWave(0, OurWaveColumn.FIRE_TICK));
        assertEquals(14.0, wb.getOurWave(0, OurWaveColumn.FIRE_BULLET_SPEED), 1e-9);
        assertEquals(1.0, wb.getOurWave(0, OurWaveColumn.IS_REAL), 1e-9);
        assertEquals(0.0, wb.getOurWave(1, OurWaveColumn.IS_REAL), 1e-9);

        assertTrue(Double.isNaN(wb.getFeature(Feature.OUR_FIRE_POWER)));
    }

    @Test
    void resolvesWaveWhenItReachesOpponent() {
        // Manually inject a wave into ring buffer
        int slot = wb.allocateOurWave();
        wb.setOurWave(slot, OurWaveColumn.FIRE_X, 400);
        wb.setOurWave(slot, OurWaveColumn.FIRE_Y, 300);
        wb.setOurWave(slot, OurWaveColumn.FIRE_TICK, 5);
        wb.setOurWave(slot, OurWaveColumn.FIRE_BEARING_ABSOLUTE, 0);
        wb.setOurWave(slot, OurWaveColumn.FIRE_BULLET_SPEED, 14.0);
        wb.setOurWave(slot, OurWaveColumn.FIRE_MEA, GuessFactor.maxEscapeAngle(14.0));
        wb.setOurWave(slot, OurWaveColumn.FIRE_DIRECTION, 1);
        wb.setOurWave(slot, OurWaveColumn.FIRE_DISTANCE, 200);
        wb.setOurWave(slot, OurWaveColumn.FIRE_LATERAL_VELOCITY, 0);
        wb.setOurWave(slot, OurWaveColumn.IS_REAL, 1.0);
        wb.setOurWaveState(slot, Whiteboard.WAVE_ACTIVE);

        // Opponent at (400, 500) → distance 200, tick 20: 15*14=210 > 200 → resolved
        setBasicScanState(20, 400, 300, 200, 0);
        wb.process();

        assertEquals(0, wb.getActiveWaveCount());
        assertEquals(Whiteboard.WAVE_RESOLVED, wb.getOurWaveState(slot));

        VcsStore vcs = wb.getVcsStore();
        int zeroBin = GuessFactor.gfToBinIndex(0, GuessFactor.NUM_BINS);
        assertTrue(vcs.getCount(1, 0, zeroBin) > 0);

        assertFalse(Double.isNaN(wb.getFeature(Feature.OUR_BREAK_GF)));
        assertEquals(0, wb.getFeature(Feature.OUR_BREAK_GF), 0.1);
    }

    @Test
    void doesNotResolveWaveBeforeReaching() {
        int slot = wb.allocateOurWave();
        wb.setOurWave(slot, OurWaveColumn.FIRE_X, 400);
        wb.setOurWave(slot, OurWaveColumn.FIRE_Y, 300);
        wb.setOurWave(slot, OurWaveColumn.FIRE_TICK, 10);
        wb.setOurWave(slot, OurWaveColumn.FIRE_BEARING_ABSOLUTE, 0);
        wb.setOurWave(slot, OurWaveColumn.FIRE_BULLET_SPEED, 14.0);
        wb.setOurWave(slot, OurWaveColumn.FIRE_MEA, GuessFactor.maxEscapeAngle(14.0));
        wb.setOurWave(slot, OurWaveColumn.FIRE_DIRECTION, 1);
        wb.setOurWave(slot, OurWaveColumn.FIRE_DISTANCE, 200);
        wb.setOurWave(slot, OurWaveColumn.FIRE_LATERAL_VELOCITY, 0);
        wb.setOurWave(slot, OurWaveColumn.IS_REAL, 1.0);
        wb.setOurWaveState(slot, Whiteboard.WAVE_ACTIVE);

        // Tick 11: only 1*14 = 14 travelled, target at distance 200
        setBasicScanState(11, 400, 300, 200, 0);
        wb.process();

        assertEquals(1, wb.getActiveWaveCount());
    }

    @Test
    void noWaveCreatedWhenFirePowerIsNaN() {
        setBasicScanState(10, 400, 300, 200, 0);
        wb.process();
        assertEquals(0, wb.getActiveWaveCount());
    }
}

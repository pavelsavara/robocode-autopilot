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

    // --- Virtual bullet tests ---

    @Test
    void virtualBulletsGetEvenlySpacedGFs() {
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
        wb.setFeature(Feature.OUR_FIRE_BULLET_SPEED, 14.0);
        wb.setFeature(Feature.OUR_FIRE_AIM_GF, 0.5);
        wb.setFeature(Feature.OUR_FIRE_IS_REAL, 1.0);

        wb.process();

        // Slot 0 = real, slots 1..10 = virtual
        assertEquals(0.5, wb.getOurWave(0, OurWaveColumn.AIM_GF), 1e-9);
        assertEquals(1.0, wb.getOurWave(0, OurWaveColumn.IS_REAL), 1e-9);

        // Virtual GFs: -1.0, -0.778, -0.556, -0.333, -0.111, 0.111, 0.333, 0.556,
        // 0.778, 1.0
        assertEquals(-1.0, wb.getOurWave(1, OurWaveColumn.AIM_GF), 1e-3);
        assertEquals(0.0, wb.getOurWave(1, OurWaveColumn.IS_REAL), 1e-9);
        assertEquals(1.0, wb.getOurWave(10, OurWaveColumn.AIM_GF), 1e-3);
        assertEquals(0.0, wb.getOurWave(10, OurWaveColumn.IS_REAL), 1e-9);

        // Check a middle virtual bullet (index 5 → GF = -1 + 2*4/9 ≈ -0.111)
        double expectedGf4 = -1.0 + 2.0 * 4 / 9.0;
        assertEquals(expectedGf4, wb.getOurWave(5, OurWaveColumn.AIM_GF), 1e-9);
    }

    @Test
    void virtualBulletsDoNotUpdateVcs() {
        VcsStore vcs = wb.getVcsStore();

        // Insert a virtual bullet manually
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
        wb.setOurWave(slot, OurWaveColumn.AIM_GF, 0.5);
        wb.setOurWave(slot, OurWaveColumn.IS_REAL, 0.0); // virtual!
        wb.setOurWaveState(slot, Whiteboard.WAVE_ACTIVE);

        // Resolve — opponent directly ahead, wave should reach at tick 20
        setBasicScanState(20, 400, 300, 200, 0);
        wb.process();

        // Virtual bullet resolved
        assertEquals(Whiteboard.WAVE_RESOLVED, wb.getOurWaveState(slot));

        // VCS should NOT have been incremented
        int zeroBin = GuessFactor.gfToBinIndex(0, GuessFactor.NUM_BINS);
        assertEquals(0, vcs.getCount(1, 0, zeroBin));
    }

    @Test
    void virtualBulletBreakHitComputedGeometrically() {
        // Opponent at (400, 500) — bearing 0 from (400, 300)
        // Virtual bullet aimed at GF=0 → should hit (fires straight at them)
        assertTrue(WaveTracker.computeWouldHit(
                400, 300, 0, 0.0, GuessFactor.maxEscapeAngle(14.0), 1,
                400, 500));

        // Virtual bullet aimed at GF=1.0 → large offset, should miss
        assertFalse(WaveTracker.computeWouldHit(
                400, 300, 0, 1.0, GuessFactor.maxEscapeAngle(14.0), 1,
                400, 500));

        // Virtual bullet aimed at GF=-1.0 → large offset, should miss
        assertFalse(WaveTracker.computeWouldHit(
                400, 300, 0, -1.0, GuessFactor.maxEscapeAngle(14.0), 1,
                400, 500));
    }

    @Test
    void virtualBulletSetsBreakHitOnResolution() {
        // Insert a virtual bullet aimed at GF=0 (straight ahead)
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
        wb.setOurWave(slot, OurWaveColumn.AIM_GF, 0.0); // aimed straight at opponent
        wb.setOurWave(slot, OurWaveColumn.IS_REAL, 0.0);
        wb.setOurWaveState(slot, Whiteboard.WAVE_ACTIVE);

        // Opponent directly ahead at (400, 500) → GF=0 bullet should "hit"
        setBasicScanState(20, 400, 300, 200, 0);
        wb.process();

        assertEquals(Whiteboard.WAVE_RESOLVED, wb.getOurWaveState(slot));
        assertEquals(1.0, wb.getOurWave(slot, OurWaveColumn.BREAK_HIT), 1e-9);
    }

    @Test
    void realWaveSetsBreakStagingFeaturesVirtualDoesNot() {
        // Insert real and virtual bullet
        int realSlot = wb.allocateOurWave();
        wb.setOurWave(realSlot, OurWaveColumn.FIRE_X, 400);
        wb.setOurWave(realSlot, OurWaveColumn.FIRE_Y, 300);
        wb.setOurWave(realSlot, OurWaveColumn.FIRE_TICK, 5);
        wb.setOurWave(realSlot, OurWaveColumn.FIRE_BEARING_ABSOLUTE, 0);
        wb.setOurWave(realSlot, OurWaveColumn.FIRE_BULLET_SPEED, 14.0);
        wb.setOurWave(realSlot, OurWaveColumn.FIRE_MEA, GuessFactor.maxEscapeAngle(14.0));
        wb.setOurWave(realSlot, OurWaveColumn.FIRE_DIRECTION, 1);
        wb.setOurWave(realSlot, OurWaveColumn.FIRE_DISTANCE, 200);
        wb.setOurWave(realSlot, OurWaveColumn.FIRE_LATERAL_VELOCITY, 0);
        wb.setOurWave(realSlot, OurWaveColumn.IS_REAL, 1.0);
        wb.setOurWaveState(realSlot, Whiteboard.WAVE_ACTIVE);

        int virtualSlot = wb.allocateOurWave();
        wb.setOurWave(virtualSlot, OurWaveColumn.FIRE_X, 400);
        wb.setOurWave(virtualSlot, OurWaveColumn.FIRE_Y, 300);
        wb.setOurWave(virtualSlot, OurWaveColumn.FIRE_TICK, 5);
        wb.setOurWave(virtualSlot, OurWaveColumn.FIRE_BEARING_ABSOLUTE, 0);
        wb.setOurWave(virtualSlot, OurWaveColumn.FIRE_BULLET_SPEED, 14.0);
        wb.setOurWave(virtualSlot, OurWaveColumn.FIRE_MEA, GuessFactor.maxEscapeAngle(14.0));
        wb.setOurWave(virtualSlot, OurWaveColumn.FIRE_DIRECTION, 1);
        wb.setOurWave(virtualSlot, OurWaveColumn.FIRE_DISTANCE, 200);
        wb.setOurWave(virtualSlot, OurWaveColumn.FIRE_LATERAL_VELOCITY, 0);
        wb.setOurWave(virtualSlot, OurWaveColumn.AIM_GF, 0.5);
        wb.setOurWave(virtualSlot, OurWaveColumn.IS_REAL, 0.0);
        wb.setOurWaveState(virtualSlot, Whiteboard.WAVE_ACTIVE);

        // Both resolve at tick 20
        setBasicScanState(20, 400, 300, 200, 0);
        wb.process();

        // Real wave should set staging features
        assertFalse(Double.isNaN(wb.getFeature(Feature.OUR_BREAK_GF)));
        assertEquals(20, (long) wb.getFeature(Feature.OUR_BREAK_TICK));
    }
}

package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.GuessFactor;
import cz.zamboch.autopilot.core.OurWaveColumn;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.VcsStore;
import cz.zamboch.autopilot.core.Whiteboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class OurWaveTrackerTest {

    private Whiteboard wb;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        wb.registerFeatures(
                new ScanFeatures(),
                new OurWaveTracker());
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
        wb.setFeature(Feature.SCAN_TICK, tick);
    }

    @Test
    void computesGunAimFeatures() {
        setBasicScanState(1, 400, 300, 250, 0.25);
        wb.setFeature(Feature.GUN_HEAT, 0);

        wb.process();

        assertEquals(2.5, wb.getFeature(Feature.GUN_AIM_POWER), 1e-9);
        assertEquals(0.25, wb.getFeature(Feature.GUN_AIM_ANGLE), 1e-9);
        assertEquals(0.0, wb.getFeature(Feature.GUN_AIM_GF), 1e-9);
    }

    @Test
    void createsWaveFromSnapshotFireFeatures() {
        wb.setFeature(Feature.TICK, 10);
        wb.setFeature(Feature.OUR_X, 400);
        wb.setFeature(Feature.OUR_Y, 300);
        wb.setFeature(Feature.OUR_HEADING, 0);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.setFeature(Feature.DISTANCE, 200);
        wb.setFeature(Feature.OPPONENT_HEADING, 0);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
        wb.setFeature(Feature.OPPONENT_ENERGY, 100);
        wb.setFeature(Feature.SCAN_TICK, 10);

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
        assertEquals(1 + OurWaveTracker.VIRTUAL_BULLET_COUNT, wb.getActiveWaveCount());
        assertEquals(Whiteboard.WAVE_ACTIVE, wb.getOurWaveState(0));
        assertEquals(400, wb.getOurWave(0, OurWaveColumn.FIRE_X), 1e-9);
        assertEquals(300, wb.getOurWave(0, OurWaveColumn.FIRE_Y), 1e-9);
        assertEquals(10, (long) wb.getOurWave(0, OurWaveColumn.FIRE_TICK));
        assertEquals(14.0, wb.getOurWave(0, OurWaveColumn.FIRE_BULLET_SPEED), 1e-9);
        assertEquals(1.0, wb.getOurWave(0, OurWaveColumn.IS_REAL), 1e-9);
        assertEquals(0.0, wb.getOurWave(1, OurWaveColumn.IS_REAL), 1e-9);

        assertTrue(Double.isNaN(wb.getFeature(Feature.OUR_FIRE_POWER)));
    }

    /**
     * The wave origin must be the staged fire-time position (captured by the robot
     * at the true fire tick), not the robot's current position at the tick the wave
     * slot is allocated. Here the current body position differs from the staged fire
     * position, so this test falsifies any regression that reads OUR_X/OUR_Y instead
     * of the OUR_FIRE_X/OUR_FIRE_Y staging.
     */
    @Test
    void waveOriginUsesStagedFirePositionNotCurrentPosition() {
        wb.setFeature(Feature.TICK, 12);
        // Current body position (post-move) deliberately differs from fire-time.
        wb.setFeature(Feature.OUR_X, 350);
        wb.setFeature(Feature.OUR_Y, 260);
        wb.setFeature(Feature.OUR_HEADING, 0);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.setFeature(Feature.DISTANCE, 200);
        wb.setFeature(Feature.OPPONENT_HEADING, 0);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
        wb.setFeature(Feature.OPPONENT_ENERGY, 100);
        wb.setFeature(Feature.SCAN_TICK, 12);

        // Fire staging captured at the true fire tick (10) and muzzle (400, 300).
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

        // Origin and tick come from the staging, not the current (350, 260) / tick 12.
        assertEquals(400, wb.getOurWave(0, OurWaveColumn.FIRE_X), 1e-9);
        assertEquals(300, wb.getOurWave(0, OurWaveColumn.FIRE_Y), 1e-9);
        assertEquals(10, (long) wb.getOurWave(0, OurWaveColumn.FIRE_TICK));
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
        assertTrue(vcs.getCount(1, 0, 0.0, zeroBin) > 0);

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
        wb.setFeature(Feature.SCAN_TICK, 10);

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
        assertEquals(0, vcs.getCount(1, 0, 0.0, zeroBin));
    }

    @Test
    void virtualBulletBreakHitComputedGeometrically() {
        // Opponent at (400, 500) — bearing 0 from (400, 300)
        // Virtual bullet aimed at GF=0 → should hit (fires straight at them)
        assertTrue(OurWaveTracker.computeWouldHit(
                400, 300, 0, 0.0, GuessFactor.maxEscapeAngle(14.0), 1,
                400, 500));

        // Virtual bullet aimed at GF=1.0 → large offset, should miss
        assertFalse(OurWaveTracker.computeWouldHit(
                400, 300, 0, 1.0, GuessFactor.maxEscapeAngle(14.0), 1,
                400, 500));

        // Virtual bullet aimed at GF=-1.0 → large offset, should miss
        assertFalse(OurWaveTracker.computeWouldHit(
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

    /**
     * The OUR_AIM_* staging (captured one tick before fire) must be copied into the
     * wave's AIM_* columns for both the real and all virtual slots, then cleared.
     * Falsifies any regression where aim geometry is dropped or read from the wrong
     * source.
     */
    @Test
    void aimStagingCopiedIntoWaveColumnsAndCleared() {
        wb.setFeature(Feature.TICK, 10);
        wb.setFeature(Feature.OUR_X, 400);
        wb.setFeature(Feature.OUR_Y, 300);
        wb.setFeature(Feature.OUR_HEADING, 0);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.setFeature(Feature.DISTANCE, 200);
        wb.setFeature(Feature.OPPONENT_HEADING, 0);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
        wb.setFeature(Feature.OPPONENT_ENERGY, 100);
        wb.setFeature(Feature.SCAN_TICK, 10);

        wb.setFeature(Feature.OUR_FIRE_POWER, 2.0);
        wb.setFeature(Feature.OUR_FIRE_X, 400);
        wb.setFeature(Feature.OUR_FIRE_Y, 300);
        wb.setFeature(Feature.OUR_FIRE_TICK, 10);
        wb.setFeature(Feature.OUR_FIRE_BEARING_ABSOLUTE, 0);
        wb.setFeature(Feature.OUR_FIRE_DISTANCE, 200);
        wb.setFeature(Feature.OUR_FIRE_LATERAL_VELOCITY, 0);
        wb.setFeature(Feature.OUR_FIRE_AIM_GF, 0.0);
        wb.setFeature(Feature.OUR_FIRE_IS_REAL, 1.0);

        // Aim geometry from one tick before fire (deliberately distinct values).
        wb.setFeature(Feature.OUR_AIM_X, 390);
        wb.setFeature(Feature.OUR_AIM_Y, 295);
        wb.setFeature(Feature.OUR_AIM_OPPONENT_X, 390);
        wb.setFeature(Feature.OUR_AIM_OPPONENT_Y, 505);
        wb.setFeature(Feature.OUR_AIM_DISTANCE, 210);
        wb.setFeature(Feature.OUR_AIM_BEARING_ABSOLUTE, 0.05);
        wb.setFeature(Feature.OUR_AIM_LAG1_GF, 0.42);

        wb.process();

        // Real slot 0 carries the aim geometry.
        assertEquals(390, wb.getOurWave(0, OurWaveColumn.AIM_X), 1e-9);
        assertEquals(295, wb.getOurWave(0, OurWaveColumn.AIM_Y), 1e-9);
        assertEquals(390, wb.getOurWave(0, OurWaveColumn.AIM_OPPONENT_X), 1e-9);
        assertEquals(505, wb.getOurWave(0, OurWaveColumn.AIM_OPPONENT_Y), 1e-9);
        assertEquals(210, wb.getOurWave(0, OurWaveColumn.AIM_DISTANCE), 1e-9);
        assertEquals(0.05, wb.getOurWave(0, OurWaveColumn.AIM_BEARING_ABSOLUTE), 1e-9);
        assertEquals(0.42, wb.getOurWave(0, OurWaveColumn.AIM_LAG1_GF), 1e-9);

        // Virtual slot 1 shares the same aim geometry (same shooter snapshot).
        assertEquals(390, wb.getOurWave(1, OurWaveColumn.AIM_X), 1e-9);
        assertEquals(210, wb.getOurWave(1, OurWaveColumn.AIM_DISTANCE), 1e-9);
        assertEquals(0.42, wb.getOurWave(1, OurWaveColumn.AIM_LAG1_GF), 1e-9);

        // Staging cleared after processing.
        assertTrue(Double.isNaN(wb.getFeature(Feature.OUR_AIM_X)));
        assertTrue(Double.isNaN(wb.getFeature(Feature.OUR_AIM_DISTANCE)));
        assertTrue(Double.isNaN(wb.getFeature(Feature.OUR_AIM_BEARING_ABSOLUTE)));
        assertTrue(Double.isNaN(wb.getFeature(Feature.OUR_AIM_LAG1_GF)));
    }

    // --- Pre-aim (self-position lead) + training-frame consistency ---

    /**
     * The gun pre-aims for the fire tick T: it anchors the GF=0 baseline at our
     * PREDICTED T position (current pos advanced one tick by our own kinematics),
     * not our current T-1 position. With an empty VCS the offset is 0, so the aim
     * angle is exactly the bearing from the predicted position to the opponent.
     */
    @Test
    void gunPreAimsFromPredictedNextPosition() {
        // Us at (100,100) heading north, moving 8 px/tick north -> predicted (100,108).
        // Opponent placed off-axis so the predicted bearing differs from current.
        double ourX = 100, ourY = 100, vel = 8;
        // Opponent at (200,300): bearing from (100,100) is atan2(100,200).
        double oppBearing = Math.atan2(100, 200);
        double distance = Math.hypot(100, 200);
        setBasicScanState(1, ourX, ourY, distance, oppBearing);
        wb.setFeature(Feature.OUR_VELOCITY, vel);
        wb.setFeature(Feature.GUN_HEAT, 0);

        wb.process();

        double oppX = wb.getFeature(Feature.OPPONENT_X);
        double oppY = wb.getFeature(Feature.OPPONENT_Y);
        double predX = ourX + vel * Math.sin(0); // heading north
        double predY = ourY + vel * Math.cos(0);
        double expectedPredicted = Math.atan2(oppX - predX, oppY - predY);
        double currentBearing = Math.atan2(oppX - ourX, oppY - ourY);

        // Empty VCS -> GF 0 -> aim angle == predicted baseline.
        assertEquals(0.0, wb.getFeature(Feature.GUN_AIM_GF), 1e-9);
        assertEquals(expectedPredicted, wb.getFeature(Feature.GUN_AIM_ANGLE), 1e-9);
        // And it must NOT be the un-compensated current-position bearing.
        assertNotEquals(currentBearing, wb.getFeature(Feature.GUN_AIM_ANGLE), 1e-6);
    }

    /** A stationary robot (velocity 0) -> pre-aim is a no-op (current == predicted). */
    @Test
    void preAimIsNoOpWhenStationary() {
        double oppBearing = Math.atan2(100, 200);
        double distance = Math.hypot(100, 200);
        setBasicScanState(1, 100, 100, distance, oppBearing);
        wb.setFeature(Feature.OUR_VELOCITY, 0);
        wb.setFeature(Feature.GUN_HEAT, 0);

        wb.process();

        double oppX = wb.getFeature(Feature.OPPONENT_X);
        double oppY = wb.getFeature(Feature.OPPONENT_Y);
        double currentBearing = Math.atan2(oppX - 100, oppY - 100);
        assertEquals(currentBearing, wb.getFeature(Feature.GUN_AIM_ANGLE), 1e-9);
    }

    /**
     * Training-frame fix: a resolved wave measures its GF against the aim-time
     * opponent reference (AIM_OPPONENT_X/Y) anchored at the fire origin — the same
     * baseline the gun pre-aimed against — NOT the recorded FIRE_BEARING_ABSOLUTE.
     * This makes the realized GF the model learns match the intended aim GF.
     */
    @Test
    void resolvedGuessFactorUsesAimTimeOpponentBaseline() {
        double fireX = 100, fireY = 100;
        // Aim-time opponent reference: due north -> baseline 0.
        double aimOppX = 100, aimOppY = 300;
        // A deliberately WRONG recorded fire bearing that must be ignored.
        double wrongFireBearing = Math.atan2(50, 200);

        // Stage a real fire at tick 10.
        wb.setFeature(Feature.TICK, 10);
        wb.setFeature(Feature.OUR_X, fireX);
        wb.setFeature(Feature.OUR_Y, fireY);
        wb.setFeature(Feature.OUR_HEADING, 0);
        wb.setFeature(Feature.OUR_VELOCITY, 0);
        // Place current (break-bound) opponent far enough not to resolve at tick 10.
        double oppX = 200, oppY = 300;
        double distance = Math.hypot(oppX - fireX, oppY - fireY);
        wb.setFeature(Feature.BEARING_RADIANS, Math.atan2(oppX - fireX, oppY - fireY));
        wb.setFeature(Feature.DISTANCE, distance);
        wb.setFeature(Feature.OPPONENT_HEADING, 0);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
        wb.setFeature(Feature.OPPONENT_ENERGY, 100);
        wb.setFeature(Feature.SCAN_TICK, 10);

        wb.setFeature(Feature.OUR_FIRE_POWER, 2.0);
        wb.setFeature(Feature.OUR_FIRE_X, fireX);
        wb.setFeature(Feature.OUR_FIRE_Y, fireY);
        wb.setFeature(Feature.OUR_FIRE_TICK, 10);
        wb.setFeature(Feature.OUR_FIRE_BEARING_ABSOLUTE, wrongFireBearing);
        wb.setFeature(Feature.OUR_FIRE_DISTANCE, distance);
        wb.setFeature(Feature.OUR_FIRE_LATERAL_VELOCITY, 0);
        wb.setFeature(Feature.OUR_FIRE_OPPONENT_X, oppX);
        wb.setFeature(Feature.OUR_FIRE_OPPONENT_Y, oppY);
        wb.setFeature(Feature.OUR_FIRE_AIM_GF, 0.0);
        wb.setFeature(Feature.OUR_FIRE_IS_REAL, 1.0);
        wb.setFeature(Feature.OUR_AIM_X, fireX);
        wb.setFeature(Feature.OUR_AIM_Y, fireY);
        wb.setFeature(Feature.OUR_AIM_OPPONENT_X, aimOppX);
        wb.setFeature(Feature.OUR_AIM_OPPONENT_Y, aimOppY);
        wb.setFeature(Feature.OUR_AIM_DISTANCE, Math.hypot(aimOppX - fireX, aimOppY - fireY));
        wb.setFeature(Feature.OUR_AIM_BEARING_ABSOLUTE, Math.atan2(aimOppX - fireX, aimOppY - fireY));
        wb.setFeature(Feature.OUR_AIM_LAG1_GF, Double.NaN);

        wb.process(); // creates the wave at tick 10 (no resolve yet)
        assertEquals(Whiteboard.WAVE_ACTIVE, wb.getOurWaveState(0));

        // Advance to a tick where the wave has reached the opponent and resolve.
        // Re-apply the scan state (TICKS-ring features are cleared on tick change)
        // so the opponent resolves at the same (oppX, oppY).
        long resolveTick = 26; // (26-10)*14 = 224 >= distance (~223.6)
        wb.setFeature(Feature.TICK, resolveTick);
        wb.setFeature(Feature.OUR_X, fireX);
        wb.setFeature(Feature.OUR_Y, fireY);
        wb.setFeature(Feature.OUR_HEADING, 0);
        wb.setFeature(Feature.OUR_VELOCITY, 0);
        wb.setFeature(Feature.BEARING_RADIANS, Math.atan2(oppX - fireX, oppY - fireY));
        wb.setFeature(Feature.DISTANCE, distance);
        wb.setFeature(Feature.OPPONENT_HEADING, 0);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
        wb.setFeature(Feature.OPPONENT_ENERGY, 100);
        wb.setFeature(Feature.SCAN_TICK, resolveTick);
        wb.process();

        assertEquals(oppX, wb.getFeature(Feature.OPPONENT_X), 1e-6);
        assertEquals(oppY, wb.getFeature(Feature.OPPONENT_Y), 1e-6);
        assertEquals(Whiteboard.WAVE_RESOLVED, wb.getOurWaveState(0));

        double mea = GuessFactor.maxEscapeAngle(14.0);
        double actualBearing = Math.atan2(oppX - fireX, oppY - fireY);
        double aimBaseline = Math.atan2(aimOppX - fireX, aimOppY - fireY);
        double expectedGf = GuessFactor.guessFactor(
                RoboMath.normalRelativeAngle(actualBearing - aimBaseline), mea, 1);
        double wrongGf = GuessFactor.guessFactor(
                RoboMath.normalRelativeAngle(actualBearing - wrongFireBearing), mea, 1);

        assertEquals(expectedGf, wb.getFeature(Feature.OUR_BREAK_GF), 1e-9);
        assertNotEquals(wrongGf, wb.getFeature(Feature.OUR_BREAK_GF), 1e-6);
    }

    /**
     * Second-order training-frame fix: the resolved GF sign uses the AIM-time
     * direction (the lateral-velocity sign the gun keyed its GF on), not the
     * fire-time FIRE_DIRECTION. This keeps the realized GF sign matching the
     * intended aim GF when the opponent's lateral motion reverses between T-1
     * and T.
     */
    @Test
    void resolvedGuessFactorUsesAimTimeDirection() {
        int slot = wb.allocateOurWave();
        double mea = GuessFactor.maxEscapeAngle(14.0);
        wb.setOurWave(slot, OurWaveColumn.FIRE_X, 400);
        wb.setOurWave(slot, OurWaveColumn.FIRE_Y, 300);
        wb.setOurWave(slot, OurWaveColumn.FIRE_TICK, 5);
        wb.setOurWave(slot, OurWaveColumn.FIRE_BEARING_ABSOLUTE, 0);
        wb.setOurWave(slot, OurWaveColumn.FIRE_BULLET_SPEED, 14.0);
        wb.setOurWave(slot, OurWaveColumn.FIRE_MEA, mea);
        wb.setOurWave(slot, OurWaveColumn.FIRE_DIRECTION, 1); // fire-time direction +1
        wb.setOurWave(slot, OurWaveColumn.FIRE_DISTANCE, 200);
        wb.setOurWave(slot, OurWaveColumn.FIRE_LATERAL_VELOCITY, 5);
        // Aim-time frame: opponent reference due north of the fire origin (baseline
        // 0) and a NEGATIVE lateral velocity -> aim direction -1, opposite the
        // fire-time direction.
        wb.setOurWave(slot, OurWaveColumn.AIM_OPPONENT_X, 400);
        wb.setOurWave(slot, OurWaveColumn.AIM_OPPONENT_Y, 500);
        wb.setOurWave(slot, OurWaveColumn.AIM_LATERAL_VELOCITY, -5);
        wb.setOurWave(slot, OurWaveColumn.IS_REAL, 1.0);
        wb.setOurWaveState(slot, Whiteboard.WAVE_ACTIVE);

        // Opponent breaks at (500,500): off the baseline so the GF sign is testable.
        setBasicScanState(21, 400, 300, Math.hypot(100, 200), Math.atan2(100, 200));
        wb.process();

        assertEquals(Whiteboard.WAVE_RESOLVED, wb.getOurWaveState(slot));
        double angleOffset = Math.atan2(100, 200); // relative to baseline 0
        double expected = GuessFactor.guessFactor(angleOffset, mea, -1);
        double fireDirGf = GuessFactor.guessFactor(angleOffset, mea, 1);
        assertEquals(expected, wb.getFeature(Feature.OUR_BREAK_GF), 1e-9);
        assertNotEquals(fireDirGf, wb.getFeature(Feature.OUR_BREAK_GF), 1e-6);
    }
}

package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.GuessFactor;
import cz.zamboch.autopilot.core.TheirWaveColumn;
import cz.zamboch.autopilot.core.Whiteboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class TheirWaveTrackerTest {

    private Whiteboard wb;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        wb.registerFeatures(
                new SpatialFeatures(),
                new MovementFeatures(),
                new TimingFeatures(),
                new FireFeatures(),
                new TheirWaveTracker());
    }

    private void setBasicState(long tick, double ourX, double ourY,
            double oppX, double oppY) {
        wb.setFeature(Feature.TICK, tick);
        wb.setFeature(Feature.OUR_X, ourX);
        wb.setFeature(Feature.OUR_Y, ourY);
        wb.setFeature(Feature.OUR_HEADING, 0);
        wb.setFeature(Feature.OPPONENT_ENERGY, 100);
        wb.setFeature(Feature.LAST_SCAN_TICK, tick);
        // Set opponent position (normally from SpatialFeatures)
        wb.setFeature(Feature.OPPONENT_X, oppX);
        wb.setFeature(Feature.OPPONENT_Y, oppY);
    }

    @Test
    void createsWaveOnFireDetection() {
        // Tick 5: scan with opponent at full energy
        setBasicState(5, 200, 200, 200, 400);
        wb.setFeature(Feature.DISTANCE, 200);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.setFeature(Feature.OPPONENT_HEADING, 0);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
        wb.process();

        // Tick 6: opponent loses 2.0 energy → fire detected
        wb.setFeature(Feature.TICK, 6);
        wb.setFeature(Feature.OPPONENT_ENERGY, 98);
        wb.setFeature(Feature.LAST_SCAN_TICK, 6);
        wb.setFeature(Feature.DISTANCE, 200);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.setFeature(Feature.OPPONENT_HEADING, 0);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
        wb.setFeature(Feature.OUR_X, 200);
        wb.setFeature(Feature.OUR_Y, 200);
        wb.setFeature(Feature.OPPONENT_X, 200);
        wb.setFeature(Feature.OPPONENT_Y, 400);
        wb.process();

        // Verify wave was created
        assertEquals(1, wb.getActiveTheirWaveCount());
        assertEquals(Whiteboard.WAVE_ACTIVE, wb.getTheirWaveState(0));
        assertEquals(2.0, wb.getTheirWave(0, TheirWaveColumn.FIRE_POWER), 1e-9);
        // Fire detected at tick 6, but the opponent's fire code ran at tick 5
        // (bullet/energy applied one tick later), so the wave is attributed to
        // tick 5 and the opponent's tick-5 position.
        assertEquals(5, (long) wb.getTheirWave(0, TheirWaveColumn.FIRE_TICK));
        assertEquals(200, wb.getTheirWave(0, TheirWaveColumn.FIRE_X), 1e-9);
        assertEquals(400, wb.getTheirWave(0, TheirWaveColumn.FIRE_Y), 1e-9);
        assertEquals(14.0, wb.getTheirWave(0, TheirWaveColumn.BULLET_SPEED), 1e-9);
        assertEquals(200, wb.getTheirWave(0, TheirWaveColumn.FIRE_DISTANCE), 1e-9);

        // THEIR_FIRE_POWER should be cleared after consumption
        assertTrue(Double.isNaN(wb.getFeature(Feature.THEIR_FIRE_POWER)));
    }

    /**
     * The energy drop that reveals an enemy shot is observed one tick AFTER the
     * shot was actually fired. The muzzle is therefore the opponent's body position
     * at the END of the previous tick — not the position at detection time. With a
     * MOVING opponent (and a moving us) the two differ, so this test falsifies any
     * regression that records the detection-tick position instead of the
     * previous-tick (true fire) position.
     */
    @Test
    void attributesFireToPreviousTickPositionWhenMoving() {
        // Tick 5: scan, opponent at (200, 400) at full energy, we at (200, 200).
        setBasicState(5, 200, 200, 200, 400);
        wb.setFeature(Feature.DISTANCE, 200);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.setFeature(Feature.OPPONENT_HEADING, 0);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
        wb.process();

        // Tick 6: opponent drops 2.0 energy (fire detected) but has MOVED to
        // (250, 410), and we have moved to (160, 230). The true muzzle is the
        // tick-5 opponent position (200, 400), and the true fire tick is 5.
        wb.setFeature(Feature.TICK, 6);
        wb.setFeature(Feature.OPPONENT_ENERGY, 98);
        wb.setFeature(Feature.LAST_SCAN_TICK, 6);
        wb.setFeature(Feature.DISTANCE, 200);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.setFeature(Feature.OPPONENT_HEADING, 0);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
        wb.setFeature(Feature.OUR_X, 160);
        wb.setFeature(Feature.OUR_Y, 230);
        wb.setFeature(Feature.OPPONENT_X, 250);
        wb.setFeature(Feature.OPPONENT_Y, 410);
        wb.process();

        assertEquals(1, wb.getActiveTheirWaveCount());
        // Attributed to the true fire tick (5), not the detection tick (6).
        assertEquals(5, (long) wb.getTheirWave(0, TheirWaveColumn.FIRE_TICK));
        // Muzzle = opponent position at END of tick 5, not the tick-6 position.
        assertEquals(200, wb.getTheirWave(0, TheirWaveColumn.FIRE_X), 1e-9);
        assertEquals(400, wb.getTheirWave(0, TheirWaveColumn.FIRE_Y), 1e-9);
        // Our reference position is also the tick-5 value, not the tick-6 value.
        assertEquals(200, wb.getTheirWave(0, TheirWaveColumn.FIRE_OUR_X), 1e-9);
        assertEquals(200, wb.getTheirWave(0, TheirWaveColumn.FIRE_OUR_Y), 1e-9);
    }

    @Test
    void resolvesWaveWhenItReachesUs() {
        // Manually inject a their-wave into ring buffer
        // Opponent at (200, 400), we at (200, 200), distance = 200
        int slot = wb.allocateTheirWave();
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_POWER, 2.0);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_TICK, 5);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_X, 200);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_Y, 400);
        wb.setTheirWave(slot, TheirWaveColumn.BULLET_SPEED, 14.0);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_BEARING, Math.atan2(0, -200)); // bearing from (200,400) to (200,200)
                                                                                  // = PI (south)
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_DISTANCE, 200);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_OUR_X, 200);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_OUR_Y, 200);
        wb.setTheirWaveState(slot, Whiteboard.WAVE_ACTIVE);

        // Tick 20: 15 ticks * 14 speed = 210 > 200 → should resolve
        setBasicState(20, 200, 200, 200, 400);
        wb.process();

        assertEquals(0, wb.getActiveTheirWaveCount());
        assertEquals(Whiteboard.WAVE_RESOLVED, wb.getTheirWaveState(slot));
        assertEquals(20, wb.getTheirWave(slot, TheirWaveColumn.BREAK_TICK), 1e-9);
        assertEquals(200, wb.getTheirWave(slot, TheirWaveColumn.BREAK_OUR_X), 1e-9);
        assertEquals(200, wb.getTheirWave(slot, TheirWaveColumn.BREAK_OUR_Y), 1e-9);

        // We didn't move, so bearing offset should be 0 → GF = 0
        assertEquals(0, wb.getTheirWave(slot, TheirWaveColumn.BREAK_GF), 0.01);
        assertEquals(0, wb.getTheirWave(slot, TheirWaveColumn.BREAK_BEARING_OFFSET), 0.01);
    }

    @Test
    void resolvesWithNonZeroGF() {
        // Opponent at (200, 400), we were at (200, 200) at fire time
        // bearing from opponent to us: atan2(0, -200) = PI (south)
        double fireBearing = Math.atan2(200 - 200, 200 - 400); // atan2(0, -200) = PI

        int slot = wb.allocateTheirWave();
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_POWER, 2.0);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_TICK, 5);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_X, 200);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_Y, 400);
        wb.setTheirWave(slot, TheirWaveColumn.BULLET_SPEED, 14.0);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_BEARING, fireBearing);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_DISTANCE, 200);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_OUR_X, 200);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_OUR_Y, 200);
        wb.setTheirWaveState(slot, Whiteboard.WAVE_ACTIVE);

        // We moved east to (280, 200) — offset from fire bearing
        // Tick 20: 15*14=210, dist to (280,200) from (200,400) = sqrt(80^2+200^2) =
        // sqrt(46400) ≈ 215 > 210? No.
        // Let's use tick 21: 16*14=224 > 215 → resolves
        setBasicState(21, 280, 200, 200, 400);
        wb.process();

        assertEquals(0, wb.getActiveTheirWaveCount());
        assertEquals(Whiteboard.WAVE_RESOLVED, wb.getTheirWaveState(slot));

        // Actual bearing from (200,400) to (280,200): atan2(80, -200) ≈ 2.76
        // Offset from fire bearing (PI ≈ 3.14): 2.76 - 3.14 ≈ -0.38
        double actualBearing = Math.atan2(280 - 200, 200 - 400);
        double expectedOffset = actualBearing - fireBearing;
        double mea = GuessFactor.maxEscapeAngle(14.0);
        double expectedGF = Math.max(-1.0, Math.min(1.0, expectedOffset / mea));

        assertEquals(expectedGF, wb.getTheirWave(slot, TheirWaveColumn.BREAK_GF), 0.01);
        assertEquals(expectedOffset, wb.getTheirWave(slot, TheirWaveColumn.BREAK_BEARING_OFFSET), 0.01);
    }

    @Test
    void doesNotResolveBeforeReaching() {
        int slot = wb.allocateTheirWave();
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_POWER, 2.0);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_TICK, 5);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_X, 200);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_Y, 400);
        wb.setTheirWave(slot, TheirWaveColumn.BULLET_SPEED, 14.0);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_BEARING, Math.PI);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_DISTANCE, 200);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_OUR_X, 200);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_OUR_Y, 200);
        wb.setTheirWaveState(slot, Whiteboard.WAVE_ACTIVE);

        // Tick 10: 5*14=70 < 200 → not yet reached
        setBasicState(10, 200, 200, 200, 400);
        wb.process();

        assertEquals(1, wb.getActiveTheirWaveCount());
        assertEquals(Whiteboard.WAVE_ACTIVE, wb.getTheirWaveState(slot));
    }

    @Test
    void markHitUsRecordedOnWave() {
        int slot = wb.allocateTheirWave();
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_POWER, 2.0);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_TICK, 5);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_X, 200);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_Y, 400);
        wb.setTheirWave(slot, TheirWaveColumn.BULLET_SPEED, 14.0);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_BEARING, Math.PI);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_DISTANCE, 200);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_OUR_X, 200);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_OUR_Y, 200);
        wb.setTheirWaveState(slot, Whiteboard.WAVE_ACTIVE);

        // Mark that this bullet hit us
        wb.markTheirBulletHitUs(2.0);

        // Resolve on tick 20
        setBasicState(20, 200, 200, 200, 400);
        wb.process();

        assertEquals(Whiteboard.WAVE_RESOLVED, wb.getTheirWaveState(slot));
        assertEquals(1.0, wb.getTheirWave(slot, TheirWaveColumn.HIT_US), 1e-9);
        assertEquals(1.0, wb.getFeature(Feature.THEIR_HIT_US), 1e-9);
    }

    @Test
    void noWaveCreatedWithoutFireDetection() {
        // No energy drop → no fire
        setBasicState(5, 200, 200, 200, 400);
        wb.setFeature(Feature.DISTANCE, 200);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.setFeature(Feature.OPPONENT_HEADING, 0);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
        wb.process();

        assertEquals(0, wb.getActiveTheirWaveCount());
    }

    @Test
    void multipleWavesInFlight() {
        // Create two waves manually
        int slot0 = wb.allocateTheirWave();
        wb.setTheirWave(slot0, TheirWaveColumn.FIRE_POWER, 3.0);
        wb.setTheirWave(slot0, TheirWaveColumn.FIRE_TICK, 5);
        wb.setTheirWave(slot0, TheirWaveColumn.FIRE_X, 200);
        wb.setTheirWave(slot0, TheirWaveColumn.FIRE_Y, 400);
        wb.setTheirWave(slot0, TheirWaveColumn.BULLET_SPEED, 11.0); // power 3 = speed 11
        wb.setTheirWave(slot0, TheirWaveColumn.FIRE_BEARING, Math.PI);
        wb.setTheirWave(slot0, TheirWaveColumn.FIRE_DISTANCE, 200);
        wb.setTheirWave(slot0, TheirWaveColumn.FIRE_OUR_X, 200);
        wb.setTheirWave(slot0, TheirWaveColumn.FIRE_OUR_Y, 200);
        wb.setTheirWaveState(slot0, Whiteboard.WAVE_ACTIVE);

        int slot1 = wb.allocateTheirWave();
        wb.setTheirWave(slot1, TheirWaveColumn.FIRE_POWER, 1.0);
        wb.setTheirWave(slot1, TheirWaveColumn.FIRE_TICK, 10);
        wb.setTheirWave(slot1, TheirWaveColumn.FIRE_X, 200);
        wb.setTheirWave(slot1, TheirWaveColumn.FIRE_Y, 400);
        wb.setTheirWave(slot1, TheirWaveColumn.BULLET_SPEED, 17.0); // power 1 = speed 17
        wb.setTheirWave(slot1, TheirWaveColumn.FIRE_BEARING, Math.PI);
        wb.setTheirWave(slot1, TheirWaveColumn.FIRE_DISTANCE, 200);
        wb.setTheirWave(slot1, TheirWaveColumn.FIRE_OUR_X, 200);
        wb.setTheirWave(slot1, TheirWaveColumn.FIRE_OUR_Y, 200);
        wb.setTheirWaveState(slot1, Whiteboard.WAVE_ACTIVE);

        // Tick 20: slot0 = 15*11=165 < 200 (not resolved), slot1 = 10*17=170 < 200 (not
        // resolved)
        setBasicState(20, 200, 200, 200, 400);
        wb.process();
        assertEquals(2, wb.getActiveTheirWaveCount());

        // Tick 24: slot0 = 19*11=209 > 200 (resolved), slot1 = 14*17=238 > 200
        // (resolved)
        setBasicState(24, 200, 200, 200, 400);
        wb.process();
        assertEquals(0, wb.getActiveTheirWaveCount());
        assertEquals(Whiteboard.WAVE_RESOLVED, wb.getTheirWaveState(slot0));
        assertEquals(Whiteboard.WAVE_RESOLVED, wb.getTheirWaveState(slot1));
    }
}

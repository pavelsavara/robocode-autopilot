package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * God-view tests for MovementSegmentationOfflineFeatures.
 * Uses hand-crafted Whiteboard state with known values.
 */
class MovementSegmentationOfflineFeaturesTest {

    private Whiteboard wb;
    private MovementSegmentationOfflineFeatures feat;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        feat = new MovementSegmentationOfflineFeatures();
        wb.onRoundStart(0, 800, 600, 0.1, 10);
    }

    private void scanWithLateralVel(long tick, double latVel, double vel) {
        wb.setTick(tick);
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setOpponentScan(500, 400, 0, vel, 80);
        wb.setFeature(Feature.OPPONENT_LATERAL_VELOCITY, latVel);
        wb.setFeature(Feature.OPPONENT_VELOCITY, vel);
    }

    @Test
    void lateralDirectionPositive() {
        scanWithLateralVel(0, 3.5, 5.0);
        feat.process(wb);
        assertEquals(1.0, wb.getFeature(Feature.OPPONENT_LATERAL_DIRECTION), 0.001);
    }

    @Test
    void lateralDirectionNegative() {
        scanWithLateralVel(0, -2.0, 5.0);
        feat.process(wb);
        assertEquals(-1.0, wb.getFeature(Feature.OPPONENT_LATERAL_DIRECTION), 0.001);
    }

    @Test
    void lateralDirectionZero() {
        scanWithLateralVel(0, 0.0, 0.0);
        feat.process(wb);
        assertEquals(0.0, wb.getFeature(Feature.OPPONENT_LATERAL_DIRECTION), 0.001);
    }

    @Test
    void velocityDeltaOnConsecutiveScans() {
        // First scan at tick 0
        scanWithLateralVel(0, 3.0, 5.0);
        feat.process(wb);
        wb.advanceTick();

        // Second scan at tick 1
        scanWithLateralVel(1, 3.0, 7.0);
        feat.process(wb);

        assertTrue(wb.hasFeature(Feature.OPPONENT_VELOCITY_DELTA));
        assertEquals(2.0, wb.getFeature(Feature.OPPONENT_VELOCITY_DELTA), 0.001);
    }

    @Test
    void velocityDeltaWithGap() {
        // First scan at tick 0
        scanWithLateralVel(0, 3.0, 5.0);
        feat.process(wb);
        wb.advanceTick();
        wb.advanceTick();

        // Second scan at tick 2 (gap of 2)
        scanWithLateralVel(2, 3.0, 9.0);
        feat.process(wb);

        assertTrue(wb.hasFeature(Feature.OPPONENT_VELOCITY_DELTA));
        assertEquals(2.0, wb.getFeature(Feature.OPPONENT_VELOCITY_DELTA), 0.001); // (9-5)/2
    }

    @Test
    void isDeceleratingTrue() {
        scanWithLateralVel(0, 3.0, 5.0);
        feat.process(wb);
        wb.advanceTick();

        scanWithLateralVel(1, 3.0, 3.0); // speed decreased: |3| < |5|
        feat.process(wb);

        assertEquals(1.0, wb.getFeature(Feature.OPPONENT_IS_DECELERATING), 0.001);
    }

    @Test
    void isDeceleratingFalse() {
        scanWithLateralVel(0, 3.0, 3.0);
        feat.process(wb);
        wb.advanceTick();

        scanWithLateralVel(1, 3.0, 6.0);
        feat.process(wb);

        assertEquals(0.0, wb.getFeature(Feature.OPPONENT_IS_DECELERATING), 0.001);
    }

    @Test
    void directionChangeResetsCounter() {
        // CW lateral
        scanWithLateralVel(0, 3.0, 5.0);
        feat.process(wb);
        wb.advanceTick();

        // Still CW — counter should increase
        scanWithLateralVel(1, 2.0, 4.0);
        feat.process(wb);
        double before = wb.getFeature(Feature.OPPONENT_TIME_SINCE_DIRECTION_CHANGE);
        wb.advanceTick();

        // Direction reversal to CCW — counter resets
        scanWithLateralVel(2, -2.0, -4.0);
        feat.process(wb);
        double after = wb.getFeature(Feature.OPPONENT_TIME_SINCE_DIRECTION_CHANGE);
        assertTrue(after < before, "Counter should reset on direction change");
    }

    @Test
    void skipWhenNoScan() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(5);
        feat.process(wb);
        assertFalse(wb.hasFeature(Feature.OPPONENT_LATERAL_DIRECTION));
    }
}

package cz.zamboch.autopilot.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the depth-3 tick ring used to attribute aiming decisions to the
 * correct historical tick (AIM = one tick before fire = two ticks before an
 * energy-drop detection), including the last-known walk across radar-lock gaps.
 */
final class WhiteboardTickRingTest {

    private Whiteboard wb;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
    }

    /** Advance to a new tick and store a distinct OUR_X marker for it. */
    private void tick(long t, double ourX) {
        wb.setFeature(Feature.TICK, t);
        wb.setFeature(Feature.OUR_X, ourX);
    }

    @Test
    void ringDepthIsThree() {
        assertEquals(3, Whiteboard.TICK_RING_DEPTH);
    }

    @Test
    void getFeatureNTicksAgoReturnsHistoricalValues() {
        tick(1, 10);
        tick(2, 20);
        tick(3, 30);

        assertEquals(30, wb.getFeatureNTicksAgo(Feature.OUR_X, 0), 1e-9);
        assertEquals(20, wb.getFeatureNTicksAgo(Feature.OUR_X, 1), 1e-9);
        assertEquals(10, wb.getFeatureNTicksAgo(Feature.OUR_X, 2), 1e-9);
    }

    @Test
    void getPreviousTickFeatureDelegatesToOneBack() {
        tick(1, 10);
        tick(2, 20);

        assertEquals(10, wb.getPreviousTickFeature(Feature.OUR_X), 1e-9);
        assertEquals(wb.getFeatureNTicksAgo(Feature.OUR_X, 1),
                wb.getPreviousTickFeature(Feature.OUR_X), 1e-9);
    }

    @Test
    void ringWrapsAroundAfterDepthTicks() {
        tick(1, 10);
        tick(2, 20);
        tick(3, 30);
        tick(4, 40); // wraps: slot that held tick 1 now holds tick 4

        assertEquals(40, wb.getFeatureNTicksAgo(Feature.OUR_X, 0), 1e-9);
        assertEquals(30, wb.getFeatureNTicksAgo(Feature.OUR_X, 1), 1e-9);
        assertEquals(20, wb.getFeatureNTicksAgo(Feature.OUR_X, 2), 1e-9);
    }

    @Test
    void nOutOfRangeThrows() {
        tick(1, 10);
        assertThrows(IllegalArgumentException.class,
                () -> wb.getFeatureNTicksAgo(Feature.OUR_X, 3));
        assertThrows(IllegalArgumentException.class,
                () -> wb.getFeatureNTicksAgo(Feature.OUR_X, -1));
    }

    @Test
    void lastKnownWalksBackAcrossNaNGaps() {
        // Opponent scanned at tick 2, then a one-tick radar-lock gap, then current.
        wb.setFeature(Feature.TICK, 2);
        wb.setFeature(Feature.OPPONENT_X, 100);
        wb.setFeature(Feature.TICK, 3); // no scan: OPPONENT_X stays NaN this tick
        wb.setFeature(Feature.TICK, 4);
        wb.setFeature(Feature.OPPONENT_X, 140);

        // From one tick ago (tick 3, NaN) walk back to the last known value (tick 2).
        assertEquals(100, wb.getLastKnownFeatureNTicksAgo(Feature.OPPONENT_X, 1), 1e-9);
        // From the current tick the value is the fresh one.
        assertEquals(140, wb.getLastKnownFeatureNTicksAgo(Feature.OPPONENT_X, 0), 1e-9);
    }

    @Test
    void lastKnownReturnsNaNWhenNoValueInRange() {
        wb.setFeature(Feature.TICK, 1);
        wb.setFeature(Feature.TICK, 2);
        assertTrue(Double.isNaN(wb.getLastKnownFeatureNTicksAgo(Feature.OPPONENT_X, 0)));
    }

    @Test
    void nonTickFeatureThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> wb.getFeatureNTicksAgo(Feature.OUR_AIM_X, 1));
    }
}

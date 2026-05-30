package cz.zamboch.autopilot.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the depth-3 tick ring used to attribute aiming decisions to the
 * correct historical tick (AIM = one tick before fire = two ticks before an
 * energy-drop detection).
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
    void nonTickFeatureThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> wb.getFeatureNTicksAgo(Feature.OUR_AIM_X, 1));
    }
}

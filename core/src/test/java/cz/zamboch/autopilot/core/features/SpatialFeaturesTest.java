package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SpatialFeatures with hand-crafted Whiteboard state.
 */
class SpatialFeaturesTest {

    private Whiteboard wb;
    private SpatialFeatures processor;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        processor = new SpatialFeatures();
        wb.onRoundStart(0, 800, 600, 0.1, 10);
    }

    @Test
    void computesDistanceCorrectly() {
        // Us at (100, 100), opponent at (400, 500)
        wb.setOurState(100, 100, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan(400, 500, 0, 0, 100);

        processor.process(wb);

        assertTrue(wb.hasFeature(Feature.DISTANCE));
        double expected = Math.hypot(300, 400); // 500.0
        assertEquals(expected, wb.getFeature(Feature.DISTANCE), 0.001);
    }

    @Test
    void computesBearingCorrectly() {
        // Us at (400, 300), opponent directly north at (400, 500)
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan(400, 500, 0, 0, 100);

        processor.process(wb);

        assertTrue(wb.hasFeature(Feature.BEARING_TO_OPPONENT_ABS));
        // atan2(0, 200) = 0 (due north in robocode)
        assertEquals(0.0, wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS), 0.001);
    }

    @Test
    void computesBearingEast() {
        // Us at (400, 300), opponent east at (600, 300)
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan(600, 300, 0, 0, 100);

        processor.process(wb);

        // atan2(200, 0) = PI/2 (east)
        assertEquals(Math.PI / 2, wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS), 0.001);
    }

    @Test
    void computesWallDistanceCorrectly() {
        // Opponent at (50, 100) on 800x600 field
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan(50, 100, 0, 0, 100);

        processor.process(wb);

        assertTrue(wb.hasFeature(Feature.OPPONENT_DIST_TO_WALL_MIN));
        // min(50, 750, 100, 500) = 50
        assertEquals(50.0, wb.getFeature(Feature.OPPONENT_DIST_TO_WALL_MIN), 0.001);
    }

    @Test
    void computesWallDistanceCenter() {
        // Opponent at center (400, 300) on 800x600 field
        wb.setOurState(100, 100, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan(400, 300, 0, 0, 100);

        processor.process(wb);

        // min(400, 400, 300, 300) = 300
        assertEquals(300.0, wb.getFeature(Feature.OPPONENT_DIST_TO_WALL_MIN), 0.001);
    }

    @Test
    void noFeaturesWithoutScan() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(5);
        // No scan this tick

        processor.process(wb);

        assertFalse(wb.hasFeature(Feature.DISTANCE));
        assertFalse(wb.hasFeature(Feature.BEARING_TO_OPPONENT_ABS));
        assertFalse(wb.hasFeature(Feature.OPPONENT_DIST_TO_WALL_MIN));
    }
}

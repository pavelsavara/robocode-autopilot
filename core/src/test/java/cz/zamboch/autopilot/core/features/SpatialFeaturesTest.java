package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class SpatialFeaturesTest {
    private Whiteboard wb;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        wb.registerFeatures(new SpatialFeatures());
    }

    @Test
    void computesGeometryAndMovementFromCurrentScan() {
        wb.setFeature(Feature.TICK, 7);
        wb.setFeature(Feature.OUR_X, 100);
        wb.setFeature(Feature.OUR_Y, 200);
        wb.setFeature(Feature.OUR_HEADING, 0);
        wb.beginScanRow(7);
        wb.setFeature(Feature.DISTANCE, 150);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.setFeature(Feature.OPPONENT_HEADING, Math.PI / 2);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 8);

        wb.process();

        assertEquals(0, wb.getFeature(Feature.OPPONENT_BEARING_ABSOLUTE), 1e-9);
        assertEquals(100, wb.getFeature(Feature.OPPONENT_X), 1e-9);
        assertEquals(350, wb.getFeature(Feature.OPPONENT_Y), 1e-9);
        assertEquals(-8, wb.getFeature(Feature.OPPONENT_LATERAL_VELOCITY), 1e-9);
        assertEquals(0, wb.getFeature(Feature.OPPONENT_ADVANCING_VELOCITY), 1e-9);
    }

    @Test
    void missingMovementInputsStillLeavesGeometryAvailable() {
        wb.setFeature(Feature.TICK, 8);
        wb.setFeature(Feature.OUR_X, 100);
        wb.setFeature(Feature.OUR_Y, 200);
        wb.setFeature(Feature.OUR_HEADING, Math.PI / 2);
        wb.beginScanRow(8);
        wb.setFeature(Feature.DISTANCE, 50);
        wb.setFeature(Feature.BEARING_RADIANS, 0);

        wb.process();

        assertEquals(Math.PI / 2, wb.getFeature(Feature.OPPONENT_BEARING_ABSOLUTE), 1e-9);
        assertEquals(150, wb.getFeature(Feature.OPPONENT_X), 1e-9);
        assertEquals(200, wb.getFeature(Feature.OPPONENT_Y), 1e-9);
        assertTrue(Double.isNaN(wb.getFeature(Feature.OPPONENT_LATERAL_VELOCITY)));
        assertTrue(Double.isNaN(wb.getFeature(Feature.OPPONENT_ADVANCING_VELOCITY)));
    }
}
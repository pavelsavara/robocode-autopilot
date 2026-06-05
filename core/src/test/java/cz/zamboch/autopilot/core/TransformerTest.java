package cz.zamboch.autopilot.core;

import cz.zamboch.autopilot.core.features.ScanFeatures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class TransformerTest {

    @Test
    void processWritesComputedFeatures() {
        Whiteboard wb = new Whiteboard();
        wb.registerFeatures(new ScanFeatures());

        // Set input features
        wb.setFeature(Feature.TICK, 42);
        wb.setFeature(Feature.OUR_X, 100);
        wb.setFeature(Feature.OUR_Y, 200);
        wb.setFeature(Feature.OUR_HEADING, Math.toRadians(45));
        wb.setFeature(Feature.OUR_VELOCITY, 5.0);
        wb.setFeature(Feature.OUR_ENERGY, 85.0);
        wb.setFeature(Feature.GUN_HEAT, 1.5);
        wb.beginScanRow(40);
        wb.setFeature(Feature.TICK, 42);
        wb.beginScanRow(42);
        wb.setFeature(Feature.BEARING_RADIANS, Math.toRadians(30));
        wb.setFeature(Feature.OPPONENT_HEADING, Math.toRadians(180));
        wb.setFeature(Feature.OPPONENT_VELOCITY, 4.0);

        wb.process();

        // OPPONENT_BEARING_ABSOLUTE = OUR_HEADING + BEARING_RADIANS
        double expectedAbsBearing = Math.toRadians(45) + Math.toRadians(30);
        assertEquals(expectedAbsBearing, wb.getFeature(Feature.OPPONENT_BEARING_ABSOLUTE), 1e-9);

        // TICKS_SINCE_SCAN = current scan tick - previous scan tick
        assertEquals(2.0, wb.getFeature(Feature.TICKS_SINCE_SCAN), 1e-9);

        // Lateral/advancing should be computed (not NaN)
        assertFalse(Double.isNaN(wb.getFeature(Feature.OPPONENT_LATERAL_VELOCITY)));
        assertFalse(Double.isNaN(wb.getFeature(Feature.OPPONENT_ADVANCING_VELOCITY)));
    }

    @Test
    void missingInputLeavesComputedAsNaN() {
        Whiteboard wb = new Whiteboard();
        wb.registerFeatures(new ScanFeatures());

        // Only set TICK, no scan data
        wb.setFeature(Feature.TICK, 10);
        wb.setFeature(Feature.OUR_HEADING, 0);

        wb.process();

        // No scan row means scan-derived values are absent.
        assertTrue(Double.isNaN(wb.getFeature(Feature.OPPONENT_BEARING_ABSOLUTE)));
        assertTrue(Double.isNaN(wb.getFeature(Feature.TICKS_SINCE_SCAN)));
    }
}

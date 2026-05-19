package cz.zamboch.autopilot.core;

import cz.zamboch.autopilot.core.features.MovementFeatures;
import cz.zamboch.autopilot.core.features.SpatialFeatures;
import cz.zamboch.autopilot.core.features.TimingFeatures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class TransformerTest {

    @Test
    void processWritesComputedFeatures() {
        Whiteboard wb = new Whiteboard();
        wb.registerFeatures(
                new SpatialFeatures(),
                new MovementFeatures(),
                new TimingFeatures());

        // Set input features
        wb.setFeature(Feature.TICK, 42);
        wb.setFeature(Feature.OUR_X, 100);
        wb.setFeature(Feature.OUR_Y, 200);
        wb.setFeature(Feature.OUR_HEADING, Math.toRadians(45));
        wb.setFeature(Feature.OUR_VELOCITY, 5.0);
        wb.setFeature(Feature.OUR_ENERGY, 85.0);
        wb.setFeature(Feature.GUN_HEAT, 1.5);
        wb.setFeature(Feature.BEARING_RADIANS, Math.toRadians(30));
        wb.setFeature(Feature.OPPONENT_HEADING, Math.toRadians(180));
        wb.setFeature(Feature.OPPONENT_VELOCITY, 4.0);
        wb.setFeature(Feature.LAST_SCAN_TICK, 40);

        wb.process();

        // OPPONENT_BEARING_ABSOLUTE = OUR_HEADING + BEARING_RADIANS
        double expectedAbsBearing = Math.toRadians(45) + Math.toRadians(30);
        assertEquals(expectedAbsBearing, wb.getFeature(Feature.OPPONENT_BEARING_ABSOLUTE), 1e-9);

        // TICKS_SINCE_SCAN = TICK - LAST_SCAN_TICK
        assertEquals(2.0, wb.getFeature(Feature.TICKS_SINCE_SCAN), 1e-9);

        // Lateral/advancing should be computed (not NaN)
        assertFalse(Double.isNaN(wb.getFeature(Feature.OPPONENT_LATERAL_VELOCITY)));
        assertFalse(Double.isNaN(wb.getFeature(Feature.OPPONENT_ADVANCING_VELOCITY)));
    }

    @Test
    void missingInputLeavesComputedAsNaN() {
        Whiteboard wb = new Whiteboard();
        wb.registerFeatures(
                new SpatialFeatures(),
                new MovementFeatures(),
                new TimingFeatures());

        // Only set TICK, no scan data
        wb.setFeature(Feature.TICK, 10);
        wb.setFeature(Feature.OUR_HEADING, 0);

        wb.process();

        // No bearing → OPPONENT_BEARING_ABSOLUTE should remain NaN
        assertTrue(Double.isNaN(wb.getFeature(Feature.OPPONENT_BEARING_ABSOLUTE)));
        // No LAST_SCAN_TICK → TICKS_SINCE_SCAN should remain NaN
        assertTrue(Double.isNaN(wb.getFeature(Feature.TICKS_SINCE_SCAN)));
    }
}

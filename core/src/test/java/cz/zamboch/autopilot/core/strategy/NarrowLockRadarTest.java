package cz.zamboch.autopilot.core.strategy;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.SpatialFeatures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class NarrowLockRadarTest {

    private Whiteboard wb;
    private NarrowLockRadar radar;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        wb.registerFeatures(new SpatialFeatures());
        radar = new NarrowLockRadar(wb);
    }

    @Test
    void spinsClockwiseInitiallyWhenNoScan() {
        double turn = radar.getRadarTurn();
        assertTrue(Double.isInfinite(turn));
        assertTrue(turn > 0, "Initial spin should be clockwise (positive)");
    }

    @Test
    void crossesKnownCenterWithNarrowOvershoot() {
        wb.setFeature(Feature.OUR_X, 100);
        wb.setFeature(Feature.OUR_Y, 100);
        wb.setFeature(Feature.DISTANCE, 400);
        wb.setFeature(Feature.OUR_HEADING, Math.PI / 4);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.process();

        wb.setFeature(Feature.RADAR_HEADING, Math.PI / 6);
        double turn = radar.getRadarTurn();

        double expectedBase = Math.PI / 4 - Math.PI / 6;
        double overshoot = Math.toRadians(1.0);
        assertEquals(expectedBase + overshoot, turn, 1e-9);
    }

    @Test
    void crossesBackTowardLastKnownCenterAfterLockLost() {
        wb.setFeature(Feature.OUR_X, 100);
        wb.setFeature(Feature.OUR_Y, 100);
        wb.setFeature(Feature.DISTANCE, 400);
        wb.setFeature(Feature.OUR_HEADING, 0);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.process();

        wb.setFeature(Feature.RADAR_HEADING, Math.PI / 4);
        double turn1 = radar.getRadarTurn();
        assertTrue(turn1 < 0, "Turn should be negative (counter-clockwise)");

        wb.setFeature(Feature.TICK, 1);
    wb.setFeature(Feature.OUR_X, 100);
    wb.setFeature(Feature.OUR_Y, 100);
        wb.setFeature(Feature.RADAR_HEADING, Math.toRadians(-2));
        wb.process();

        double turn2 = radar.getRadarTurn();
        assertTrue(turn2 > 0, "Should cross back through the last known center");
        assertFalse(Double.isInfinite(turn2));
    }

    @Test
    void alternatesWhenAlreadyOnCenter() {
        wb.setFeature(Feature.OUR_X, 100);
        wb.setFeature(Feature.OUR_Y, 100);
        wb.setFeature(Feature.DISTANCE, 400);
        wb.setFeature(Feature.OUR_HEADING, 0);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.process();

        wb.setFeature(Feature.RADAR_HEADING, 0);
        double turn1 = radar.getRadarTurn();
        assertTrue(turn1 < 0);

        wb.setFeature(Feature.RADAR_HEADING, 0);
        double turn2 = radar.getRadarTurn();
        assertTrue(turn2 > 0);
    }
}
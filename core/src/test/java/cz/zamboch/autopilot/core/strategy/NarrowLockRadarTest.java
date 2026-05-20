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
        // No opponent bearing → should spin positive (clockwise)
        double turn = radar.getRadarTurn();
        assertTrue(Double.isInfinite(turn));
        assertTrue(turn > 0, "Initial spin should be clockwise (positive)");
    }

    @Test
    void returnsNarrowLockTurnWhenBearingKnown() {
        // Opponent at absolute bearing π/4, radar at π/6
        wb.setFeature(Feature.OUR_HEADING, Math.PI / 4);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.process(); // computes OPPONENT_BEARING_ABSOLUTE = π/4

        wb.setFeature(Feature.RADAR_HEADING, Math.PI / 6);
        double turn = radar.getRadarTurn();

        // Expected: normalRelativeAngle(π/4 - π/6) = π/12 ≈ 0.2618
        // Plus overshoot of 2° in same direction
        double expectedBase = Math.PI / 4 - Math.PI / 6;
        double overshoot = Math.toRadians(2);
        assertEquals(expectedBase + overshoot, turn, 1e-9);
    }

    @Test
    void remembersDirectionAfterLockLost() {
        // First: establish lock with negative turn direction
        // Opponent at bearing 0, radar at π/4 → turn is negative
        wb.setFeature(Feature.OUR_HEADING, 0);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.process(); // OPPONENT_BEARING_ABSOLUTE = 0

        wb.setFeature(Feature.RADAR_HEADING, Math.PI / 4);
        double turn1 = radar.getRadarTurn();
        assertTrue(turn1 < 0, "Turn should be negative (counter-clockwise)");

        // Now lose the lock (clear bearing)
        wb.clearFeatures();
        wb.setFeature(Feature.OUR_HEADING, 0);
        wb.process(); // OPPONENT_BEARING_ABSOLUTE stays NaN

        double turn2 = radar.getRadarTurn();
        assertTrue(Double.isInfinite(turn2));
        assertTrue(turn2 < 0, "Should spin counter-clockwise (last known direction)");
    }

    @Test
    void switchesDirectionWhenOpponentCrossesRadar() {
        // Opponent at bearing 0, radar slightly past (at -0.01)
        wb.setFeature(Feature.OUR_HEADING, 0);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.process();

        wb.setFeature(Feature.RADAR_HEADING, -0.01 + 2 * Math.PI); // just past
        double turn1 = radar.getRadarTurn();
        // normalRelativeAngle(0 - (2π - 0.01)) = normalRelativeAngle(0.01 - 2π) = 0.01
        assertTrue(turn1 > 0);

        // Next tick: radar overshot the other way
        wb.setFeature(Feature.RADAR_HEADING, 0.01);
        double turn2 = radar.getRadarTurn();
        // normalRelativeAngle(0 - 0.01) = -0.01
        assertTrue(turn2 < 0);
    }
}

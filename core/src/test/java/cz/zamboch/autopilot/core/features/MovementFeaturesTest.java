package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.util.RoboMath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MovementFeatures with hand-crafted Whiteboard state.
 */
class MovementFeaturesTest {

    private Whiteboard wb;
    private SpatialFeatures spatial;
    private MovementFeatures processor;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        spatial = new SpatialFeatures();
        processor = new MovementFeatures();
        wb.onRoundStart(0, 800, 600, 0.1, 10);
    }

    @Test
    void computesVelocityFromScan() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan(600, 300, 0, 5.5, 100);

        spatial.process(wb);
        processor.process(wb);

        assertTrue(wb.hasFeature(Feature.OPPONENT_VELOCITY));
        assertEquals(5.5, wb.getFeature(Feature.OPPONENT_VELOCITY), 0.001);
    }

    @Test
    void computesLateralVelocityPerpendicular() {
        // Us at (400, 300), opponent east at (600, 300)
        // Opponent heading north (0 rad), moving at velocity 8
        // Bearing to opponent = PI/2 (east)
        // Relative heading = 0 - PI/2 = -PI/2
        // Lateral = 8 * sin(-PI/2) = -8
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan(600, 300, 0, 8, 100);

        spatial.process(wb);
        processor.process(wb);

        assertTrue(wb.hasFeature(Feature.OPPONENT_LATERAL_VELOCITY));
        assertEquals(-8.0, wb.getFeature(Feature.OPPONENT_LATERAL_VELOCITY), 0.001);
    }

    @Test
    void computesAdvancingVelocityTowardsUs() {
        // Us at (400, 300), opponent north at (400, 500)
        // Bearing to opponent = 0 (north)
        // Opponent heading south (PI), velocity 6 => moving south toward us
        // relative heading = PI - 0 = PI
        // advancing = -6 * cos(PI) = 6 (positive = approaching)
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan(400, 500, Math.PI, 6, 100);

        spatial.process(wb);
        processor.process(wb);

        assertTrue(wb.hasFeature(Feature.OPPONENT_ADVANCING_VELOCITY));
        assertEquals(6.0, wb.getFeature(Feature.OPPONENT_ADVANCING_VELOCITY), 0.001);
    }

    @Test
    void computesHeadingDelta() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);

        // First scan — sets prevOpponentHeading
        wb.setTick(0);
        wb.setOpponentScan(600, 300, 1.0, 5, 100);
        spatial.process(wb);
        processor.process(wb);

        // prevOpponentHeading should be NaN for the first scan, so no heading delta
        assertFalse(wb.hasFeature(Feature.OPPONENT_HEADING_DELTA));

        // Advance tick, clear features
        wb.advanceTick();

        // Second scan — now heading delta should be computed
        wb.setOpponentScan(600, 300, 1.2, 5, 100);
        spatial.process(wb);
        processor.process(wb);

        assertTrue(wb.hasFeature(Feature.OPPONENT_HEADING_DELTA));
        assertEquals(0.2, wb.getFeature(Feature.OPPONENT_HEADING_DELTA), 0.001);
    }

    @Test
    void headingDeltaWrapsAround() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);

        // First scan: heading just below 2*PI
        wb.setTick(0);
        wb.setOpponentScan(600, 300, 6.0, 5, 100);
        spatial.process(wb);
        processor.process(wb);

        wb.advanceTick();

        // Second scan: heading just above 0 — small positive turn
        wb.setOpponentScan(600, 300, 0.3, 5, 100);
        spatial.process(wb);
        processor.process(wb);

        assertTrue(wb.hasFeature(Feature.OPPONENT_HEADING_DELTA));
        // 0.3 - 6.0 normalized to relative angle
        double expected = RoboMath.normalRelativeAngle(0.3 - 6.0);
        assertEquals(expected, wb.getFeature(Feature.OPPONENT_HEADING_DELTA), 0.001);
    }

    @Test
    void noFeaturesWithoutScan() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(5);

        processor.process(wb);

        assertFalse(wb.hasFeature(Feature.OPPONENT_VELOCITY));
        assertFalse(wb.hasFeature(Feature.OPPONENT_LATERAL_VELOCITY));
    }
}

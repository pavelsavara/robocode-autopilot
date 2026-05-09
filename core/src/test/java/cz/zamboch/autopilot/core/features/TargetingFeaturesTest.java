package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.util.RoboMath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TargetingFeatures — circular targeting, linear targeting,
 * and GuessFactor computation.
 */
class TargetingFeaturesTest {

    private static final int BF_W = 800;
    private static final int BF_H = 600;
    private static final double HALF = 18.0;
    private static final double TOLERANCE = 0.05; // ~2.9 degrees

    private Whiteboard wb;
    private TargetingFeatures targeting;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        targeting = new TargetingFeatures();
        wb.onRoundStart(0, BF_W, BF_H, 0.1, 10);
    }

    // === circularTargetAngle — straight line (headingDelta = 0) ===

    @Test
    void straightLine_headingNorth_predictionsPointAhead() {
        // Opponent at (400,300) heading north (0 rad), vel=8, no turn
        // Us at (400,100), bullet speed 14 (power 2.0)
        double angle = TargetingFeatures.circularTargetAngle(
                400, 100,   // our position
                400, 300,   // opponent position
                0, 8, 0,    // heading north, vel 8, no turn
                14,          // bullet speed
                BF_W, BF_H);

        // Opponent moves north; we are south. Angle should be ~0 (north),
        // slightly ahead of the opponent's current position
        assertEquals(0.0, angle, TOLERANCE,
                "Straight-line north opponent should aim ~north");
    }

    @Test
    void straightLine_headingEast_predictionsPointEast() {
        // Opponent at (200,300) heading east (PI/2 rad), vel=6
        // Us at (200,100)
        double angle = TargetingFeatures.circularTargetAngle(
                200, 100,
                200, 300,
                Math.PI / 2, 6, 0,
                14,
                BF_W, BF_H);

        // Opponent moves east (increasing X). Predicted position is NE of us
        // Angle should be between 0 (north) and PI/2 (east)
        assertTrue(angle > 0 && angle < Math.PI,
                "Straight-line east opponent: angle should be in [0, PI]");
    }

    @Test
    void stationaryOpponent_aimsDirect() {
        // Opponent not moving at all
        double angle = TargetingFeatures.circularTargetAngle(
                400, 100,
                400, 300,
                0, 0, 0,
                14,
                BF_W, BF_H);

        // Should aim directly at opponent: atan2(0, 200) = 0
        assertEquals(0.0, angle, 0.01,
                "Stationary opponent should aim directly at them");
    }

    // === circularTargetAngle — circular arc ===

    @Test
    void turningOpponent_predictsArc() {
        // Opponent at center, heading north, vel=6, turning right 0.05 rad/tick
        // Max turn at vel 6 = toRadians(10 - 4.5) = ~0.096 rad/tick, so 0.05 is within limit
        double angle = TargetingFeatures.circularTargetAngle(
                400, 100,
                400, 300,
                0, 6, 0.05,
                14,
                BF_W, BF_H);

        // Opponent curves to the right (clockwise). Predicted position is east of current.
        // Angle should be > 0 (east-ward offset from dead north)
        assertTrue(angle > 0 && angle < Math.PI,
                "Turning-right opponent should produce an eastward-offset angle");
    }

    @Test
    void turningLeft_predictsArcLeft() {
        // Same setup but turning left (-0.05 rad/tick)
        double angle = TargetingFeatures.circularTargetAngle(
                400, 100,
                400, 300,
                0, 6, -0.05,
                14,
                BF_W, BF_H);

        // Opponent curves to the left (CCW). Predicted position is west of current.
        // Angle should be in (PI, 2*PI) or equivalently negative offset
        double relAngle = RoboMath.normalRelativeAngle(angle);
        assertTrue(relAngle < 0,
                "Turning-left opponent should produce a westward-offset angle");
    }

    // === circularTargetAngle — wall collision ===

    @Test
    void opponentHeadingIntoWall_stopsAtWall() {
        // Opponent near north wall heading north, fast velocity
        // Should stop at wall, not pass through
        double angle = TargetingFeatures.circularTargetAngle(
                400, 100,
                400, 560,   // near top wall (BF_H=600, wall at 582)
                0, 8, 0,    // heading straight north (toward wall)
                14,
                BF_W, BF_H);

        // After wall hit, opponent stops. Predicted position is at the wall.
        // Angle should aim toward wall area (north of us)
        assertTrue(!Double.isNaN(angle), "Should not produce NaN");
        // Verify it aims toward the wall area, not beyond
        double expectedBearing = Math.atan2(400 - 400, (BF_H - HALF) - 100);
        assertEquals(expectedBearing, angle, TOLERANCE,
                "Should aim at wall-clamped position");
    }

    @Test
    void opponentHeadingIntoLeftWall_stops() {
        // Opponent near left wall heading west (3*PI/2 = 4.712)
        double angle = TargetingFeatures.circularTargetAngle(
                400, 300,
                50, 300,     // near left wall
                3 * Math.PI / 2, 8, 0,  // heading west
                14,
                BF_W, BF_H);

        assertTrue(!Double.isNaN(angle), "Should not produce NaN");
    }

    // === circularTargetAngle — turn rate capping ===

    @Test
    void excessiveTurnRate_isCapped() {
        // At velocity 8, max turn = toRadians(10 - 6) = ~0.07 rad/tick
        // Give headingDelta = 0.2 (way too high) — should be capped
        double cappedAngle = TargetingFeatures.circularTargetAngle(
                400, 100,
                400, 300,
                0, 8, 0.2,   // excessively large turn rate
                14,
                BF_W, BF_H);

        // Compare with physically-limited turn rate
        double maxTurn = Math.toRadians(10.0 - 0.75 * 8.0); // ~0.07 rad
        double limitedAngle = TargetingFeatures.circularTargetAngle(
                400, 100,
                400, 300,
                0, 8, maxTurn,
                14,
                BF_W, BF_H);

        assertEquals(limitedAngle, cappedAngle, 0.001,
                "Excessive turn rate should be capped to physics limit");
    }

    @Test
    void turnRateWithinLimit_notCapped() {
        // At velocity 4, max turn = toRadians(10 - 3) = ~0.122 rad/tick
        // Give headingDelta = 0.05 (well within limit)
        double angle1 = TargetingFeatures.circularTargetAngle(
                400, 100,
                400, 300,
                0, 4, 0.05,
                14,
                BF_W, BF_H);

        // Should differ from a smaller turn rate
        double angle2 = TargetingFeatures.circularTargetAngle(
                400, 100,
                400, 300,
                0, 4, 0.02,
                14,
                BF_W, BF_H);

        assertNotEquals(angle1, angle2, 0.001,
                "Different within-limit turn rates should produce different angles");
    }

    // === Turn-then-move ordering ===

    @Test
    void turnThenMove_ordering_correctForTurningOpponent() {
        // With correct turn-then-move ordering, a turning opponent's
        // first predicted step should apply the heading change BEFORE moving.
        // This tests that the prediction diverges correctly from head-on for a turner.

        // Opponent heading north at vel=6, turning right at 0.08 rad/tick
        double circAngle = TargetingFeatures.circularTargetAngle(
                400, 100,
                400, 300,
                0, 6, 0.08,
                14,
                BF_W, BF_H);

        // Head-on angle (opponent at (400,300) from (400,100))
        double headOnAngle = Math.atan2(400 - 400, 300 - 100); // 0.0

        // Circular should lead the target to the right (positive angle offset)
        double offset = RoboMath.normalRelativeAngle(circAngle - headOnAngle);
        assertTrue(offset > 0.01,
                "Turning-right opponent should produce positive lead angle, got: " + offset);
    }

    // === Full process() integration ===

    @Test
    void process_computesAllTargetingFeatures() {
        // Set up whiteboard with a scan
        wb.setOurState(400, 100, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan("TestBot", 400, 300, 0.5, 6, 100);

        // Provide required upstream features
        new SpatialFeatures().process(wb);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 6.0);
        wb.setFeature(Feature.OPPONENT_LATERAL_DIRECTION, 1);
        wb.setFeature(Feature.OUR_LATERAL_VELOCITY, 4.0);

        targeting.process(wb);

        assertTrue(wb.hasFeature(Feature.LINEAR_TARGET_ANGLE), "Should compute linear angle");
        assertTrue(wb.hasFeature(Feature.CIRCULAR_TARGET_ANGLE), "Should compute circular angle");
        assertTrue(wb.hasFeature(Feature.GF_CURRENT_AT_POWER_2), "Should compute GF");
        assertTrue(wb.hasFeature(Feature.OPPONENT_GUESS_FACTOR), "Should compute opponent GF");
    }

    @Test
    void process_skipsWithoutScan() {
        wb.setOurState(400, 100, 0, 0, 0, 0, 100, 0);
        wb.setTick(5);
        // No scan this tick

        targeting.process(wb);

        assertFalse(wb.hasFeature(Feature.CIRCULAR_TARGET_ANGLE),
                "Should not compute circular angle without scan");
    }

    @Test
    void process_usesLastFirePower() {
        wb.setOurState(400, 100, 0, 0, 0, 0, 100, 0);
        wb.setTick(1);
        // Simulate having fired at power 1.0 (bullet speed = 17)
        wb.setLastOurFire(0, 1.0);
        wb.setOpponentScan("TestBot", 400, 300, 0, 6, 100);

        new SpatialFeatures().process(wb);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 6.0);
        targeting.process(wb);

        assertTrue(wb.hasFeature(Feature.CIRCULAR_TARGET_ANGLE),
                "Should compute with last fire power");
    }

    // === gfAt ===

    @Test
    void gfAt_zeroBearingOffset_returnsZero() {
        assertEquals(0.0, TargetingFeatures.gfAt(0, 1, 2.0), 0.001);
    }

    @Test
    void gfAt_maxPositiveOffset_returnsOne() {
        double mea = Math.asin(8.0 / 14.0); // MEA at power 2.0
        assertEquals(1.0, TargetingFeatures.gfAt(mea, 1, 2.0), 0.001);
    }

    @Test
    void gfAt_clampsBeyondOne() {
        // Offset larger than MEA should clamp to 1.0
        assertEquals(1.0, TargetingFeatures.gfAt(1.0, 1, 2.0), 0.001);
    }

    @Test
    void gfAt_negativeDirectionFlips() {
        double mea = Math.asin(8.0 / 14.0);
        // Positive offset with negative lateral direction → negative GF
        double gf = TargetingFeatures.gfAt(mea, -1, 2.0);
        assertEquals(-1.0, gf, 0.001);
    }
}

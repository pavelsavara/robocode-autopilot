package cz.zamboch.autopilot.core.physics;

import cz.zamboch.autopilot.core.movement.CandidatePosition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests envelope algorithm properties: braking coverage, speed symmetry,
 * and physical reachability validation.
 */
final class EnvelopeAlgorithmTest {

    private static final int BFW = 800;
    private static final int BFH = 600;

    @Test
    void brakingCoveredForFullSpeed() {
        // A robot at v=8 heading north. After 10 ticks with full braking (-2/tick),
        // it stops in 4 ticks (8→6→4→2→0), then can reverse to -6 in 6 more ticks.
        // So positions BEHIND the start should be reachable.
        RobotState state = RobotState.at(400, 300, 0, 8);
        CandidatePosition[] all = ReachableEnvelope.getAllCandidates(state, BFW, BFH);

        boolean hasPositionBehind = false;
        for (CandidatePosition c : all) {
            if (c.y < 300) { // Behind (south of) start when heading north
                hasPositionBehind = true;
                break;
            }
        }
        assertTrue(hasPositionBehind,
                "Full-speed robot should have reachable positions behind start (braking + reverse)");
    }

    @Test
    void stoppedRobotHasSymmetricEnvelope() {
        // A stopped robot heading north should have roughly symmetric reach
        // in the lateral (east-west) direction
        RobotState state = RobotState.at(400, 300, 0, 0);
        CandidatePosition[] all = ReachableEnvelope.getAllCandidates(state, BFW, BFH);

        int leftCount = 0, rightCount = 0;
        for (CandidatePosition c : all) {
            if (c.x < 395) leftCount++;
            if (c.x > 405) rightCount++;
        }
        // Should be approximately equal (within 10%)
        double ratio = (double) leftCount / rightCount;
        assertTrue(ratio > 0.9 && ratio < 1.1,
                "Stopped robot should have symmetric lateral reach: left=" + leftCount
                        + " right=" + rightCount + " ratio=" + ratio);
    }

    @Test
    void negativeVelocityMirrorPositive() {
        // v=-5 should use same table as v=+5 (Math.abs in lookup)
        RobotState pos = RobotState.at(400, 300, 0, 5);
        RobotState neg = RobotState.at(400, 300, 0, -5);

        int[] posCounts = ReachableEnvelope.countReachable(pos, BFW, BFH);
        int[] negCounts = ReachableEnvelope.countReachable(neg, BFW, BFH);

        assertEquals(posCounts[1], negCounts[1],
                "v=5 and v=-5 should use same table size");
    }

    @Test
    void maxReachDistanceIsPhysicallyPlausible() {
        // Max distance in 10 ticks at v=8: robot can maintain v=8 for 10 ticks
        // = 80px forward. The envelope should NOT contain positions >100px away
        // from start (accounting for turns adding lateral distance).
        RobotState state = RobotState.at(400, 300, 0, 8);
        CandidatePosition[] all = ReachableEnvelope.getAllCandidates(state, BFW, BFH);

        double maxDist = 0;
        for (CandidatePosition c : all) {
            double d = Math.hypot(c.x - 400, c.y - 300);
            if (d > maxDist) maxDist = d;
        }
        // Theoretical max: 10 ticks × 8 px/tick = 80px straight line.
        // With turns, max distance is less than 100px.
        assertTrue(maxDist < 120,
                "Max reach distance should be < 120px, got " + maxDist);
        assertTrue(maxDist > 50,
                "Max reach distance should be > 50px, got " + maxDist);
    }

    @Test
    void stoppedRobotReachesLessThanMovingRobot() {
        // v=0 should reach less far than v=8 in the forward direction
        RobotState stopped = RobotState.at(400, 300, 0, 0);
        RobotState moving = RobotState.at(400, 300, 0, 8);

        CandidatePosition[] allStopped = ReachableEnvelope.getAllCandidates(stopped, BFW, BFH);
        CandidatePosition[] allMoving = ReachableEnvelope.getAllCandidates(moving, BFW, BFH);

        double maxForwardStopped = 0, maxForwardMoving = 0;
        for (CandidatePosition c : allStopped) {
            double dy = c.y - 300;
            if (dy > maxForwardStopped) maxForwardStopped = dy;
        }
        for (CandidatePosition c : allMoving) {
            double dy = c.y - 300;
            if (dy > maxForwardMoving) maxForwardMoving = dy;
        }

        assertTrue(maxForwardMoving > maxForwardStopped,
                "Moving robot (" + maxForwardMoving + ") should reach further forward than stopped ("
                        + maxForwardStopped + ")");
    }

    @Test
    void envelopeFillRatioNearCenterIsHigh() {
        // At the center of an 800x600 field, most of the envelope should survive
        RobotState state = RobotState.at(400, 300, 0, 4);
        int[] counts = ReachableEnvelope.countReachable(state, BFW, BFH);
        double ratio = (double) counts[0] / counts[1];
        assertTrue(ratio > 0.85,
                "Fill ratio at center should be > 85%, got " + (ratio * 100) + "%");
    }

    @Test
    void envelopeFillRatioInCornerIsLow() {
        // In a tight corner, fill ratio should be significantly reduced
        RobotState state = RobotState.at(20, 20, 0, 4);
        int[] counts = ReachableEnvelope.countReachable(state, BFW, BFH);
        double ratio = (double) counts[0] / counts[1];
        assertTrue(ratio < 0.5,
                "Fill ratio in corner should be < 50%, got " + (ratio * 100) + "%");
    }

    @Test
    void precisePredictionVerifiesReachability() {
        // Pick a specific candidate from the envelope and verify it's reachable
        // by forward-simulating with PrecisePredictor
        RobotState start = RobotState.at(400, 300, 0, 0);
        CandidatePosition[] all = ReachableEnvelope.getAllCandidates(start, BFW, BFH);
        assertTrue(all.length > 0);

        // The closest candidate to straight ahead should be reachable
        // by simply accelerating forward for 10 ticks
        RobotState simulated = PrecisePredictor.simulate(start, 1.0, 0.0,
                ReachableEnvelope.HORIZON, BFW, BFH);

        // The simulated end position should be in the envelope
        // (within grid spacing tolerance)
        boolean found = false;
        for (CandidatePosition c : all) {
            if (Math.abs(c.x - simulated.x) <= ReachableEnvelope.GRID + 1
                    && Math.abs(c.y - simulated.y) <= ReachableEnvelope.GRID + 1) {
                found = true;
                break;
            }
        }
        assertTrue(found,
                "Straight-ahead simulation to (" + simulated.x + "," + simulated.y
                        + ") should be in the envelope");
    }
}

package cz.zamboch.autopilot.core.physics;

import cz.zamboch.autopilot.core.movement.CandidatePosition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ReachableEnvelope — table loading, rotation, wall clamping, jitter.
 */
final class ReachableEnvelopeTest {

    private static final int BFW = 800;
    private static final int BFH = 600;

    @Test
    void tablesLoadedForAllVelocities() {
        // Center of battlefield, heading north, each velocity
        for (int v = 0; v <= 8; v++) {
            RobotState state = RobotState.at(400, 300, 0, v);
            CandidatePosition[] all = ReachableEnvelope.getAllCandidates(state, BFW, BFH);
            // Should have many positions — research says 1389-2140
            assertTrue(all.length > 500,
                    "v=" + v + " should have >500 candidates in open field, got " + all.length);
        }
    }

    @Test
    void wallClampingReducesCandidates() {
        // Robot in a corner — many positions should be clipped
        RobotState corner = RobotState.at(25, 25, 0, 0);
        CandidatePosition[] inCorner = ReachableEnvelope.getAllCandidates(corner, BFW, BFH);

        RobotState center = RobotState.at(400, 300, 0, 0);
        CandidatePosition[] inCenter = ReachableEnvelope.getAllCandidates(center, BFW, BFH);

        assertTrue(inCorner.length < inCenter.length,
                "Corner (" + inCorner.length + ") should have fewer candidates than center ("
                        + inCenter.length + ")");
    }

    @Test
    void subsampledCandidatesRespectMax() {
        RobotState state = RobotState.at(400, 300, 0, 4);
        CandidatePosition[] sub = ReachableEnvelope.getCandidates(state, BFW, BFH, 30);
        assertTrue(sub.length <= 30);
        assertTrue(sub.length > 0);
    }

    @Test
    void jitterProducesDifferentPositions() {
        RobotState state = RobotState.at(400, 300, 0, 4);
        CandidatePosition[] a = ReachableEnvelope.getCandidates(state, BFW, BFH, 30);
        CandidatePosition[] b = ReachableEnvelope.getCandidates(state, BFW, BFH, 30);

        // With jitter + random subsampling, highly unlikely to get identical results
        boolean anyDifferent = false;
        int minLen = Math.min(a.length, b.length);
        for (int i = 0; i < minLen; i++) {
            if (a[i].x != b[i].x || a[i].y != b[i].y) {
                anyDifferent = true;
                break;
            }
        }
        assertTrue(anyDifferent, "Two calls should produce different positions due to jitter");
    }

    @Test
    void rotationWorksForDifferentHeadings() {
        RobotState north = RobotState.at(400, 300, 0, 4);
        RobotState east = RobotState.at(400, 300, Math.PI / 2, 4);

        CandidatePosition[] nCands = ReachableEnvelope.getAllCandidates(north, BFW, BFH);
        CandidatePosition[] eCands = ReachableEnvelope.getAllCandidates(east, BFW, BFH);

        // Same count (center of field, no wall clipping difference expected)
        assertEquals(nCands.length, eCands.length,
                "Same position, different heading should yield same count");

        // But positions should be rotated — check that mean X differs
        double nMeanX = 0, eMeanX = 0;
        for (CandidatePosition c : nCands) nMeanX += c.x;
        for (CandidatePosition c : eCands) eMeanX += c.x;
        nMeanX /= nCands.length;
        eMeanX /= eCands.length;
        // Heading north: symmetric around center → mean ≈ 400
        // Heading east: shifted right → mean > 400
        assertTrue(eMeanX > nMeanX + 10,
                "East-facing should shift candidates right: nMeanX=" + nMeanX + ", eMeanX=" + eMeanX);
    }

    @Test
    void countReachableMatchesGetAll() {
        // Very near a corner — guaranteed wall clipping
        RobotState state = RobotState.at(25, 25, Math.PI / 4, 6);
        CandidatePosition[] all = ReachableEnvelope.getAllCandidates(state, BFW, BFH);
        int[] counts = ReachableEnvelope.countReachable(state, BFW, BFH);

        assertEquals(all.length, counts[0],
                "countReachable surviving should match getAllCandidates length");
        assertTrue(counts[1] > counts[0],
                "Total (" + counts[1] + ") should be > surviving (" + counts[0]
                        + ") near a corner");
    }

    @Test
    void allCandidatesInsideWalls() {
        RobotState state = RobotState.at(50, 50, 1.0, 8);
        CandidatePosition[] all = ReachableEnvelope.getAllCandidates(state, BFW, BFH);
        for (CandidatePosition c : all) {
            assertTrue(c.x >= 18 && c.x <= BFW - 18,
                    "x=" + c.x + " outside walls");
            assertTrue(c.y >= 18 && c.y <= BFH - 18,
                    "y=" + c.y + " outside walls");
        }
    }
}

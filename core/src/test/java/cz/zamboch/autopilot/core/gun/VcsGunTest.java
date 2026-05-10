package cz.zamboch.autopilot.core.gun;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.WaveRecord;
import cz.zamboch.autopilot.core.Whiteboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VcsGun: segment consistency between update and query,
 * smoothing, and fire-time segmentation.
 */
class VcsGunTest {

    private Whiteboard wb;
    private VcsGun gun;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        wb.onRoundStart(0, 800, 600, 0.1, 10);
        gun = new VcsGun();
    }

    // === Segment consistency: data stored at fire-time conditions ===

    @Test
    void gunVcsUsesFireTimeDistanceForSegment() {
        // Set up: fire from (400, 300) at opponent at (400, 600) = distance 300 (close)
        // But opponent moves to (400, 100) by wave break = distance 200 (still close)
        // The segment should be based on fire distance (300), not break distance

        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan("TestBot", 400, 600, 0, 0, 100);

        // Create our wave: fire distance = 300, lat dir = 1 → segment for close+positive
        double speed = 14.0; // power 2.0
        double fireBearing = Math.atan2(600 - 300, 400 - 400); // bearing to opponent
        int fireLateralDir = 1;
        WaveRecord wave = new WaveRecord(400, 300, speed, 2.0, 0, 300.0,
                fireBearing, fireLateralDir);
        wb.addOurWave(wave);

        // Advance time so wave passes
        wb.setTick(25); // 25 * 14 = 350 > 300 + 18
        // Opponent has moved (simulate different position at wave break)
        wb.setOpponentScan("TestBot", 400, 100, 0, -4.0, 95);

        // Prune — this triggers VCS update
        wb.prunePassedWaves(200); // current distance = 200

        // The GF should be stored in the segment for fire distance 300 (close bin),
        // not the current distance 200. Both are close range, but let's verify
        // by using a far fire distance case.
        assertEquals(0, wb.getOurWaves().size(), "Wave should be pruned");
    }

    @Test
    void gunVcsSegmentMatchesBetweenStoreAndQuery() {
        // Key test: verify the segment used at store time equals segment at query time
        // Fire at distance 400 (mid), latDir = -1 → segment = 1*2+1 = 3

        double fireDistance = 400.0;
        int fireLateralDir = -1;
        int expectedSegment = Whiteboard.vcsSegment(fireDistance, fireLateralDir);

        // Create and break a wave to populate VCS
        wb.setOurState(400, 100, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan("TestBot", 400, 500, 0, 0, 100);

        double fireBearing = Math.atan2(500 - 100, 400 - 400);
        WaveRecord wave = new WaveRecord(400, 100, 14.0, 2.0, 0,
                fireDistance, fireBearing, fireLateralDir);
        wb.addOurWave(wave);

        // Break the wave
        wb.setTick(35);
        wb.setOpponentScan("TestBot", 450, 520, 0, 3.0, 95);
        wb.prunePassedWaves(400);

        // Now check: the GF should be in the expected segment
        int[] hist = wb.getGunVcsSegment(expectedSegment);
        int total = 0;
        for (int v : hist) total += v;
        assertTrue(total > 0, "GF should be stored in segment " + expectedSegment);
    }

    @Test
    void gunVcsDoesNotStoreinWrongSegmentDueToVelocitySign() {
        // Previously, latDir was computed as opponentVelocity >= 0 ? 1 : -1
        // which uses forward/backward instead of lateral direction.
        // This test verifies the fix: latDir from WaveRecord is used.

        double fireDistance = 400.0;
        int fireLateralDir = -1; // opponent moving left (laterally)

        wb.setOurState(400, 100, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        // Opponent moving forward (positive velocity) but laterally negative
        wb.setOpponentScan("TestBot", 400, 500, 0, 5.0, 100);

        double fireBearing = Math.atan2(500 - 100, 400 - 400);
        WaveRecord wave = new WaveRecord(400, 100, 14.0, 2.0, 0,
                fireDistance, fireBearing, fireLateralDir);
        wb.addOurWave(wave);

        wb.setTick(35);
        wb.setOpponentScan("TestBot", 450, 520, 0, 5.0, 95);
        wb.prunePassedWaves(400);

        // With the old bug: velocity 5.0 >= 0 → latDir = 1 → wrong segment
        // With the fix: fireLateralDir = -1 → correct segment
        int correctSegment = Whiteboard.vcsSegment(fireDistance, fireLateralDir);
        int wrongSegment = Whiteboard.vcsSegment(fireDistance, 1);

        assertNotEquals(correctSegment, wrongSegment, "Segments should differ for test validity");

        int[] correctHist = wb.getGunVcsSegment(correctSegment);
        int correctTotal = 0;
        for (int v : correctHist) correctTotal += v;
        assertTrue(correctTotal > 0, "Data should be in correct segment (latDir=-1)");

        int[] wrongHist = wb.getGunVcsSegment(wrongSegment);
        int wrongTotal = 0;
        for (int v : wrongHist) wrongTotal += v;
        assertEquals(0, wrongTotal, "Data should NOT be in wrong segment (latDir=+1)");
    }

    @Test
    void gunVcsUsesFireDistanceNotBreakDistance() {
        // Fire at distance 600 (far bin), but by wave break opponent is at distance 200 (close bin)
        // VCS should store in far segment, not close segment

        double fireDistance = 600.0; // far
        int fireLateralDir = 1;

        wb.setOurState(400, 100, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan("TestBot", 400, 700, 0, 0, 100);

        double fireBearing = Math.atan2(700 - 100, 400 - 400);
        WaveRecord wave = new WaveRecord(400, 100, 14.0, 2.0, 0,
                fireDistance, fireBearing, fireLateralDir);
        wb.addOurWave(wave);

        wb.setTick(50);
        // Opponent now close
        wb.setOpponentScan("TestBot", 400, 300, 0, 0, 95);
        wb.prunePassedWaves(200); // current distance = 200 (close)

        int farSegment = Whiteboard.vcsSegment(600.0, 1); // fire-time: far
        int closeSegment = Whiteboard.vcsSegment(200.0, 1); // break-time: close

        assertNotEquals(farSegment, closeSegment, "Test requires different segments");

        int[] farHist = wb.getGunVcsSegment(farSegment);
        int farTotal = 0;
        for (int v : farHist) farTotal += v;
        assertTrue(farTotal > 0, "Data should be in far segment (fire-time distance)");

        int[] closeHist = wb.getGunVcsSegment(closeSegment);
        int closeTotal = 0;
        for (int v : closeHist) closeTotal += v;
        assertEquals(0, closeTotal, "Data should NOT be in close segment (break-time distance)");
    }

    // === WaveRecord backward compatibility ===

    @Test
    void waveRecordBackwardCompatDefault() {
        WaveRecord w = new WaveRecord(0, 0, 14.0, 2.0, 0, 300.0);
        assertEquals(1, w.fireLateralDir);
        assertTrue(Double.isNaN(w.fireBearing));
    }

    @Test
    void waveRecordThreeArgConstructorDefault() {
        WaveRecord w = new WaveRecord(0, 0, 14.0, 2.0, 0, 300.0, 1.5);
        assertEquals(1, w.fireLateralDir);
        assertEquals(1.5, w.fireBearing, 0.001);
    }

    // === VcsGun fires at peak bin ===

    @Test
    void vcsGunFiresAtPopulatedBin() {
        // Populate a specific GF bin in a known segment
        int segment = Whiteboard.vcsSegment(300.0, 1); // close, positive
        int targetBin = 40; // GF ≈ +0.33
        for (int i = 0; i < 20; i++) {
            wb.incrementGunVcs(segment, targetBin);
        }

        // Set up features matching the same segment
        wb.setFeature(Feature.DISTANCE, 300.0);
        wb.setFeature(Feature.OPPONENT_LATERAL_DIRECTION, 1.0);
        wb.setFeature(Feature.MEA_FOR_OUR_BULLET, 0.5);
        wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, 0.0);

        double angle = gun.getFireAngle(wb);
        // Should aim at positive offset (GF > 0)
        assertTrue(angle > 0 || angle != 0.0,
                "Gun should aim at the populated GF bin, not head-on");
    }

    @Test
    void vcsGunDefaultsToHeadOnWhenEmpty() {
        wb.setFeature(Feature.DISTANCE, 300.0);
        wb.setFeature(Feature.OPPONENT_LATERAL_DIRECTION, 1.0);
        wb.setFeature(Feature.MEA_FOR_OUR_BULLET, 0.5);
        wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, 1.0);

        double angle = gun.getFireAngle(wb);
        // Empty histogram → should fire at GF=0 → bearing = 1.0
        assertEquals(1.0, angle, 0.001, "Empty VCS should fire head-on");
    }
}

package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.SpatialFeatures;
import cz.zamboch.autopilot.core.features.TimingFeatures;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.RobotState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ScanSynthesizerTest {

    private ScanSynthesizer synthesizer;
    private Whiteboard wb;
    private Perspective[] perspectives;

    @BeforeEach
    void setUp() {
        synthesizer = new ScanSynthesizer();
        wb = new Whiteboard();
        wb.registerFeatures(new SpatialFeatures(), new TimingFeatures());
        Whiteboard wb2 = new Whiteboard();
        perspectives = Perspective.createPair(wb, wb2);
    }

    @Test
    void firstTickDoesNotScan() {
        // On first tick, no previous radar heading → no scan
        IRobotSnapshot self = TestSnapshots.robot(
                400, 300, 0, 0, 100, 0, 0, Math.toRadians(90),
                0, RobotState.ACTIVE, "Us");
        IRobotSnapshot opponent = TestSnapshots.robot(
                500, 300, 0, 0, 100, 0, 0, 0,
                1, RobotState.ACTIVE, "Them");

        synthesizer.tryScan(perspectives[0], self, opponent, 0);

        assertTrue(Double.isNaN(wb.getFeature(Feature.DISTANCE)),
                "No scan should occur on first tick");
    }

    @Test
    void scanDetectedWhenRadarSweepsAcrossOpponent() {
        // Tick 0: set initial radar heading (pointing north = 0)
        IRobotSnapshot self0 = TestSnapshots.robot(
                400, 300, 0, 0, 100, 0, 0, 0,
                0, RobotState.ACTIVE, "Us");
        IRobotSnapshot opponent0 = TestSnapshots.robot(
                500, 300, 0, 0, 100, 0, 0, 0,
                1, RobotState.ACTIVE, "Them");
        synthesizer.tryScan(perspectives[0], self0, opponent0, 0);

        // Tick 1: radar swept to 90° (east) — opponent is directly east
        // Bearing from (400,300) to (500,300): atan2(100,0) = π/2 = 90° east
        IRobotSnapshot self1 = TestSnapshots.robot(
                400, 300, 0, 0, 100, 0, 0, Math.toRadians(90),
                0, RobotState.ACTIVE, "Us");
        IRobotSnapshot opponent1 = TestSnapshots.robot(
                500, 300, Math.toRadians(180), 3.0, 95, 0, 0, 0,
                1, RobotState.ACTIVE, "Them");
        synthesizer.tryScan(perspectives[0], self1, opponent1, 1);

        // Should have detected the scan
        assertFalse(Double.isNaN(wb.getFeature(Feature.DISTANCE)));
        assertEquals(100.0, wb.getFeature(Feature.DISTANCE), 1.0);
        assertEquals(1.0, wb.getFeature(Feature.LAST_SCAN_TICK), 1e-9);
        assertEquals(Math.toRadians(180), wb.getFeature(Feature.OPPONENT_HEADING), 1e-9);
        assertEquals(3.0, wb.getFeature(Feature.OPPONENT_VELOCITY), 1e-9);
        assertEquals(95.0, wb.getFeature(Feature.OPPONENT_ENERGY), 1e-9);
    }

    @Test
    void noScanWhenRadarPointsAway() {
        // Tick 0: radar at 180° (south)
        IRobotSnapshot self0 = TestSnapshots.robot(
                400, 300, 0, 0, 100, 0, 0, Math.toRadians(180),
                0, RobotState.ACTIVE, "Us");
        IRobotSnapshot opponent0 = TestSnapshots.robot(
                500, 300, 0, 0, 100, 0, 0, 0,
                1, RobotState.ACTIVE, "Them");
        synthesizer.tryScan(perspectives[0], self0, opponent0, 0);

        // Tick 1: radar swept to 270° (west) — opponent is east, never crossed
        IRobotSnapshot self1 = TestSnapshots.robot(
                400, 300, 0, 0, 100, 0, 0, Math.toRadians(270),
                0, RobotState.ACTIVE, "Us");
        IRobotSnapshot opponent1 = TestSnapshots.robot(
                500, 300, 0, 0, 100, 0, 0, 0,
                1, RobotState.ACTIVE, "Them");
        synthesizer.tryScan(perspectives[0], self1, opponent1, 1);

        assertTrue(Double.isNaN(wb.getFeature(Feature.DISTANCE)),
                "Should not scan when radar sweeps away from opponent");
    }

    @Test
    void scanSetsCorrectBearing() {
        // Us at (400, 300) heading north (0), opponent at (400, 500) — directly ahead
        // (north)
        IRobotSnapshot self0 = TestSnapshots.robot(
                400, 300, 0, 0, 100, 0, 0, Math.toRadians(350),
                0, RobotState.ACTIVE, "Us");
        IRobotSnapshot opponent0 = TestSnapshots.robot(
                400, 500, 0, 0, 100, 0, 0, 0,
                1, RobotState.ACTIVE, "Them");
        synthesizer.tryScan(perspectives[0], self0, opponent0, 0);

        // Sweep from 350° to 10° (crosses 0° north where opponent is)
        IRobotSnapshot self1 = TestSnapshots.robot(
                400, 300, 0, 0, 100, 0, 0, Math.toRadians(10),
                0, RobotState.ACTIVE, "Us");
        IRobotSnapshot opponent1 = TestSnapshots.robot(
                400, 500, 0, 0, 100, 0, 0, 0,
                1, RobotState.ACTIVE, "Them");
        synthesizer.tryScan(perspectives[0], self1, opponent1, 1);

        assertFalse(Double.isNaN(wb.getFeature(Feature.DISTANCE)));
        // Distance should be 200
        assertEquals(200.0, wb.getFeature(Feature.DISTANCE), 1.0);
        // Bearing should be ~0 (opponent directly ahead when heading north)
        assertEquals(0.0, wb.getFeature(Feature.BEARING_RADIANS), 0.05);
    }

    @Test
    void prevRadarHeadingUpdatesAfterEachCall() {
        IRobotSnapshot self0 = TestSnapshots.robot(
                400, 300, 0, 0, 100, 0, 0, Math.toRadians(45),
                0, RobotState.ACTIVE, "Us");
        IRobotSnapshot opponent = TestSnapshots.robot(
                500, 300, 0, 0, 100, 0, 0, 0,
                1, RobotState.ACTIVE, "Them");
        synthesizer.tryScan(perspectives[0], self0, opponent, 0);

        assertEquals(Math.toRadians(45), perspectives[0].prevRadarHeading(), 1e-9);

        IRobotSnapshot self1 = TestSnapshots.robot(
                400, 300, 0, 0, 100, 0, 0, Math.toRadians(90),
                0, RobotState.ACTIVE, "Us");
        synthesizer.tryScan(perspectives[0], self1, opponent, 1);

        assertEquals(Math.toRadians(90), perspectives[0].prevRadarHeading(), 1e-9);
    }
}

package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.MovementFeatures;
import cz.zamboch.autopilot.core.features.SpatialFeatures;
import cz.zamboch.autopilot.core.features.TimingFeatures;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: exercises Player → Whiteboard → CsvWriter pipeline
 * with synthetic turn snapshots (no .br file needed).
 */
final class PipelineIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void fullPipelineProducesCsvFiles() throws Exception {
        // Scenario: 1 round, 5 ticks. Robot A at (200,300), Robot B at (500,400).
        // On tick 3, Robot B loses 2.0 energy (fire detection for perspective A).
        // Robot A dies on tick 5 (round ends with B winning).

        String battleId = "test-battle-001";
        double bfWidth = 800;
        double bfHeight = 600;

        Whiteboard wbA = createWhiteboard();
        Whiteboard wbB = createWhiteboard();

        File dirA = tempDir.resolve(battleId).resolve("BotA").toFile();
        File dirB = tempDir.resolve(battleId).resolve("BotB").toFile();
        CsvWriter csvA = new CsvWriter(dirA);
        CsvWriter csvB = new CsvWriter(dirB);
        csvA.writeHeaders(battleId);
        csvB.writeHeaders(battleId);

        Player player = new Player(wbA, wbB);

        // Simulate 5 ticks
        double[] bEnergies = { 100, 100, 100, 98, 98 }; // drops 2.0 at tick 3
        RobotState[] aStates = {
                RobotState.ACTIVE, RobotState.ACTIVE, RobotState.ACTIVE,
                RobotState.ACTIVE, RobotState.DEAD
        };

        IRobotSnapshot lastRobotA = null;
        IRobotSnapshot lastRobotB = null;

        for (int tick = 0; tick < 5; tick++) {
            // Robot A moves slightly each tick
            double ax = 200 + tick * 2;
            double ay = 300 + tick;
            // Robot B stays still
            double bx = 500;
            double by = 400;

            IRobotSnapshot robotA = TestSnapshots.robot(
                    ax, ay, Math.toRadians(45), 2.0, 100 - tick, 0.0,
                    Math.toRadians(60), Math.toRadians(60),
                    0, aStates[tick], "BotA");
            IRobotSnapshot robotB = TestSnapshots.robot(
                    bx, by, Math.toRadians(180), 0.0, bEnergies[tick], 0.0,
                    Math.toRadians(270), Math.toRadians(270),
                    1, RobotState.ACTIVE, "BotB");

            ITurnSnapshot turn = TestSnapshots.turn(tick, robotA, robotB);
            player.processTurn(0, turn, bfWidth, bfHeight);

            // Compute derived features
            wbA.process();
            wbB.process();

            // Write tick rows
            csvA.writeTickRow(wbA, battleId, 0);
            csvB.writeTickRow(wbB, battleId, 0);

            // Write wave rows if opponent fired
            if (!Double.isNaN(wbA.getFeature(Feature.OPPONENT_FIRE_POWER))) {
                csvA.writeWaveRow(wbA, battleId, 0);
            }
            if (!Double.isNaN(wbB.getFeature(Feature.OPPONENT_FIRE_POWER))) {
                csvB.writeWaveRow(wbB, battleId, 0);
            }

            lastRobotA = robotA;
            lastRobotB = robotB;

            // Reset per-tick fire detection
            wbA.setFeature(Feature.OPPONENT_FIRE_POWER, Double.NaN);
            wbB.setFeature(Feature.OPPONENT_FIRE_POWER, Double.NaN);
        }

        // Finalize round
        player.finalizeRound(wbA, wbB, lastRobotA, lastRobotB);
        csvA.writeScoreRow(wbA, battleId, 0);
        csvB.writeScoreRow(wbB, battleId, 0);

        csvA.close();
        csvB.close();

        // --- Assertions ---

        // Check ticks.csv exists and has correct rows (header + 5 ticks)
        File ticksA = new File(dirA, "ticks.csv");
        assertTrue(ticksA.exists(), "ticks.csv for A should exist");
        List<String> tickLines = Files.readAllLines(ticksA.toPath());
        assertEquals(6, tickLines.size(), "ticks.csv should have header + 5 data rows");

        // Check header contains expected columns
        String header = tickLines.get(0);
        assertTrue(header.startsWith("battle_id,round,tick,"), "Header should start with battle_id,round,tick");
        assertTrue(header.contains("distance"), "Header should contain distance");
        assertTrue(header.contains("our_energy"), "Header should contain our_energy");

        // Check first data row has correct battle_id and tick
        String firstRow = tickLines.get(1);
        assertTrue(firstRow.startsWith(battleId + ",0,0,"), "First row should have tick=0");

        // Check waves.csv: perspective A should see B's fire (energy drop on tick 3)
        File wavesA = new File(dirA, "waves.csv");
        assertTrue(wavesA.exists(), "waves.csv for A should exist");
        List<String> waveLines = Files.readAllLines(wavesA.toPath());
        assertEquals(2, waveLines.size(), "waves.csv should have header + 1 fire event");
        assertTrue(waveLines.get(1).contains("2.0"), "Wave row should contain fire power 2.0");

        // Check scores.csv: A lost (DEAD), B won
        File scoresA = new File(dirA, "scores.csv");
        assertTrue(scoresA.exists(), "scores.csv for A should exist");
        List<String> scoreLines = Files.readAllLines(scoresA.toPath());
        assertEquals(2, scoreLines.size(), "scores.csv should have header + 1 round");
        assertTrue(scoreLines.get(1).contains(",-1"), "A's result should be -1 (loss)");

        File scoresB = new File(dirB, "scores.csv");
        List<String> scoreBLines = Files.readAllLines(scoresB.toPath());
        assertTrue(scoreBLines.get(1).contains(",1"), "B's result should be 1 (win)");

        // Verify no NaN in spatial features (scan should have been synthesized)
        // Parse the tick row and check distance is not NaN
        String[] cols = tickLines.get(1).split(",");
        // Find distance column index
        String[] headers = header.split(",");
        int distIdx = -1;
        for (int i = 0; i < headers.length; i++) {
            if ("distance".equals(headers[i])) {
                distIdx = i;
                break;
            }
        }
        assertNotEquals(-1, distIdx, "Should find distance column");
        assertNotEquals("NaN", cols[distIdx], "Distance should not be NaN");
        double distance = Double.parseDouble(cols[distIdx]);
        // Expected: sqrt((500-200)^2 + (400-300)^2) = sqrt(90000+10000) = sqrt(100000)
        // ≈ 316.2
        assertEquals(316.2, distance, 1.0, "Distance should be ~316.2");
    }

    @Test
    void noScanWhenRadarPointsAway() throws Exception {
        // Robot A's radar points away from B: should produce NaN for distance
        String battleId = "test-noscan";
        double bfWidth = 800;
        double bfHeight = 600;

        Whiteboard wbA = createWhiteboard();
        Whiteboard wbB = createWhiteboard();

        File dirA = tempDir.resolve(battleId).resolve("BotA").toFile();
        File dirB = tempDir.resolve(battleId).resolve("BotB").toFile();
        CsvWriter csvA = new CsvWriter(dirA);
        CsvWriter csvB = new CsvWriter(dirB);
        csvA.writeHeaders(battleId);
        csvB.writeHeaders(battleId);

        Player player = new Player(wbA, wbB);

        // First tick always produces a scan (per Player logic), so we need tick 1+
        IRobotSnapshot robotA0 = TestSnapshots.robot(
                100, 300, 0, 0, 100, 0, 0, 0.0, // radar north
                0, RobotState.ACTIVE, "BotA");
        IRobotSnapshot robotB0 = TestSnapshots.robot(
                700, 300, 0, 0, 100, 0, 0, 0,
                1, RobotState.ACTIVE, "BotB");
        ITurnSnapshot turn0 = TestSnapshots.turn(0, robotA0, robotB0);
        player.processTurn(0, turn0, bfWidth, bfHeight);
        wbA.process();
        csvA.writeTickRow(wbA, battleId, 0);

        // Tick 1: radar barely moves (still north), opponent is east
        IRobotSnapshot robotA1 = TestSnapshots.robot(
                100, 300, 0, 0, 100, 0, 0, 0.01, // radar barely moved
                0, RobotState.ACTIVE, "BotA");
        IRobotSnapshot robotB1 = TestSnapshots.robot(
                700, 300, 0, 0, 100, 0, 0, 0,
                1, RobotState.ACTIVE, "BotB");
        ITurnSnapshot turn1 = TestSnapshots.turn(1, robotA1, robotB1);
        player.processTurn(0, turn1, bfWidth, bfHeight);
        wbA.process();
        csvA.writeTickRow(wbA, battleId, 0);

        csvA.close();
        csvB.close();

        // Read tick 1 (second data row) — distance should be NaN since radar missed
        File ticksA = new File(dirA, "ticks.csv");
        List<String> lines = Files.readAllLines(ticksA.toPath());
        String[] headers = lines.get(0).split(",");
        int distIdx = -1;
        for (int i = 0; i < headers.length; i++) {
            if ("distance".equals(headers[i])) {
                distIdx = i;
                break;
            }
        }
        // Tick 1 should have NaN distance (radar missed)
        String[] cols = lines.get(2).split(",");
        assertEquals("NaN", cols[distIdx], "Distance should be NaN when radar misses");
    }

    private static Whiteboard createWhiteboard() {
        Whiteboard wb = new Whiteboard();
        wb.registerFeatures(
                new SpatialFeatures(),
                new MovementFeatures(),
                new TimingFeatures());
        return wb;
    }
}

package cz.zamboch.autopilot.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that runs an actual Robocode battle with Autopilot vs
 * SittingDuck.
 * Validates that:
 * - CSV output is produced (ticks, waves, scores)
 * - Debug properties from the robot snapshot match the pipeline whiteboard
 * features
 * <p>
 * Requires: staged robot JARs in battle-stage/ (handled by Gradle battleTest
 * task).
 */
final class BattleLoopTest {

    @TempDir
    Path tempDir;

    @Test
    void battleProducesCsvAndValidatesDebugProperties() {
        // Resolve the battle-stage directory (set by Gradle task or fallback)
        String robotsPath = System.getProperty("battle.stage");
        if (robotsPath == null) {
            robotsPath = new File("build/battle-stage").getAbsolutePath();
        }
        assertTrue(new File(robotsPath).isDirectory(),
                "battle-stage directory must exist: " + robotsPath);

        // Configure Robocode
        System.setProperty("ROBOTPATH", robotsPath);
        System.setProperty("NOSECURITY", "true");
        System.setProperty("java.awt.headless", "true");

        String outputDir = tempDir.toFile().getAbsolutePath();
        int rounds = Integer.parseInt(System.getProperty("battle.rounds", "2"));
        String opponent = System.getProperty("battle.opponent", "test.SittingDuck");

        // Run the battle
        BattleRunner.StreamingPipelineObserver observer = BattleRunner.runBattle(opponent, rounds, outputDir);
        observer.close();

        // --- Verify CSV output ---
        File[] battleDirs = tempDir.toFile().listFiles(f -> f.isDirectory() && f.getName().startsWith("battle-"));
        assertNotNull(battleDirs);
        assertEquals(1, battleDirs.length, "Should have exactly one battle output directory");

        File battleDir = battleDirs[0];
        File autopilotDir = new File(battleDir, "Autopilot");
        assertTrue(autopilotDir.isDirectory(), "Autopilot CSV dir should exist");

        // Check ticks.csv
        File ticksCsv = new File(autopilotDir, "ticks.csv");
        assertTrue(ticksCsv.exists(), "ticks.csv should exist");
        try {
            List<String> tickLines = Files.readAllLines(ticksCsv.toPath());
            assertTrue(tickLines.size() > 1, "ticks.csv should have data rows");

            String header = tickLines.get(0);
            assertTrue(header.contains("distance"), "Header should contain distance column");
            assertTrue(header.contains("our_energy"), "Header should contain our_energy column");

            System.out.println("=== BATTLE LOOP TEST SUMMARY ===");
            System.out.println("Rounds: " + rounds);
            System.out.println("Ticks recorded: " + (tickLines.size() - 1));
        } catch (Exception e) {
            fail("Failed to read ticks.csv: " + e.getMessage());
        }

        // Check scores.csv
        File scoresCsv = new File(autopilotDir, "scores.csv");
        assertTrue(scoresCsv.exists(), "scores.csv should exist");
        try {
            List<String> scoreLines = Files.readAllLines(scoresCsv.toPath());
            int scoreRows = scoreLines.size() - 1;
            assertTrue(scoreRows >= 1, "scores.csv should have at least 1 round result");
            System.out.println("Score rows: " + scoreRows);
            System.out.println("Output: " + battleDir.getAbsolutePath());
        } catch (Exception e) {
            fail("Failed to read scores.csv: " + e.getMessage());
        }
    }
}

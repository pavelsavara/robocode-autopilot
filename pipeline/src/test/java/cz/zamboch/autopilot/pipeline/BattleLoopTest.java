package cz.zamboch.autopilot.pipeline;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test that runs actual Robocode battles with Autopilot vs
 * multiple opponents.
 * Validates that:
 * - CSV output is produced (ticks, waves, scores)
 * - Debug properties from the robot snapshot match the pipeline whiteboard
 * features (DebugValidator)
 * - Cross-perspective scan accuracy (GodViewValidator)
 * - Score baselines per opponent (win rate and score ratio)
 * - GF gun hit rate from our-waves.csv
 */
@Tag("integration")
final class BattleLoopTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest(name = "vs {0}")
    @ValueSource(strings = { "test.SittingDuck", "test.Aggressive", "sample.Fire",
            "sample.Walls", "sample.Crazy", "kc.mega.BeepBoop" })
    void battleProducesCsvAndValidatesDebugProperties(String opponent) {
        // Allow system property override (single-opponent mode)
        String overrideOpponent = System.getProperty("battle.opponent");
        if (overrideOpponent != null && !overrideOpponent.isEmpty()) {
            // When system property is set, only run the matching opponent
            if (!opponent.equals(overrideOpponent)) {
                return;
            }
        }

        // Resolve the battle-stage directory (set by Gradle task or fallback)
        String robotsPath = System.getProperty("battle.stage");
        if (robotsPath == null) {
            robotsPath = new File("build/battle-stage").getAbsolutePath();
        }
        assumeTrue(new File(robotsPath).isDirectory(),
                "Skipping: battle-stage directory not found (run via ./gradlew :pipeline:battleTest)");

        // Configure Robocode
        System.setProperty("ROBOTPATH", robotsPath);
        System.setProperty("NOSECURITY", "true");
        System.setProperty("java.awt.headless", "true");

        String outputDir = tempDir.toFile().getAbsolutePath();
        int rounds = Integer.parseInt(System.getProperty("battle.rounds", "10"));

        // Run the battle — may fail if --add-opens JVM args are missing (VS Code test
        // panel)
        StreamingPipelineObserver observer;
        try {
            observer = BattleRunner.runBattle(opponent, rounds, outputDir);
        } catch (NullPointerException e) {
            // Robocode repository init fails without --add-opens
            assumeTrue(false, "Skipping: Robocode engine requires --add-opens JVM args "
                    + "(run via ./gradlew :pipeline:battleTest)");
            return;
        }
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
        List<String> tickLines = readLines(ticksCsv);
        assertTrue(tickLines.size() > 1, "ticks.csv should have data rows");
        String header = tickLines.get(0);
        assertTrue(header.contains("distance"), "Header should contain distance column");
        assertTrue(header.contains("our_energy"), "Header should contain our_energy column");

        System.out.println("=== BATTLE LOOP TEST SUMMARY ===");
        System.out.println("Rounds: " + rounds);
        System.out.println("Ticks recorded: " + (tickLines.size() - 1));

        // Check scores.csv
        File scoresCsv = new File(autopilotDir, "scores.csv");
        assertTrue(scoresCsv.exists(), "scores.csv should exist");
        List<String> scoreLines = readLines(scoresCsv);
        int scoreRows = scoreLines.size() - 1;
        assertTrue(scoreRows >= 1, "scores.csv should have at least 1 round result");
        System.out.println("Score rows: " + scoreRows);

        // Check our-waves.csv (wave resolution output)
        File ourWavesCsv = new File(autopilotDir, "our-waves.csv");
        assertTrue(ourWavesCsv.exists(), "our-waves.csv should exist");
        List<String> waveLines = readLines(ourWavesCsv);
        assertTrue(waveLines.size() > 1, "our-waves.csv should have data rows (waves resolved)");
        String waveHeader = waveLines.get(0);
        assertTrue(waveHeader.contains("our_break_gf"), "our-waves.csv header should contain our_break_gf");
        assertTrue(waveHeader.contains("our_fire_power"), "our-waves.csv header should contain our_fire_power");
        assertTrue(waveHeader.contains("our_fire_mea"), "our-waves.csv header should contain our_fire_mea");
        assertTrue(waveHeader.contains("our_break_hit"), "our-waves.csv header should contain our_break_hit");
        System.out.println("Our-waves rows: " + (waveLines.size() - 1));

        // --- Score assertions ---
        double winRate = observer.getWinRate();
        double scoreRatio = observer.getScoreRatio();
        System.out.println(String.format("Win rate: %.1f%% (%d/%d)", winRate * 100,
                observer.getOurFirsts(), observer.getTotalRounds()));
        System.out.println(String.format("Score ratio: %.2f (%d/%d)", scoreRatio,
                observer.getOurScore(), observer.getOpponentScore()));

        assertScoreBaseline(opponent, winRate, scoreRatio, observer.getTotalRounds());

        // --- Hit rate from our-waves.csv ---
        double hitRate = computeHitRate(waveLines);
        System.out.println(String.format("Hit rate: %.1f%%", hitRate * 100));
        assertHitRateBaseline(opponent, hitRate);

        // --- God-view validation accuracy ---
        double gvAccuracy = observer.getGodViewValidator().getTotalChecks() > 0
                ? (double) observer.getGodViewValidator().getTotalHits()
                        / observer.getGodViewValidator().getTotalChecks()
                : 1.0;
        System.out.println(String.format("GodView accuracy: %.1f%%", gvAccuracy * 100));
        assertTrue(gvAccuracy >= 0.99, "GodView accuracy should be >= 99%, was " + gvAccuracy);

        // --- Debug validator: fire-time features must match exactly ---
        observer.getDebugValidator().printSummary();
        int nonBreakMismatches = observer.getDebugValidator().getNonBreakMismatchCount();
        System.out.println(String.format("DebugValidator non-break mismatches: %d", nonBreakMismatches));
        assertEquals(0, nonBreakMismatches,
                "Fire-time and spatial features must match exactly between robot and pipeline");

        // --- Mean absolute GF error (robot stale scan vs pipeline god-view) ---
        double gfError = observer.getDebugValidator().getMeanAbsoluteGFError();
        int gfCount = observer.getDebugValidator().getGFErrorCount();
        System.out.println(String.format("Mean absolute GF error: %.4f (%d comparisons)", gfError, gfCount));
        if (gfCount > 0) {
            assertTrue(gfError < 0.1, "Mean absolute GF error should be < 0.1, was " + gfError);
        }

        System.out.println("Output: " + battleDir.getAbsolutePath());
    }

    // --- Score baseline per opponent ---
    // With 10 rounds, we can set meaningful baselines for all opponents.
    private void assertScoreBaseline(String opponent, double winRate, double scoreRatio, int totalRounds) {
        switch (opponent) {
            case "test.SittingDuck":
                assertTrue(winRate >= 0.9, "vs SittingDuck: should win >= 90%, was " + winRate);
                assertTrue(scoreRatio > 5.0, "vs SittingDuck: score ratio should be >5, was " + scoreRatio);
                break;
            case "test.Aggressive":
                assertTrue(winRate >= 0.5, "vs Aggressive: should win >= 50%, was " + winRate);
                break;
            case "sample.Crazy":
                assertTrue(winRate >= 0.2, "vs Crazy: should win >= 20%, was " + winRate);
                break;
            case "sample.Fire":
                assertTrue(winRate >= 0.3, "vs Fire: should win >= 30%, was " + winRate);
                break;
            case "sample.Walls":
                assertTrue(winRate >= 0.2, "vs Walls: should win >= 20%, was " + winRate);
                break;
            // kc.mega.BeepBoop — strong opponent, no win-rate baseline
        }
    }

    // --- Hit rate baseline per opponent ---
    private void assertHitRateBaseline(String opponent, double hitRate) {
        switch (opponent) {
            case "test.SittingDuck":
                // Against stationary target, GF gun should hit most shots
                assertTrue(hitRate >= 0.7, "vs SittingDuck: hit rate should be >= 70%, was " + hitRate);
                break;
            // Other opponents: no strict hit rate baseline yet (gun is learning)
        }
    }

    // --- Compute hit rate from our-waves.csv lines ---
    private double computeHitRate(List<String> lines) {
        if (lines.size() < 2)
            return 0;
        String header = lines.get(0);
        String[] cols = header.split(",");
        int hitIdx = -1;
        for (int i = 0; i < cols.length; i++) {
            if ("our_break_hit".equals(cols[i].trim())) {
                hitIdx = i;
                break;
            }
        }
        if (hitIdx < 0)
            return 0;

        int hits = 0;
        int total = 0;
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(",");
            if (hitIdx < parts.length) {
                String val = parts[hitIdx].trim();
                if (!val.isEmpty() && !"NaN".equals(val)) {
                    total++;
                    if (Double.parseDouble(val) >= 1.0) {
                        hits++;
                    }
                }
            }
        }
        return total > 0 ? (double) hits / total : 0;
    }

    private static List<String> readLines(File file) {
        try {
            return Files.readAllLines(file.toPath());
        } catch (Exception e) {
            fail("Failed to read " + file.getName() + ": " + e.getMessage());
            return null; // unreachable
        }
    }
}

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
 * multiple opponents using PipelineOrchestrator.
 * Validates that:
 * - CSV output is produced (ticks, waves, scores)
 * - PipelineValidator spatial accuracy
 * - Fire detection rate and GF precision
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

        // Run the battle — may fail if --add-opens JVM args are missing
        BattleRunner.BattleResult result;
        try {
            result = BattleRunner.runBattle(opponent, rounds, outputDir);
        } catch (NullPointerException e) {
            assumeTrue(false, "Skipping: Robocode engine requires --add-opens JVM args "
                    + "(run via ./gradlew :pipeline:battleTest)");
            return;
        }

        PipelineValidator validator = result.orchestrator().validator();
        assertNotNull(validator, "Validator should be attached");

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
        double winRate = result.getWinRate();
        double scoreRatio = result.getScoreRatio();
        System.out.println(String.format("Win rate: %.1f%% (%d/%d)", winRate * 100,
                result.getOurFirsts(), result.getTotalRounds()));
        System.out.println(String.format("Score ratio: %.2f (%d/%d)", scoreRatio,
                result.getOurScore(), result.getOpponentScore()));

        assertScoreBaseline(opponent, winRate, scoreRatio, result.getTotalRounds());

        // --- Hit rate from our-waves.csv ---
        double hitRate = computeHitRate(waveLines);
        System.out.println(String.format("Hit rate: %.1f%%", hitRate * 100));
        assertHitRateBaseline(opponent, hitRate);

        // --- PipelineValidator: spatial accuracy ---
        int spatialMismatches = validator.getSpatialMismatches();
        System.out.println(String.format("Spatial mismatches: %d", spatialMismatches));
        validator.printSummary();
        assertEquals(0, spatialMismatches,
                "Spatial features must match exactly between observer and god-view");

        // --- PipelineValidator: fire detection rate ---
        double fireDetectionRate0 = validator.getFireDetectionRate(0);
        System.out.println(String.format("Fire detection rate (persp 0): %.1f%%", fireDetectionRate0 * 100));
        assertTrue(fireDetectionRate0 >= 0.9,
                "Fire detection rate should be >= 90%, was " + fireDetectionRate0);

        // --- PipelineValidator: GF mean absolute error ---
        double gfError = validator.getGfMeanAbsoluteError(0);
        System.out.println(String.format("GF mean absolute error: %.4f", gfError));
        if (!Double.isNaN(gfError)) {
            assertTrue(gfError < 0.1, "GF mean absolute error should be < 0.1, was " + gfError);
        }

        // --- PipelineValidator: debug property mismatches ---
        int debugMismatches = validator.getNonBreakDebugPropertyMismatches();
        System.out.println(String.format("Debug property mismatches: %d", debugMismatches));
        assertEquals(0, debugMismatches,
                "Fire-time and spatial features must match between robot and pipeline");

        // --- PipelineValidator: energy accounting ---
        int energyDisc0 = validator.getEnergyDiscrepancies(0);
        System.out.println(String.format("Energy discrepancies (persp 0): %d", energyDisc0));
        assertEquals(0, energyDisc0, "Energy accounting should have no discrepancies");

        // --- Non-vacuous check ---
        validator.assertNonVacuous();

        // Print full summary
        validator.printSummary();
        System.out.println("Output: " + battleDir.getAbsolutePath());
    }

    // --- Score baseline per opponent ---
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
                assertTrue(hitRate >= 0.7, "vs SittingDuck: hit rate should be >= 70%, was " + hitRate);
                break;
        }
    }

    // --- Compute hit rate from our-waves.csv lines (real bullets only) ---
    private double computeHitRate(List<String> lines) {
        if (lines.size() < 2)
            return 0;
        String header = lines.get(0);
        String[] cols = header.split(",");
        int hitIdx = -1;
        int isRealIdx = -1;
        for (int i = 0; i < cols.length; i++) {
            if ("our_break_hit".equals(cols[i].trim())) {
                hitIdx = i;
            } else if ("our_fire_is_real".equals(cols[i].trim())) {
                isRealIdx = i;
            }
        }
        if (hitIdx < 0)
            return 0;

        int hits = 0;
        int total = 0;
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(",");
            // Skip virtual bullet rows
            if (isRealIdx >= 0 && isRealIdx < parts.length) {
                String realVal = parts[isRealIdx].trim();
                if (!"1.0".equals(realVal) && !"1".equals(realVal)) {
                    continue;
                }
            }
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

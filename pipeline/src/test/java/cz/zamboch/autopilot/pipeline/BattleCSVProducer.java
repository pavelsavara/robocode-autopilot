package cz.zamboch.autopilot.pipeline;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("integration")
@Tag("battle")
final class BattleCSVProducer {
    private static final String[] ROBOTS = {
            "cz.zamboch.Autopilot",
            "kc.mega.BeepBoop",
            "aaa.r.ScalarR",
            "jk.mega.DrussGT",
            "voidious.Diamond",
            "rsalesc.mega.Knight"
    };

    @Test
    void produceRobotSideCsvMatrix() throws Exception {
        String robotsPath = System.getProperty("battle.stage");
        if (robotsPath == null) {
            robotsPath = new File("build/battle-stage").getAbsolutePath();
        }
        assumeTrue(new File(robotsPath).isDirectory(),
                "Skipping: battle-stage directory not found (run via ./gradlew :pipeline:battleCsvProducer)");

        System.setProperty("ROBOTPATH", robotsPath);
        System.setProperty("NOSECURITY", "true");
        System.setProperty("java.awt.headless", "true");

        int rounds = Integer.parseInt(System.getProperty("battle.rounds", "1"));
        long baseSeed = Long.parseLong(System.getProperty("battle.seed", Long.toString(System.currentTimeMillis())));
        File outputRoot = new File(System.getProperty("battle.csv.dir",
                new File("build/battle-csv-producer").getAbsolutePath()),
                baseSeed + "-" + System.currentTimeMillis());
        assertTrue(outputRoot.mkdirs() || outputRoot.isDirectory(),
                "Should be able to create CSV output directory: " + outputRoot);
        System.out.println("=== BATTLE CSV PRODUCER SEED: " + baseSeed + " ===");
        System.out.println("=== BATTLE CSV PRODUCER OUTPUT: " + outputRoot.getAbsolutePath() + " ===");

        String onlyA = System.getProperty("battle.robotA");
        String onlyB = System.getProperty("battle.robotB");
        int battleIndex = 0;
        int battlesRun = 0;
        for (String robotA : ROBOTS) {
            for (String robotB : ROBOTS) {
                if (onlyA != null && !onlyA.equals(robotA)) {
                    battleIndex++;
                    continue;
                }
                if (onlyB != null && !onlyB.equals(robotB)) {
                    battleIndex++;
                    continue;
                }

                long battleSeed = baseSeed + battleIndex;
                System.setProperty("RANDOMSEED", Long.toString(battleSeed));
                File pairDir = new File(outputRoot,
                        RobotSideCsvObserver.sanitize(robotA) + "__vs__" + RobotSideCsvObserver.sanitize(robotB));
                System.out.println("=== CSV BATTLE " + battleIndex + ": " + robotA + " vs " + robotB
                        + " seed=" + battleSeed + " ===");

                BattleCsvRunner.BattleCsvResult result = BattleCsvRunner.runBattle(robotA, robotB, rounds, pairDir);
                assertNotNull(result.battleEndEvent(), "Battle end score event should be produced");
                assertEquals(2, result.battleEndEvent().scores().length, "1v1 battle should have two score rows");

                assertPerspective(pairDir, 0, robotA);
                assertPerspective(pairDir, 1, robotB);
                battlesRun++;
                battleIndex++;
            }
        }

        if (onlyA != null || onlyB != null) {
            assertEquals(1, battlesRun, "Focused producer run should execute one ordered pair");
        } else {
            assertEquals(ROBOTS.length * ROBOTS.length, battlesRun, "Full producer run should execute 36 battles");
        }
    }

    private static void assertPerspective(File pairDir, int perspectiveIndex, String robotName) throws Exception {
        File dir = new File(pairDir, "Perspective" + perspectiveIndex + "-" + RobotSideCsvObserver.sanitize(robotName));
        assertTrue(dir.isDirectory(), "Perspective CSV directory should exist: " + dir);

        assertDataRows(new File(dir, "ticks.csv"), "ticks.csv should have data rows");
        assertDataRows(new File(dir, "scan.csv"), "scan.csv should have scan rows");
        assertHeader(new File(dir, "autopilot-waves.csv"));
        assertHeader(new File(dir, "dejavu-waves.csv"));
        assertTheirWaves(new File(dir, "their-waves.csv"));
        assertDataRows(new File(dir, "scores.csv"), "scores.csv should have a battle-end score row");
    }

    private static void assertHeader(File file) throws Exception {
        assertTrue(file.exists(), file.getName() + " should exist");
        List<String> lines = Files.readAllLines(file.toPath());
        assertTrue(!lines.isEmpty(), file.getName() + " should have a header row");
        assertNoDuplicateHeaders(file, lines.get(0));
    }

    private static void assertDataRows(File file, String message) throws Exception {
        assertHeader(file);
        List<String> lines = Files.readAllLines(file.toPath());
        assertTrue(lines.size() > 1, message);
    }

    private static void assertTheirWaves(File file) throws Exception {
        assertHeader(file);
        List<String> lines = Files.readAllLines(file.toPath());
        if (lines.size() <= 1) {
            return;
        }
        String[] headers = lines.get(0).split(",");
        int powerIndex = indexOf(headers, "their_fire_power");
        assertTrue(powerIndex >= 0, "their-waves.csv should include their_fire_power");
        for (int i = 1; i < lines.size(); i++) {
            String[] values = lines.get(i).split(",");
            assertTrue(powerIndex < values.length, "their_fire_power should have a value on row " + i);
            assertTrue(!values[powerIndex].isBlank() && !"NaN".equals(values[powerIndex]),
                    "their_fire_power should be populated on row " + i);
        }
    }

    private static void assertNoDuplicateHeaders(File file, String headerRow) {
        Set<String> seen = new HashSet<>();
        for (String header : headerRow.split(",")) {
            assertTrue(seen.add(header), file.getName() + " should not duplicate header: " + header);
        }
    }

    private static int indexOf(String[] values, String expected) {
        for (int i = 0; i < values.length; i++) {
            if (expected.equals(values[i])) {
                return i;
            }
        }
        return -1;
    }
}

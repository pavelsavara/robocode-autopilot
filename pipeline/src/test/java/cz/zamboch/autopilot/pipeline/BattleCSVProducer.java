package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.RoboMath;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // ===================== God-view <-> DeJaVu wave reconciliation =====================

    /**
     * Reconcile the core observer wave reconstruction (autopilot-waves.csv) against
     * the pure DeJaVu reconstruction (dejavu-waves.csv) for the hero perspective.
     * <p>
     * The two producers run different algorithms but observe the same fired bullets,
     * so every <em>post-fire</em> column that is physically reconstructable must
     * agree. Decision columns that one robot computes differently than another (the
     * model-predicted {@code our_fire_aim_gf}) are excluded — per the principle that
     * decisions can't be compared across robots but the fired wave can.
     * <p>
     * This is the third edge of the producer triangle (live&lt;-&gt;god-view is covered
     * by Layer 0); it makes silent DeJaVu gaps — e.g. a missing {@code our_aim_lag1_gf}
     * or a wrong-signed GF — visible.
     */
    @Test
    void godViewAndDejavuWavesReconcile() throws Exception {
        String robotsPath = System.getProperty("battle.stage");
        if (robotsPath == null) {
            robotsPath = new File("build/battle-stage").getAbsolutePath();
        }
        assumeTrue(new File(robotsPath).isDirectory(),
                "Skipping: battle-stage directory not found (run via ./gradlew :pipeline:battleCsvProducer)");
        System.setProperty("ROBOTPATH", robotsPath);
        System.setProperty("NOSECURITY", "true");
        System.setProperty("java.awt.headless", "true");

        int rounds = Integer.parseInt(System.getProperty("recon.rounds", "3"));
        long seed = Long.parseLong(System.getProperty("battle.seed",
                Long.toString(System.currentTimeMillis())));
        System.setProperty("RANDOMSEED", Long.toString(seed));

        String hero = "cz.zamboch.Autopilot";
        // A strong mover exercises non-trivial direction / lag-1 / break-GF sign.
        String opponent = "jk.mega.DrussGT";
        File pairDir = Files.createTempDirectory("wave-recon").toFile();

        BattleCsvRunner.BattleCsvResult result =
                BattleCsvRunner.runBattle(hero, opponent, rounds, pairDir);
        assertNotNull(result.battleEndEvent(), "battle should complete");

        File dir = new File(pairDir, "Perspective0-" + RobotSideCsvObserver.sanitize(hero));
        assertTrue(dir.isDirectory(), "hero perspective dir should exist: " + dir);

        List<Map<String, String>> obs = readRealWaves(new File(dir, "autopilot-waves.csv"));
        List<Map<String, String>> dej = readRealWaves(new File(dir, "dejavu-waves.csv"));
        assertFalse(obs.isEmpty(), "observer (autopilot-waves.csv) should have real waves");
        assertFalse(dej.isEmpty(), "dejavu (dejavu-waves.csv) should have real waves");

        Map<String, Map<String, String>> dejByKey = new HashMap<>();
        for (Map<String, String> w : dej) {
            dejByKey.put(waveKey(w), w);
        }

        // Reconstructable post-fire columns and their classification live in the
        // shared WaveReconciliation source of truth (also enforced fast against the
        // Feature enum by WaveReconciliationTest).
        List<String> exactCols = WaveReconciliation.EXACT_COLS;
        List<String> angleCols = WaveReconciliation.ANGLE_COLS;
        List<String> breakExactCols = WaveReconciliation.BREAK_EXACT_COLS;
        List<String> breakAngleCols = WaveReconciliation.BREAK_ANGLE_COLS;
        String gateCol = WaveReconciliation.GATE_COL;

        // --- Completeness guard: every OUR_WAVES column in the CSV must be classified. ---
        List<String> header = readHeader(new File(dir, "autopilot-waves.csv"));
        List<String> dejHeader = readHeader(new File(dir, "dejavu-waves.csv"));
        assertEquals(header, dejHeader, "autopilot-waves.csv and dejavu-waves.csv must share the OUR_WAVES schema");
        Set<String> classified = WaveReconciliation.classified();
        List<String> unclassified = new ArrayList<>();
        for (String col : header) {
            if (WaveReconciliation.isWaveColumn(col) && !classified.contains(col)) {
                unclassified.add(col);
            }
        }
        assertTrue(unclassified.isEmpty(),
                "New OUR_WAVES column(s) are not classified for cross-pipeline reconciliation: " + unclassified
                        + "\nEvery wave feature must be either compared (add it to WaveReconciliation and make BOTH "
                        + "GodViewWaveResolver and RobotSideCsvObserver populate it consistently) or listed "
                        + "in WaveReconciliation.EXCLUDED_WITH_REASON with a justification. No more silent gaps.");

        double tol = 1e-6;

        int matched = 0;
        int comparable = 0;
        int powerMismatch = 0;
        int breakCompared = 0;
        List<String> valueFailures = new ArrayList<>();
        List<String> nanFailures = new ArrayList<>();
        for (Map<String, String> o : obs) {
            Map<String, String> d = dejByKey.get(waveKey(o));
            if (d == null) {
                continue;
            }
            matched++;
            // Gate on fire-power agreement: DeJaVu infers power from the energy drop,
            // which is occasionally ambiguous (e.g. a same-tick wall hit). When the two
            // reconstructions disagree on power the bullet speed differs, so all
            // downstream geometry legitimately diverges — that wave is not a fair
            // post-fire comparison. Count these separately and bound them.
            double meaO = parseOrNaN(o.get(gateCol));
            double meaD = parseOrNaN(d.get(gateCol));
            if (Double.isNaN(meaO) || Double.isNaN(meaD) || Math.abs(meaO - meaD) > tol) {
                powerMismatch++;
                continue;
            }
            comparable++;
            for (String c : exactCols) {
                compareColumn(c, o, d, tol, false, valueFailures, nanFailures);
            }
            for (String c : angleCols) {
                compareColumn(c, o, d, tol, true, valueFailures, nanFailures);
            }
            if (isNumeric(o.get("our_break_gf")) && isNumeric(d.get("our_break_gf"))) {
                breakCompared++;
                for (String c : breakExactCols) {
                    compareColumn(c, o, d, tol, false, valueFailures, nanFailures);
                }
                for (String c : breakAngleCols) {
                    compareColumn(c, o, d, tol, true, valueFailures, nanFailures);
                }
            }
        }

        System.out.printf(
                "Wave reconciliation: matched=%d comparable=%d breakCompared=%d powerMismatch=%d valueFailures=%d nanFailures=%d%n",
                matched, comparable, breakCompared, powerMismatch, valueFailures.size(), nanFailures.size());
        assertTrue(comparable >= 10, "expected >= 10 power-matching real waves, got " + comparable);
        assertTrue(breakCompared >= 5, "expected >= 5 waves resolved on both sides, got " + breakCompared);
        assertTrue(valueFailures.isEmpty(), "reconstructable column value mismatches:\n"
                + String.join("\n", valueFailures.subList(0, Math.min(20, valueFailures.size()))));
        // Rare per-wave reconstruction noise (power-inference ambiguity, round-boundary
        // resolution timing) is tolerated; systematic gaps would fail many waves.
        assertTrue(powerMismatch <= Math.max(2, matched / 10),
                "too many fire-power reconstruction disagreements: " + powerMismatch + " of " + matched);
        assertTrue(nanFailures.size() <= 3, "too many NaN mismatches (" + nanFailures.size() + "):\n"
                + String.join("\n", nanFailures.subList(0, Math.min(20, nanFailures.size()))));
    }

    private static double parseOrNaN(String s) {
        return isNumeric(s) ? Double.parseDouble(s) : Double.NaN;
    }

    private static String waveKey(Map<String, String> w) {
        return w.get("round") + "|" + w.get("our_fire_tick");
    }

    private static boolean isNumeric(String s) {
        return s != null && !s.isBlank() && !"NaN".equals(s);
    }

    private static void compareColumn(String col, Map<String, String> o, Map<String, String> d,
            double tol, boolean angle, List<String> valueFailures, List<String> nanFailures) {
        String so = o.get(col);
        String sd = d.get(col);
        boolean no = isNumeric(so);
        boolean nd = isNumeric(sd);
        if (!no || !nd) {
            if (no != nd) {
                nanFailures.add(col + " NaN mismatch obs=" + so + " dej=" + sd + " key=" + waveKey(o));
            }
            return;
        }
        double a = Double.parseDouble(so);
        double b = Double.parseDouble(sd);
        double diff = angle ? Math.abs(RoboMath.normalRelativeAngle(a - b)) : Math.abs(a - b);
        if (diff > tol) {
            valueFailures.add(String.format("%s diff=%.3e obs=%s dej=%s key=%s", col, diff, so, sd, waveKey(o)));
        }
    }

    private static List<String> readHeader(File csv) throws Exception {
        List<String> lines = Files.readAllLines(csv.toPath());
        if (lines.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(lines.get(0).split(",", -1));
    }

    private static List<Map<String, String>> readRealWaves(File csv) throws Exception {
        List<String> lines = Files.readAllLines(csv.toPath());
        List<Map<String, String>> out = new ArrayList<>();
        if (lines.isEmpty()) {
            return out;
        }
        String[] headers = lines.get(0).split(",", -1);
        for (int i = 1; i < lines.size(); i++) {
            String[] v = lines.get(i).split(",", -1);
            Map<String, String> row = new HashMap<>();
            for (int j = 0; j < headers.length && j < v.length; j++) {
                row.put(headers[j], v[j]);
            }
            if ("1.0".equals(row.get("our_fire_is_real"))) {
                out.add(row);
            }
        }
        return out;
    }
}

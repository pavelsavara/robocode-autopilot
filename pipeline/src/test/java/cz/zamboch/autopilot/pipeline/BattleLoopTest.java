package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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

    /** Per-opponent layer/feature drift snapshots, accumulated across the parameterized run. */
    private static final List<OppReport> REPORTS = new ArrayList<>();

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

        int rounds = Integer.parseInt(System.getProperty("battle.rounds", "10"));
        // Deterministic RNG: Robocode reads -DRANDOMSEED at battle start and seeds
        // java.util.Random for the whole battle. Default to the current timestamp so
        // every run is reproducible; print it so a failing run can be replayed with
        // -Dbattle.seed=<printed value>. A per-battle seed (one per opponent) keeps
        // each parameterized case independently reproducible.
        long seed;
        String seedOverride = System.getProperty("battle.seed");
        if (seedOverride != null && !seedOverride.isEmpty()) {
            seed = Long.parseLong(seedOverride);
        } else {
            seed = System.currentTimeMillis();
        }
        System.setProperty("RANDOMSEED", Long.toString(seed));
        System.out.println(String.format(
                "=== RANDOM SEED: %d (vs %s) === replay with -Dbattle.seed=%d", seed, opponent, seed));

        // Emit all CSVs into a directory named "<seed>-<timestamp>" so two runs with
        // the same seed land in distinct, self-describing directories that can be
        // diffed. Base location is overridable via -Dbattle.csv.dir (default build/).
        String csvBase = System.getProperty("battle.csv.dir", new File("build").getAbsolutePath());
        File outputRoot = new File(csvBase, seed + "-" + System.currentTimeMillis());
        assertTrue(outputRoot.mkdirs() || outputRoot.isDirectory(),
                "Should be able to create CSV output directory: " + outputRoot);
        String outputDir = outputRoot.getAbsolutePath();
        System.out.println("=== CSV OUTPUT DIR: " + outputDir + " ===");

        // Always emit DebugPropertyCsvWriter (in-game.csv/observer.csv) and
        // TheirFireTraceWriter (their-fires.csv) into the per-seed dir unless the
        // caller already pinned them elsewhere via -Ddebug.csv.dir / -Dtheir.fires.dir.
        boolean setDebugCsv = System.getProperty("debug.csv.dir") == null;
        boolean setTheirFires = System.getProperty("their.fires.dir") == null;
        if (setDebugCsv) {
            System.setProperty("debug.csv.dir", outputDir);
        }
        if (setTheirFires) {
            System.setProperty("their.fires.dir", outputDir);
        }

        // Run the battle — may fail if --add-opens JVM args are missing
        BattleRunner.BattleResult result;
        try {
            result = BattleRunner.runBattle(opponent, rounds, outputDir);
        } catch (NullPointerException e) {
            assumeTrue(false, "Skipping: Robocode engine requires --add-opens JVM args "
                    + "(run via ./gradlew :pipeline:battleTest)");
            return;
        }

        GodViewQualityValidator validator = result.orchestrator().validator();
        assertNotNull(validator, "Validator should be attached");
        Layer0DebugFidelityValidator layer0 = result.orchestrator().layer0Validator();
        assertNotNull(layer0, "Layer 0 validator should be attached");

        // --- Verify CSV output ---
        File[] battleDirs = outputRoot.listFiles(f -> f.isDirectory() && f.getName().startsWith("battle-"));
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

        // --- Score + hit metrics (computed before any baseline assertion) ---
        double winRate = result.getWinRate();
        double scoreRatio = result.getScoreRatio();
        double hitRate = computeHitRate(waveLines);
        System.out.println(String.format("Win rate: %.1f%% (%d/%d)", winRate * 100,
                result.getOurFirsts(), result.getTotalRounds()));
        System.out.println(String.format("Score ratio: %.2f (%d/%d)", scoreRatio,
                result.getOurScore(), result.getOpponentScore()));
        System.out.println(String.format("Hit rate: %.1f%%", hitRate * 100));

        // Capture this opponent's full layer/feature drift snapshot for the markdown
        // report BEFORE any baseline assertion, so an opponent that trips a quality
        // gate (e.g. Fire's L3 rate, Aggressive's L0) is still included in the report.
        REPORTS.add(captureReport(opponent, seed, rounds, result, winRate, scoreRatio,
                hitRate, validator, layer0));

        assertScoreBaseline(opponent, winRate, scoreRatio, result.getTotalRounds());
        assertHitRateBaseline(opponent, hitRate);

        // --- PipelineValidator: spatial accuracy ---
        int spatialMismatches = validator.getSpatialMismatches();
        System.out.println(String.format("Spatial mismatches: %d", spatialMismatches));
        assertEquals(0, spatialMismatches,
                "Spatial features must match exactly between observer and god-view");

        // --- PipelineValidator: incoming-fire detection rate (autopilot only) ---
        double fireDetectionRate0 = validator.getTheirFireDetectionRate();
        System.out.println(String.format("Incoming-fire detection rate: %.1f%%", fireDetectionRate0 * 100));
        assertFireDetectionBaseline(opponent, fireDetectionRate0);

        // --- PipelineValidator: GF mean absolute error (quality metric) ---
        double gfError = validator.getGfMeanAbsoluteError(0);
        System.out.println(String.format("GF mean absolute error: %.4f", gfError));
        // GF comparison requires matched wave resolution between observer and live
        // robot.
        // Observer fires independently → different wave set → comparison is unreliable.
        // Report as quality metric; strict assertion deferred until wave matching is
        // aligned.

        // --- Layer 0: IDebugProperty fidelity (ALL features, incl. breaks) ---
        int debugMismatches = layer0.getMismatches();
        System.out.println(String.format("Layer 0 debug property mismatches: %d (checks=%d)",
                debugMismatches, layer0.getChecks()));

        // --- PipelineValidator: energy accounting (quality metric) ---
        int energyDisc0 = validator.getEnergyDiscrepancies(0);
        int energyChecks0 = validator.getEnergyChecks(0);
        double energyAccuracy = energyChecks0 > 0 ? 1.0 - (double) energyDisc0 / energyChecks0 : 1.0;
        System.out.println(String.format("Energy accounting: %d/%d checks passed (%.1f%% accuracy)",
                energyChecks0 - energyDisc0, energyChecks0, energyAccuracy * 100));
        // Energy accounting has timing issues with Robocode's bullet state transitions.
        // Report as quality metric; strict assertion deferred until state timing is
        // resolved.

        // --- Non-vacuous check ---
        // A non-firing battle (e.g. SittingDuck, or an opponent killed before its gun
        // cools) legitimately yields zero god-view incoming fires; waive that single
        // Layer 3 requirement when the detection rate is undefined (no fires at all).
        boolean anyFiresDetected = !Double.isNaN(fireDetectionRate0);
        validator.assertNonVacuous(anyFiresDetected);
        layer0.assertNonVacuous();

        // Print full summary (before assertions so we always see breakdown)
        validator.printSummary();
        layer0.printSummary();
        System.out.println("Output: " + battleDir.getAbsolutePath());
        System.out.println("  ticks.csv (Autopilot):  " + new File(autopilotDir, "ticks.csv").getAbsolutePath());
        System.out.println("  their-waves.csv (Autopilot): "
                + new File(autopilotDir, "their-waves.csv").getAbsolutePath());
        System.out.println("  our-waves.csv (Autopilot):   "
                + new File(autopilotDir, "our-waves.csv").getAbsolutePath());
        System.out.println("  scores.csv (Autopilot):      " + new File(autopilotDir, "scores.csv").getAbsolutePath());
        File opponentDir = new File(battleDir, "Opponent");
        System.out.println("  ticks.csv (Opponent):   " + new File(opponentDir, "ticks.csv").getAbsolutePath());
        System.out.println("  their-waves.csv (Opponent):  "
                + new File(opponentDir, "their-waves.csv").getAbsolutePath());
        System.out.println("  in-game.csv:  " + new File(outputDir, "in-game.csv").getAbsolutePath());
        System.out.println("  observer.csv: " + new File(outputDir, "observer.csv").getAbsolutePath());
        System.out.println("  their-fires.csv: " + new File(outputDir, "their-fires.csv").getAbsolutePath());
        // Restore system properties to leave the JVM clean for sibling tests
        if (setDebugCsv) {
            System.clearProperty("debug.csv.dir");
        }
        if (setTheirFires) {
            System.clearProperty("their.fires.dir");
        }

        // --- Assert debug properties match (ALL features, every tick, every round) ---
        assertEquals(0, debugMismatches,
                "Observer must be a faithful deterministic shadow: every feature must match "
                        + "the live robot's debug properties every tick");
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

    // --- Incoming-fire detection-rate baseline per opponent ---
    // The rate is robotSideFires / godViewFires. For opponents that fire little or
    // nothing in this seed, the only god-view "fires" recorded are one-per-round
    // death-tick artifacts (no real bullet), so the rate degenerates to 0 — those
    // are skipped rather than asserted.
    private void assertFireDetectionBaseline(String opponent, double rate) {
        // No god-view fires occurred this battle (opponent never got a shot off, e.g.
        // killed before its gun cooled). There is nothing to detect, so no baseline.
        if (Double.isNaN(rate)) {
            return;
        }
        switch (opponent) {
            case "test.SittingDuck":
                // Never fires — every god-view "fire" is a death-tick artifact;
                // there is nothing real to detect, so no baseline.
                break;
            case "test.Aggressive":
                // Ram-dominated; the few real shots are hard to attribute against
                // its ram/wall energy drops, so a lower bar applies.
                assertTrue(rate >= 0.8,
                        "vs Aggressive: incoming-fire detection rate should be >= 80%, was " + rate);
                break;
            default:
                assertTrue(rate >= 0.9,
                        "Incoming-fire detection rate should be >= 90%, was " + rate);
                break;
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

    // ======================================================================
    // Drift report (./BattleLoopTest.md) — by layer and by feature.
    // ======================================================================

    /** A single (name, checks, mismatches) per-feature drift entry. */
    private record FeatureDrift(String name, int checks, int mismatches) {
    }

    /** A single Layer-2 per-channel entry: event counts, drift incidents and totals. */
    private record ChannelDrift(String label, long gvEvents, long obsEvents,
            long driftIncidents, double gvTotal, double obsTotal, double absDrift) {
    }

    /** Immutable per-opponent snapshot of all layer/feature drift metrics. */
    private static final class OppReport {
        String opponent;
        long seed;
        int rounds;
        int ourFirsts;
        int totalRounds;
        double winRate;
        long ourScore;
        long oppScore;
        double scoreRatio;
        double hitRate;

        int l0Checks;
        int l0Mismatches;
        int l0WaveOther; // mismatches not attributed to an enum feature (wave-column drift)
        final List<FeatureDrift> l0Features = new ArrayList<>();

        int l1Checks;
        int l1Mismatches;
        final List<FeatureDrift> l1Features = new ArrayList<>();

        long l2Ticks;
        long l2MismatchTicks;
        double l2TotalAbsDrift;
        long l2ScannedTicks;
        double l2MissedScanPct;
        final List<ChannelDrift> l2Channels = new ArrayList<>();

        int l3GodView;
        int l3RobotSide;
        double l3Rate;
        double l3PosMAE;
        double l3PowMAE;
        double l3Latency;
        double l3AngleMAE;

        // Layer 4 — perspective 0 (autopilot) only.
        int l4Comparisons;
        double l4MAE;
        double l4MaxErr;
        double l4MatchRate;
        double l4BreakTickMAE;
    }

    private static OppReport captureReport(String opponent, long seed, int rounds,
            BattleRunner.BattleResult result, double winRate, double scoreRatio, double hitRate,
            GodViewQualityValidator validator, Layer0DebugFidelityValidator layer0) {
        OppReport r = new OppReport();
        r.opponent = opponent;
        r.seed = seed;
        r.rounds = rounds;
        r.ourFirsts = result.getOurFirsts();
        r.totalRounds = result.getTotalRounds();
        r.winRate = winRate;
        r.ourScore = result.getOurScore();
        r.oppScore = result.getOpponentScore();
        r.scoreRatio = scoreRatio;
        r.hitRate = hitRate;

        // Layer 0 — IDebugProperty fidelity, per feature.
        r.l0Checks = layer0.getChecks();
        r.l0Mismatches = layer0.getMismatches();
        int l0FeatureMismatchSum = 0;
        for (Feature f : Feature.values()) {
            int checks = layer0.getChecks(f);
            if (checks == 0) {
                continue;
            }
            int mism = layer0.getMismatches(f);
            l0FeatureMismatchSum += mism;
            if (mism > 0) {
                r.l0Features.add(new FeatureDrift(f.name(), checks, mism));
            }
        }
        // Wave-column drift is folded into the L0 total but not enumerable per enum.
        r.l0WaveOther = Math.max(0, r.l0Mismatches - l0FeatureMismatchSum);

        // Layer 1 — spatial fidelity, per feature.
        r.l1Checks = validator.getSpatialChecks();
        r.l1Mismatches = validator.getSpatialMismatches();
        for (Feature f : Feature.values()) {
            int checks = validator.getSpatialChecks(f);
            if (checks == 0) {
                continue;
            }
            int mism = validator.getSpatialMismatches(f);
            if (mism > 0) {
                r.l1Features.add(new FeatureDrift(f.name(), checks, mism));
            }
        }

        // Layer 2 — damage-observation drift, per channel.
        GodViewQualityValidator.DamageObservationTracker d2 = validator.getDamageObsTracking();
        r.l2Ticks = d2.ticks;
        r.l2MismatchTicks = d2.mismatchTicks;
        r.l2TotalAbsDrift = d2.totalAbsDrift();
        r.l2ScannedTicks = d2.scannedTicks;
        r.l2MissedScanPct = d2.missedScanFraction();
        for (int i = 0; i < GodViewQualityValidator.DamageObservationTracker.N; i++) {
            // Include every channel that saw any activity so the drift incidents
            // are visible against their denominators (e.g. wall-hit / bullet events).
            if (d2.gvEventCount[i] > 0 || d2.obsEventCount[i] > 0) {
                r.l2Channels.add(new ChannelDrift(
                        GodViewQualityValidator.DamageObservationTracker.LABELS[i],
                        d2.gvEventCount[i], d2.obsEventCount[i], d2.driftTickCount[i],
                        d2.gvTotal[i], d2.obsTotal[i], d2.absDriftTotal[i]));
            }
        }

        // Layer 3 — incoming-fire detection.
        r.l3GodView = validator.getTheirGodViewFires();
        r.l3RobotSide = validator.getTheirRobotSideFires();
        r.l3Rate = validator.getTheirFireDetectionRate();
        r.l3PosMAE = validator.getTheirFirePositionMAE();
        r.l3PowMAE = validator.getTheirFirePowerMAE();
        r.l3Latency = validator.getTheirFireDetectionLatency();
        r.l3AngleMAE = validator.getTheirFireAngleMAE();

        // Layer 4 — GF precision (autopilot perspective 0).
        r.l4Comparisons = validator.getGfComparisonCount(0);
        r.l4MAE = validator.getGfMeanAbsoluteError(0);
        r.l4MaxErr = validator.getGfMaxError(0);
        r.l4MatchRate = validator.getWaveMatchRate(0);
        r.l4BreakTickMAE = validator.getBreakTickMAE(0);

        return r;
    }

    @AfterAll
    static void writeDriftReport() throws IOException {
        if (REPORTS.isEmpty()) {
            return; // nothing ran (e.g. all skipped) — don't clobber an existing report
        }

        StringBuilder md = new StringBuilder();
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        md.append("# BattleLoopTest Drift Report\n\n");
        md.append("Generated: ").append(now).append("  \n");
        md.append("Opponents: ").append(REPORTS.size()).append("\n\n");
        md.append("Drift is reported per validation layer and, where available, per feature/channel. ");
        md.append("`N/A` means the metric was undefined (no samples).\n\n");

        // --- Overview ---
        md.append("## Overview\n\n");
        md.append("| Opponent | Seed | Rounds | Win | Score ratio | Hit rate |\n");
        md.append("|---|---:|---:|---:|---:|---:|\n");
        for (OppReport r : REPORTS) {
            md.append(String.format("| %s | %d | %d | %.0f%% (%d/%d) | %.2f (%d/%d) | %.1f%% |%n",
                    r.opponent, r.seed, r.rounds, r.winRate * 100, r.ourFirsts, r.totalRounds,
                    r.scoreRatio, r.ourScore, r.oppScore, r.hitRate * 100));
        }
        md.append('\n');

        // --- Layer 0 ---
        md.append("## Layer 0 — IDebugProperty Fidelity\n\n");
        md.append("Observer-vs-live debug-property match, every feature, every tick.\n\n");
        md.append("| Opponent | Checks | Mismatches | Match rate |\n");
        md.append("|---|---:|---:|---:|\n");
        for (OppReport r : REPORTS) {
            md.append(String.format("| %s | %d | %d | %s |%n",
                    r.opponent, r.l0Checks, r.l0Mismatches, matchRate(r.l0Checks, r.l0Mismatches)));
        }
        md.append('\n');
        md.append("### Layer 0 — drift by feature\n\n");
        appendFeatureTable(md, true);

        // --- Layer 1 ---
        md.append("## Layer 1 — Spatial Fidelity\n\n");
        md.append("| Opponent | Checks | Mismatches | Match rate |\n");
        md.append("|---|---:|---:|---:|\n");
        for (OppReport r : REPORTS) {
            md.append(String.format("| %s | %d | %d | %s |%n",
                    r.opponent, r.l1Checks, r.l1Mismatches, matchRate(r.l1Checks, r.l1Mismatches)));
        }
        md.append('\n');
        md.append("### Layer 1 — drift by feature\n\n");
        appendFeatureTable(md, false);

        // --- Layer 2 ---
        md.append("## Layer 2 — Damage Observation Drift\n\n");
        md.append("Autopilot's observed opponent-damage vs god-view, accumulated over the battle. "
                + "`Missed scans %` is the fraction of ticks with no fresh radar scan of the "
                + "opponent — the exposure window where damage channels can drift.\n\n");
        md.append("| Opponent | Ticks | Scanned ticks | Missed scans % | Mismatch ticks | Total abs drift |\n");
        md.append("|---|---:|---:|---:|---:|---:|\n");
        for (OppReport r : REPORTS) {
            md.append(String.format("| %s | %d | %d | %s | %d | %.4f |%n",
                    r.opponent, r.l2Ticks, r.l2ScannedTicks, fmtPct(r.l2MissedScanPct),
                    r.l2MismatchTicks, r.l2TotalAbsDrift));
        }
        md.append('\n');
        md.append("### Layer 2 — channels (events, drift incidents, totals)\n\n");
        md.append("`Drift incidents` = ticks where the channel's observation disagreed with "
                + "god-view. `GV events` / `Obs events` are the ticks where that channel was "
                + "active (bullet hits, ram ticks, or wall hits) — the denominators the incidents "
                + "are measured against.\n\n");
        boolean anyL2 = REPORTS.stream().anyMatch(r -> !r.l2Channels.isEmpty());
        if (!anyL2) {
            md.append("No damage-channel activity recorded for any opponent.\n\n");
        } else {
            md.append("| Opponent | Channel | GV events | Obs events | Drift incidents | "
                    + "GV total | Obs total | Abs drift |\n");
            md.append("|---|---|---:|---:|---:|---:|---:|---:|\n");
            for (OppReport r : REPORTS) {
                for (ChannelDrift c : r.l2Channels) {
                    md.append(String.format("| %s | %s | %d | %d | %d | %.4f | %.4f | %.4f |%n",
                            r.opponent, c.label(), c.gvEvents(), c.obsEvents(), c.driftIncidents(),
                            c.gvTotal(), c.obsTotal(), c.absDrift()));
                }
            }
            md.append('\n');
        }

        // --- Layer 3 ---
        md.append("## Layer 3 — Incoming-Fire Detection\n\n");
        md.append("| Opponent | GV fires | Detected | Rate | Pos MAE | Power MAE | Latency | Angle MAE (rad) |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (OppReport r : REPORTS) {
            md.append(String.format("| %s | %d | %d | %s | %s | %s | %s | %s |%n",
                    r.opponent, r.l3GodView, r.l3RobotSide, fmtPct(r.l3Rate),
                    fmt(r.l3PosMAE, "%.4f"), fmt(r.l3PowMAE, "%.4f"),
                    fmt(r.l3Latency, "%.2f"), fmt(r.l3AngleMAE, "%.4f")));
        }
        md.append('\n');

        // --- Layer 4 ---
        md.append("## Layer 4 — GF Precision (autopilot)\n\n");
        md.append("| Opponent | Comparisons | MAE | Max err | Wave match | Break-tick MAE |\n");
        md.append("|---|---:|---:|---:|---:|---:|\n");
        for (OppReport r : REPORTS) {
            md.append(String.format("| %s | %d | %s | %s | %s | %s |%n",
                    r.opponent, r.l4Comparisons, fmt(r.l4MAE, "%.6f"), fmt(r.l4MaxErr, "%.6f"),
                    fmtPct(r.l4MatchRate), fmt(r.l4BreakTickMAE, "%.2f")));
        }
        md.append('\n');

        File out = new File(repoRoot(), "BattleLoopTest.md");
        Files.write(out.toPath(), md.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("=== Drift report written: " + out.getAbsolutePath() + " ===");
        REPORTS.clear();
    }

    /** Append a per-opponent / per-feature drift table for Layer 0 (l0) or Layer 1 (spatial). */
    private static void appendFeatureTable(StringBuilder md, boolean layer0) {
        boolean any = REPORTS.stream()
                .anyMatch(r -> !(layer0 ? r.l0Features : r.l1Features).isEmpty() || (layer0 && r.l0WaveOther > 0));
        if (!any) {
            md.append("All features matched exactly across all opponents.\n\n");
            return;
        }
        md.append("| Opponent | Feature | Checks | Mismatches |\n");
        md.append("|---|---|---:|---:|\n");
        for (OppReport r : REPORTS) {
            for (FeatureDrift f : (layer0 ? r.l0Features : r.l1Features)) {
                md.append(String.format("| %s | %s | %d | %d |%n",
                        r.opponent, f.name(), f.checks(), f.mismatches()));
            }
            if (layer0 && r.l0WaveOther > 0) {
                md.append(String.format("| %s | (wave-column drift) | — | %d |%n",
                        r.opponent, r.l0WaveOther));
            }
        }
        md.append('\n');
    }

    private static String matchRate(int checks, int mismatches) {
        if (checks == 0) {
            return "N/A";
        }
        return String.format("%.3f%%", 100.0 * (checks - mismatches) / checks);
    }

    private static String fmtPct(double rate) {
        return Double.isNaN(rate) ? "N/A" : String.format("%.1f%%", rate * 100);
    }

    private static String fmt(double value, String format) {
        return Double.isNaN(value) ? "N/A" : String.format(format, value);
    }

    /** Walk up from the working directory to the repo root (settings.gradle.kts marker). */
    private static File repoRoot() {
        File dir = new File("").getAbsoluteFile();
        while (dir != null && !new File(dir, "settings.gradle.kts").exists()) {
            dir = dir.getParentFile();
        }
        return dir != null ? dir : new File("").getAbsoluteFile();
    }
}

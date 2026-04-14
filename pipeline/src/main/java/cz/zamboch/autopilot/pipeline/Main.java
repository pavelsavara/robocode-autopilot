package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Transformer;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.pipeline.features.CombatStateOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.EnergyOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.MovementOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.MovementSegmentationOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.OpponentPredictionOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.SpatialOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.StateNormalizationOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.TargetingGeometryOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.TimingOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.WaveTrackingOfflineFeatures;
import robocode.BattleRules;
import robocode.control.snapshot.ITurnSnapshot;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * CLI entry point for the Stage 2 pipeline.
 * Processes .br recordings into CSV feature files.
 */
public final class Main {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: pipeline --input <dir> --output <dir>");
            System.exit(1);
        }

        Path inputDir = null;
        Path outputDir = null;

        for (int i = 0; i < args.length - 1; i++) {
            if ("--input".equals(args[i])) {
                inputDir = Paths.get(args[i + 1]);
            } else if ("--output".equals(args[i])) {
                outputDir = Paths.get(args[i + 1]);
            }
        }

        if (inputDir == null || outputDir == null) {
            System.err.println("Both --input and --output are required");
            System.exit(1);
        }

        System.out.println("Robocode Autopilot Pipeline");
        System.out.println("Input:  " + inputDir);
        System.out.println("Output: " + outputDir);

        File[] brFiles = inputDir.toFile().listFiles(new java.io.FilenameFilter() {
            public boolean accept(File d, String name) {
                return name.endsWith(".br");
            }
        });

        if (brFiles == null || brFiles.length == 0) {
            System.err.println("No .br files found in " + inputDir);
            System.exit(1);
        }

        int processed = 0;
        for (File brFile : brFiles) {
            try {
                processBattle(brFile.toPath(), outputDir);
                processed++;
                System.out.println("  Processed: " + brFile.getName());
            } catch (Exception e) {
                System.err.println("  FAILED: " + brFile.getName() + " — " + e.getMessage());
            }
        }

        System.out.println("Done. Processed " + processed + "/" + brFiles.length + " recordings.");
    }

    static void processBattle(Path brFile, final Path outputDir) throws IOException, ClassNotFoundException {
        final String battleId = brFile.getFileName().toString().replace(".br", "");
        final Loader loader = new Loader(brFile);

        // First pass: get robot names
        final String[] robotNames = Player.getRobotNames(loader);
        if (robotNames == null || robotNames.length < 2) {
            throw new IOException("Could not read robot names from " + brFile);
        }

        // Extract battle rules (populated after first pass)
        final BattleRules rules = loader.getRecordInfo().battleRules;
        final int bfW = rules.getBattlefieldWidth();
        final int bfH = rules.getBattlefieldHeight();
        final double cooling = rules.getGunCoolingRate();
        final int numRounds = loader.getRecordInfo().roundsCount;

        // Create whiteboards, player, transformers, CSV writers
        final Whiteboard wbA = new Whiteboard();
        final Whiteboard wbB = new Whiteboard();
        wbA.setBattleId(battleId);
        wbB.setBattleId(battleId);

        final Player player = new Player(wbA, wbB);
        final Transformer tA = createTransformer();
        final Transformer tB = createTransformer();

        final CsvWriter csvA = new CsvWriter(outputDir, battleId, robotNames[0], tA);
        final CsvWriter csvB = new CsvWriter(outputDir, battleId, robotNames[1], tB);
        csvA.writeHeaders();
        csvB.writeHeaders();

        // Second pass: process all turns
        final int[] prevRoundHolder = {-1};
        final long[] lastTickHolder = {-1};
        try {
            loader.forEachTurn(new Loader.TurnConsumer() {
                public void accept(int roundIndex, ITurnSnapshot turn) {
                    // Write score for previous round BEFORE player resets counters
                    if (roundIndex != prevRoundHolder[0] && prevRoundHolder[0] >= 0) {
                        csvA.writeScoreRow(wbA, lastTickHolder[0] + 1);
                        csvB.writeScoreRow(wbB, lastTickHolder[0] + 1);
                    }

                    player.processTurn(roundIndex, turn, bfW, bfH, cooling, numRounds);
                    prevRoundHolder[0] = roundIndex;
                    lastTickHolder[0] = turn.getTurn();

                    tA.process(wbA);
                    tB.process(wbB);

                    csvA.writeTickRow(wbA);
                    csvB.writeTickRow(wbB);

                    // Write wave row when opponent fire detected
                    if (wbA.hasFeature(Feature.OPPONENT_FIRED)
                            && wbA.getFeature(Feature.OPPONENT_FIRED) == 1.0) {
                        csvA.writeWaveRow(wbA);
                    }
                    if (wbB.hasFeature(Feature.OPPONENT_FIRED)
                            && wbB.getFeature(Feature.OPPONENT_FIRED) == 1.0) {
                        csvB.writeWaveRow(wbB);
                    }

                    wbA.advanceTick();
                    wbB.advanceTick();
                }
            });

            // Write score row for the final round
            if (prevRoundHolder[0] >= 0) {
                csvA.writeScoreRow(wbA, lastTickHolder[0] + 1);
                csvB.writeScoreRow(wbB, lastTickHolder[0] + 1);
            }
        } finally {
            csvA.close();
            csvB.close();
        }
    }

    static Transformer createTransformer() {
        Transformer t = new Transformer();
        t.register(new SpatialOfflineFeatures());
        t.register(new MovementOfflineFeatures());
        t.register(new EnergyOfflineFeatures());
        t.register(new TimingOfflineFeatures());
        t.register(new MovementSegmentationOfflineFeatures());
        t.register(new TargetingGeometryOfflineFeatures());
        t.register(new StateNormalizationOfflineFeatures());
        t.register(new OpponentPredictionOfflineFeatures());
        t.register(new WaveTrackingOfflineFeatures());
        t.register(new CombatStateOfflineFeatures());
        t.resolveDependencies();
        return t;
    }
}

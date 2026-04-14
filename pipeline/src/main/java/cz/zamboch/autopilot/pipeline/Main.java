package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Transformer;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.pipeline.features.EnergyOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.MovementOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.MovementSegmentationOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.OpponentPredictionOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.SpatialOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.StateNormalizationOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.TargetingGeometryOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.TimingOfflineFeatures;
import robocode.BattleRules;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;

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
        final String[][] robotNames = {null};
        loader.forEachTurn(new Loader.TurnConsumer() {
            public void accept(int roundIndex, ITurnSnapshot turn) {
                if (robotNames[0] == null) {
                    IRobotSnapshot[] robots = turn.getRobots();
                    robotNames[0] = new String[robots.length];
                    for (int i = 0; i < robots.length; i++) {
                        robotNames[0][i] = robots[i].getShortName();
                    }
                }
            }
        });

        if (robotNames[0] == null || robotNames[0].length < 2) {
            throw new IOException("Could not read robot names from " + brFile);
        }

        // Create transformers + writers for both perspectives
        final Transformer tA = createTransformer();
        final Transformer tB = createTransformer();
        final Whiteboard wbA = new Whiteboard();
        final Whiteboard wbB = new Whiteboard();
        wbA.setBattleId(battleId);
        wbB.setBattleId(battleId);

        final CsvWriter csvA = new CsvWriter(outputDir, battleId, robotNames[0][0], tA);
        final CsvWriter csvB = new CsvWriter(outputDir, battleId, robotNames[0][1], tB);
        csvA.writeHeaders();
        csvB.writeHeaders();

        // Second pass: process all turns
        try {
            loader.forEachTurn(new Loader.TurnConsumer() {
                private int lastRound = -1;
                private double prevRadarA = Double.NaN;
                private double prevRadarB = Double.NaN;

                public void accept(int roundIndex, ITurnSnapshot turn) {
                    IRobotSnapshot[] robots = turn.getRobots();
                    if (robots.length < 2) return;

                    if (roundIndex != lastRound) {
                        BattleRules rules = loader.getRecordInfo().battleRules;
                        int bfW = rules.getBattlefieldWidth();
                        int bfH = rules.getBattlefieldHeight();
                        double cooling = rules.getGunCoolingRate();
                        int numRounds = loader.getRecordInfo().roundsCount;
                        wbA.onRoundStart(roundIndex, bfW, bfH, cooling, numRounds);
                        wbB.onRoundStart(roundIndex, bfW, bfH, cooling, numRounds);
                        prevRadarA = Double.NaN;
                        prevRadarB = Double.NaN;
                        lastRound = roundIndex;
                    }

                    IRobotSnapshot rA = robots[0];
                    IRobotSnapshot rB = robots[1];

                    wbA.setTick(turn.getTurn());
                    wbB.setTick(turn.getTurn());

                    wbA.setOurState(rA.getX(), rA.getY(), rA.getBodyHeading(),
                            rA.getGunHeading(), rA.getRadarHeading(),
                            rA.getVelocity(), rA.getEnergy(), rA.getGunHeat());
                    wbB.setOurState(rB.getX(), rB.getY(), rB.getBodyHeading(),
                            rB.getGunHeading(), rB.getRadarHeading(),
                            rB.getVelocity(), rB.getEnergy(), rB.getGunHeat());

                    boolean firstTick = (turn.getTurn() == 0);

                    if (rB.getState() != RobotState.DEAD) {
                        if (firstTick || Player.radarSweepIntersects(
                                rA.getX(), rA.getY(), prevRadarA,
                                rA.getRadarHeading(), rB.getX(), rB.getY())) {
                            wbA.setOpponentScan(rB.getX(), rB.getY(),
                                    rB.getBodyHeading(), rB.getVelocity(), rB.getEnergy());
                        }
                    }

                    if (rA.getState() != RobotState.DEAD) {
                        if (firstTick || Player.radarSweepIntersects(
                                rB.getX(), rB.getY(), prevRadarB,
                                rB.getRadarHeading(), rA.getX(), rA.getY())) {
                            wbB.setOpponentScan(rA.getX(), rA.getY(),
                                    rA.getBodyHeading(), rA.getVelocity(), rA.getEnergy());
                        }
                    }

                    prevRadarA = rA.getRadarHeading();
                    prevRadarB = rB.getRadarHeading();

                    tA.process(wbA);
                    tB.process(wbB);

                    csvA.writeTickRow(wbA);
                    csvB.writeTickRow(wbB);

                    wbA.advanceTick();
                    wbB.advanceTick();
                }
            });
        } finally {
            csvA.close();
            csvB.close();
        }
    }

    private static Transformer createTransformer() {
        Transformer t = new Transformer();
        t.register(new SpatialOfflineFeatures());
        t.register(new MovementOfflineFeatures());
        t.register(new EnergyOfflineFeatures());
        t.register(new TimingOfflineFeatures());
        t.register(new MovementSegmentationOfflineFeatures());
        t.register(new TargetingGeometryOfflineFeatures());
        t.register(new StateNormalizationOfflineFeatures());
        t.register(new OpponentPredictionOfflineFeatures());
        t.resolveDependencies();
        return t;
    }
}

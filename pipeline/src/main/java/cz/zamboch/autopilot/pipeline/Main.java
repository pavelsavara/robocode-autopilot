package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.MovementFeatures;
import cz.zamboch.autopilot.core.features.SpatialFeatures;
import cz.zamboch.autopilot.core.features.TimingFeatures;
import net.sf.robocode.recording.BattleRecordInfo;
import robocode.BattleRules;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Pipeline CLI: replays .br recordings and produces CSV files.
 * Usage: pipeline --input <recordings-dir> --output <csv-dir>
 *
 * Each .br recording is replayed from both perspectives, producing:
 * <output>/<battleId>/<robotNameA>/ticks.csv, waves.csv, scores.csv
 * <output>/<battleId>/<robotNameB>/ticks.csv, waves.csv, scores.csv
 */
public final class Main {
    public static void main(String[] args) {
        if (args.length < 4) {
            System.out.println("Usage: pipeline --input <recordings-dir> --output <csv-dir>");
            System.exit(1);
        }

        String inputDir = null;
        String outputDir = null;
        boolean force = false;
        for (int i = 0; i < args.length; i++) {
            if ("--input".equals(args[i]) && i + 1 < args.length) {
                inputDir = args[++i];
            } else if ("--output".equals(args[i]) && i + 1 < args.length) {
                outputDir = args[++i];
            } else if ("--force".equals(args[i])) {
                force = true;
            }
        }

        if (inputDir == null || outputDir == null) {
            System.err.println("ERROR: --input and --output are required");
            System.exit(1);
        }

        // Find all .br files
        List<Path> brFiles = new ArrayList<Path>();
        try {
            findBrFiles(Paths.get(inputDir), brFiles);
        } catch (IOException e) {
            System.err.println("ERROR: Cannot read input directory: " + e.getMessage());
            System.exit(1);
        }

        System.out.println("Found " + brFiles.size() + " recording(s) in " + inputDir);
        Path outBase = Paths.get(outputDir);

        int processed = 0;
        int skipped = 0;
        for (Path brFile : brFiles) {
            String battleId = deriveBattleId(brFile);
            File battleOutDir = outBase.resolve(battleId).toFile();

            // Skip if output already exists (incremental mode)
            if (!force && battleOutDir.exists()) {
                skipped++;
                continue;
            }

            try {
                processBattle(brFile, battleId, outBase);
                processed++;
            } catch (Exception e) {
                System.err.println("ERROR processing " + brFile + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println("Processed: " + processed + ", Skipped: " + skipped);
    }

    private static void processBattle(Path brFile, String battleId, Path outBase)
            throws IOException, ClassNotFoundException {
        // First pass: get robot names
        Loader nameLoader = new Loader(brFile);
        String[] names = Player.getRobotNames(nameLoader);
        nameLoader.close();

        if (names == null || names.length < 2) {
            System.err.println("WARN: Cannot read robot names from " + brFile);
            return;
        }

        String nameA = names[0];
        String nameB = names[1];
        System.out.println("  " + battleId + ": " + nameA + " vs " + nameB);

        // Set up whiteboards, CSV writers
        Whiteboard wbA = createWhiteboard();
        Whiteboard wbB = createWhiteboard();

        File dirA = outBase.resolve(battleId).resolve(nameA).toFile();
        File dirB = outBase.resolve(battleId).resolve(nameB).toFile();
        CsvWriter csvA = new CsvWriter(dirA);
        CsvWriter csvB = new CsvWriter(dirB);
        csvA.writeHeaders(battleId);
        csvB.writeHeaders(battleId);

        Perspective[] perspectives = Perspective.createPair(wbA, wbB);
        Player player = new Player(perspectives);

        // Second pass: replay all turns
        final Loader loader = new Loader(brFile);
        final int[] state = { -1 }; // currentRound
        final IRobotSnapshot[][] lastRobots = { null };
        // We need battlefield size. It's available after forEachTurn starts (reads
        // header).
        // Use a wrapper to get it from the first callback.
        final double[][] bfSize = { null };

        loader.forEachTurn(new Loader.TurnConsumer() {
            @Override
            public void accept(int roundIndex, ITurnSnapshot turn) {
                try {
                    if (bfSize[0] == null) {
                        BattleRecordInfo info = loader.getRecordInfo();
                        BattleRules rules = info.battleRules;
                        bfSize[0] = new double[] { rules.getBattlefieldWidth(), rules.getBattlefieldHeight() };
                    }
                    double bfWidth = bfSize[0][0];
                    double bfHeight = bfSize[0][1];

                    boolean newRound = player.processTurn(roundIndex, turn, bfWidth, bfHeight);

                    // On new round (except first): finalize previous round
                    if (newRound && state[0] >= 0 && lastRobots[0] != null) {
                        perspectives[0].setLastRobot(lastRobots[0][0]);
                        perspectives[1].setLastRobot(lastRobots[0][1]);
                        player.finalizeRound(perspectives);
                        csvA.writeScoreRow(wbA, battleId, state[0]);
                        csvB.writeScoreRow(wbB, battleId, state[0]);
                    }
                    state[0] = roundIndex;

                    // Compute derived features
                    wbA.process();
                    wbB.process();

                    // Write tick rows
                    csvA.writeTickRow(wbA, battleId, roundIndex);
                    csvB.writeTickRow(wbB, battleId, roundIndex);

                    // Write wave rows if opponent fired
                    if (!Double.isNaN(wbA.getFeature(Feature.THEIR_FIRE_POWER))) {
                        csvA.writeTheirWaveRow(wbA, battleId, roundIndex);
                    }
                    if (!Double.isNaN(wbB.getFeature(Feature.THEIR_FIRE_POWER))) {
                        csvB.writeTheirWaveRow(wbB, battleId, roundIndex);
                    }

                    // Track last robots for round finalization
                    lastRobots[0] = turn.getRobots();

                    // Reset per-tick fire detection
                    wbA.setFeature(Feature.THEIR_FIRE_POWER, Double.NaN);
                    wbB.setFeature(Feature.THEIR_FIRE_POWER, Double.NaN);
                } catch (IOException e) {
                    throw new RuntimeException("CSV write error", e);
                }
            }
        });

        // Finalize last round
        if (state[0] >= 0 && lastRobots[0] != null) {
            perspectives[0].setLastRobot(lastRobots[0][0]);
            perspectives[1].setLastRobot(lastRobots[0][1]);
            player.finalizeRound(perspectives);
            csvA.writeScoreRow(wbA, battleId, state[0]);
            csvB.writeScoreRow(wbB, battleId, state[0]);
        }

        csvA.close();
        csvB.close();
        loader.close();
    }

    private static Whiteboard createWhiteboard() {
        Whiteboard wb = new Whiteboard();
        wb.registerFeatures(
                new SpatialFeatures(),
                new MovementFeatures(),
                new TimingFeatures());
        return wb;
    }

    /** Derive a battle ID from the .br file path (filename without extension). */
    private static String deriveBattleId(Path brFile) {
        String name = brFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Recursively find all .br files in a directory. */
    private static void findBrFiles(Path dir, List<Path> result) throws IOException {
        if (!Files.isDirectory(dir)) {
            if (dir.toString().endsWith(".br")) {
                result.add(dir);
            }
            return;
        }
        DirectoryStream<Path> stream = Files.newDirectoryStream(dir);
        for (Path entry : stream) {
            if (Files.isDirectory(entry)) {
                findBrFiles(entry, result);
            } else if (entry.toString().endsWith(".br")) {
                result.add(entry);
            }
        }
        stream.close();
    }
}

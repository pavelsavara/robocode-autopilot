package cz.zamboch.autopilot.pipeline;

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
 * Pipeline CLI: replays .br recordings through PipelineOrchestrator.
 * Usage: pipeline --input <recordings-dir> --output <csv-dir>
 *
 * Each .br recording is replayed producing:
 * <output>/<battleId>/<perspectiveName>/ticks.csv, our-waves.csv, their-waves.csv, scores.csv
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
        List<Path> brFiles = new ArrayList<>();
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
        Loader loader = new Loader(brFile);

        // We need to peek at the first turn to get BattleRules (bfWidth, bfHeight, gunCoolingRate)
        // Use a two-phase approach: first read header via forEachTurn (gets recordInfo), then replay
        final BattleRecordInfo[] infoHolder = { null };
        final List<int[]> roundTurns = new ArrayList<>();
        final List<ITurnSnapshot> snapshots = new ArrayList<>();

        loader.forEachTurn((roundIndex, turn) -> {
            if (infoHolder[0] == null) {
                infoHolder[0] = loader.getRecordInfo();
            }
            snapshots.add(turn);
        });
        loader.close();

        if (infoHolder[0] == null || snapshots.isEmpty()) {
            System.err.println("WARN: Empty recording: " + brFile);
            return;
        }

        BattleRules rules = infoHolder[0].battleRules;
        double bfWidth = rules.getBattlefieldWidth();
        double bfHeight = rules.getBattlefieldHeight();
        double gunCoolingRate = rules.getGunCoolingRate();

        // Get robot names from first snapshot
        IRobotSnapshot[] firstRobots = snapshots.get(0).getRobots();
        String nameA = firstRobots[0].getShortName();
        String nameB = firstRobots[1].getShortName();
        System.out.println("  " + battleId + ": " + nameA + " vs " + nameB);

        // Set up PipelineOrchestrator
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(bfWidth, bfHeight, gunCoolingRate);

        // Attach CSV writers
        File dirA = outBase.resolve(battleId).resolve(nameA).toFile();
        File dirB = outBase.resolve(battleId).resolve(nameB).toFile();
        CsvWriter csvA = new CsvWriter(dirA);
        CsvWriter csvB = new CsvWriter(dirB);
        orchestrator.setCsvWriters(csvA, csvB);
        orchestrator.setBattleId(battleId);
        csvA.writeHeaders(battleId);
        csvB.writeHeaders(battleId);

        // Attach validator
        PipelineValidator validator = new PipelineValidator(bfWidth, bfHeight);
        orchestrator.setValidator(validator);

        // Replay all snapshots through the orchestrator
        for (ITurnSnapshot snap : snapshots) {
            orchestrator.processTurn(snap);
        }

        orchestrator.close();

        // Print validation summary
        validator.printSummary();
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
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    findBrFiles(entry, result);
                } else if (entry.toString().endsWith(".br")) {
                    result.add(entry);
                }
            }
        }
    }
}

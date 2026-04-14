package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Transformer;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.pipeline.features.EnergyOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.MovementOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.SpatialOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.TimingOfflineFeatures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test: processes a real .br recording through the full pipeline
 * and validates the generated CSV structure.
 */
class CsvWriterTest {

    private static final Path RECORDINGS_DIR = Paths.get("..", "recordings", "24360294214");

    @Test
    void endToEndProducesCsvWithExpectedStructure(@TempDir Path tempDir) throws Exception {
        File brFile = findFirstRecording();
        Main.processBattle(brFile.toPath(), tempDir);

        // Find the output directories
        String battleId = brFile.getName().replace(".br", "");
        File battleDir = tempDir.resolve(battleId).toFile();
        assertTrue(battleDir.exists(), "Battle output directory should exist");

        File[] perspectiveDirs = battleDir.listFiles();
        assertNotNull(perspectiveDirs);
        assertEquals(2, perspectiveDirs.length, "Should have 2 robot perspective directories");

        for (File perspDir : perspectiveDirs) {
            File ticksCsv = new File(perspDir, "ticks.csv");
            assertTrue(ticksCsv.exists(), "ticks.csv should exist for " + perspDir.getName());

            List<String> lines = readLines(ticksCsv);
            assertTrue(lines.size() > 1, "ticks.csv should have header + data rows");

            // Validate header
            String header = lines.get(0);
            assertTrue(header.startsWith("battle_id,round,tick,scan_available"),
                    "Header should start with fixed columns");
            assertTrue(header.contains("distance"), "Header should contain distance");
            assertTrue(header.contains("opponent_velocity"), "Header should contain opponent_velocity");
            assertTrue(header.contains("our_gun_heat"), "Header should contain our_gun_heat");

            // Count expected columns
            int expectedCols = header.split(",").length;
            assertTrue(expectedCols >= 16, "Should have at least 16 columns (4 fixed + 12 features)");

            // Validate data rows have same column count
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                int cols = line.split(",", -1).length;
                assertEquals(expectedCols, cols,
                        "Row " + i + " should have " + expectedCols + " columns but had " + cols);
            }

            // Validate first data row has battle_id
            String firstRow = lines.get(1);
            String[] firstCols = firstRow.split(",", -1);
            assertEquals(battleId, firstCols[0], "First column should be battle_id");
            assertEquals("0", firstCols[1], "First row should be round 0");
            assertEquals("0", firstCols[2], "First row should be tick 0");
        }
    }

    @Test
    void csvRowCountMatchesTurnCount(@TempDir Path tempDir) throws Exception {
        File brFile = findFirstRecording();
        Loader loader = new Loader(brFile.toPath());

        // Count total turns
        final int[] totalTurns = {0};
        loader.forEachTurn(new Loader.TurnConsumer() {
            public void accept(int roundIndex, robocode.control.snapshot.ITurnSnapshot turn) {
                totalTurns[0]++;
            }
        });

        // Process through pipeline
        Main.processBattle(brFile.toPath(), tempDir);

        String battleId = brFile.getName().replace(".br", "");
        File battleDir = tempDir.resolve(battleId).toFile();
        File[] perspDirs = battleDir.listFiles();
        assertNotNull(perspDirs);

        for (File perspDir : perspDirs) {
            List<String> lines = readLines(new File(perspDir, "ticks.csv"));
            // Lines = header + data rows. Data rows should equal total turns.
            assertEquals(totalTurns[0] + 1, lines.size(),
                    "CSV row count should equal total turns + 1 header for " + perspDir.getName());
        }
    }

    @Test
    void processAllRecordings(@TempDir Path tempDir) throws Exception {
        File dir = RECORDINGS_DIR.toFile();
        assertTrue(dir.exists(), "Recordings directory must exist");
        File[] brFiles = dir.listFiles(new java.io.FilenameFilter() {
            public boolean accept(File d, String name) {
                return name.endsWith(".br");
            }
        });
        assertNotNull(brFiles);
        assertTrue(brFiles.length >= 10, "Should have at least 10 .br files");

        int success = 0;
        for (File brFile : brFiles) {
            try {
                Main.processBattle(brFile.toPath(), tempDir);
                success++;
            } catch (Exception e) {
                fail("Failed to process " + brFile.getName() + ": " + e.getMessage());
            }
        }

        assertEquals(brFiles.length, success, "All recordings should process successfully");
    }

    private File findFirstRecording() {
        File dir = RECORDINGS_DIR.toFile();
        assertTrue(dir.exists(), "Recordings directory must exist: " + dir.getAbsolutePath());
        File[] brFiles = dir.listFiles(new java.io.FilenameFilter() {
            public boolean accept(File d, String name) {
                return name.endsWith(".br");
            }
        });
        assertNotNull(brFiles);
        assertTrue(brFiles.length > 0, "Must have at least one .br file");
        return brFiles[0];
    }

    private static List<String> readLines(File file) throws IOException {
        List<String> lines = new ArrayList<String>();
        BufferedReader reader = new BufferedReader(new FileReader(file));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } finally {
            reader.close();
        }
        return lines;
    }
}

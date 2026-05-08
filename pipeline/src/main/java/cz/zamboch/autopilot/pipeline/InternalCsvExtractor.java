package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import robocode.control.snapshot.IRobotSnapshot;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Extracts robot console output from {@code IRobotSnapshot.getOutputStreamSnapshot()}
 * and splits it into:
 * <ul>
 *   <li>{@code internal.csv} — lines prefixed {@code TICK,} (feature values per scan tick).
 *       Header generated from {@link Feature} enum; gun hit-rate column count inferred
 *       from the first data row.</li>
 *   <li>{@code debug.log} — everything else (DATA_LOAD, WARN, VCS_LOAD, etc.)</li>
 * </ul>
 */
public final class InternalCsvExtractor implements Closeable {

    private static final String TICK_PREFIX = "TICK,";

    /** Known columns before gun hit-rate group: round + tick + Features + 4 fixed meta. */
    private static final int KNOWN_PREFIX_COLS = 2 + Feature.values().length + 4;
    /** Trailing columns after gun hit-rates: move_cmd_ahead, move_cmd_turn. */
    private static final int TRAILING_COLS = 2;

    private final OutputStream csvStream;
    private final OutputStream logStream;
    private int lastOutputLength;
    private boolean headerWritten;

    public InternalCsvExtractor(Path outputDir, String battleId, String robotName)
            throws IOException {
        Path dir = outputDir.resolve(battleId).resolve(robotName);
        Files.createDirectories(dir);
        csvStream = new BufferedOutputStream(
                new FileOutputStream(dir.resolve("internal.csv").toFile()));
        logStream = new BufferedOutputStream(
                new FileOutputStream(dir.resolve("debug.log").toFile()));
        lastOutputLength = 0;
        headerWritten = false;
    }

    public void processTurn(IRobotSnapshot robot) {
        String fullOutput = robot.getOutputStreamSnapshot();
        if (fullOutput == null || fullOutput.isEmpty()) {
            return;
        }

        // outputStreamSnapshot may be cumulative (growing each turn) or may
        // reset between rounds. Handle both: if length shrank, process from start.
        if (fullOutput.length() < lastOutputLength) {
            lastOutputLength = 0;
        }
        if (fullOutput.length() == lastOutputLength) {
            return;
        }

        String delta = fullOutput.substring(lastOutputLength);
        lastOutputLength = fullOutput.length();

        String[] lines = delta.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            try {
                if (trimmed.startsWith(TICK_PREFIX)) {
                    String row = trimmed.substring(TICK_PREFIX.length());
                    if (!headerWritten) {
                        writeHeader(row);
                        headerWritten = true;
                    }
                    csvStream.write(row.getBytes(StandardCharsets.UTF_8));
                    csvStream.write('\n');
                } else {
                    // Filter out TICK line fragments (tails split by per-turn buffer).
                    // Real log messages start with a letter, '=' or '['.
                    char first = trimmed.charAt(0);
                    if (Character.isLetter(first) || first == '=' || first == '[') {
                        logStream.write(trimmed.getBytes(StandardCharsets.UTF_8));
                        logStream.write('\n');
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to write internal CSV / debug log", e);
            }
        }
    }

    /** Generate CSV header from Feature enum; infer gun hit-rate count from field count. */
    private void writeHeader(String firstRow) throws IOException {
        int totalFields = firstRow.split(",", -1).length;
        int gunHrCols = totalFields - KNOWN_PREFIX_COLS - TRAILING_COLS;
        if (gunHrCols < 0) gunHrCols = 0;

        StringBuilder hdr = new StringBuilder("round,tick");
        for (Feature f : Feature.values()) {
            hdr.append(',').append(f.name().toLowerCase());
        }
        hdr.append(",selected_gun_idx,move_strategy_idx,fire_power_budget,aggression");
        for (int i = 0; i < gunHrCols; i++) {
            hdr.append(",gun_hr_").append(i);
        }
        hdr.append(",move_cmd_ahead,move_cmd_turn");
        csvStream.write(hdr.toString().getBytes(StandardCharsets.UTF_8));
        csvStream.write('\n');
    }

    @Override
    public void close() throws IOException {
        csvStream.close();
        logStream.close();
    }
}

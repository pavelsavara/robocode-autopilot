package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Transformer;
import cz.zamboch.autopilot.core.Whiteboard;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Produces CSV files per battle per robot perspective.
 * Delegates column writing to IOfflineFeatures instances from the Transformer.
 * Creates ticks.csv, waves.csv, and scores.csv based on registered features.
 */
public final class CsvWriter implements Closeable {
    private final Transformer transformer;

    private final CsvRowWriter ticksRow;
    private final OutputStream ticksStream;
    private final List<IOfflineFeatures> ticksFeatures;

    private final CsvRowWriter wavesRow;
    private final OutputStream wavesStream;
    private final List<IOfflineFeatures> wavesFeatures;

    private final CsvRowWriter scoresRow;
    private final OutputStream scoresStream;
    private final List<IOfflineFeatures> scoresFeatures;

    public CsvWriter(Path outputDir, String battleId, String robotName, Transformer transformer)
            throws IOException {
        this.transformer = transformer;

        // Collect offline features by file type
        ticksFeatures = new ArrayList<IOfflineFeatures>();
        wavesFeatures = new ArrayList<IOfflineFeatures>();
        scoresFeatures = new ArrayList<IOfflineFeatures>();
        for (IInGameFeatures f : transformer.getFeatures()) {
            if (f instanceof IOfflineFeatures) {
                IOfflineFeatures off = (IOfflineFeatures) f;
                if (off.getFileType() == FileType.TICKS) {
                    ticksFeatures.add(off);
                } else if (off.getFileType() == FileType.WAVES) {
                    wavesFeatures.add(off);
                } else if (off.getFileType() == FileType.SCORES) {
                    scoresFeatures.add(off);
                }
            }
        }

        // Create output directory
        Path dir = outputDir.resolve(battleId).resolve(robotName);
        Files.createDirectories(dir);

        // Open ticks.csv
        ticksStream = new BufferedOutputStream(new FileOutputStream(dir.resolve("ticks.csv").toFile()));
        ticksRow = new CsvRowWriter(ticksStream);

        // Open waves.csv
        wavesStream = new BufferedOutputStream(new FileOutputStream(dir.resolve("waves.csv").toFile()));
        wavesRow = new CsvRowWriter(wavesStream);

        // Open scores.csv
        scoresStream = new BufferedOutputStream(new FileOutputStream(dir.resolve("scores.csv").toFile()));
        scoresRow = new CsvRowWriter(scoresStream);
    }

    /** Write header rows for all CSV files. */
    public void writeHeaders() {
        ticksRow.beginRow();
        ticksRow.writeHeaders("battle_id", "round", "tick", "scan_available");
        for (IOfflineFeatures f : ticksFeatures) {
            f.writeColumnNames(ticksRow);
        }
        ticksRow.endRow();

        wavesRow.beginRow();
        wavesRow.writeHeaders("battle_id", "round", "tick");
        for (IOfflineFeatures f : wavesFeatures) {
            f.writeColumnNames(wavesRow);
        }
        wavesRow.endRow();

        scoresRow.beginRow();
        scoresRow.writeHeaders("battle_id", "round", "ticks_in_round");
        for (IOfflineFeatures f : scoresFeatures) {
            f.writeColumnNames(scoresRow);
        }
        scoresRow.endRow();
    }

    /** Write one data row to ticks.csv. */
    public void writeTickRow(Whiteboard wb) {
        ticksRow.beginRow();
        ticksRow.writeRaw(wb.getBattleId());
        ticksRow.writeRaw(Integer.toString(wb.getRound()));
        ticksRow.writeRaw(Long.toString(wb.getTick()));
        ticksRow.writeRaw(wb.isScanAvailableThisTick() ? "1" : "0");
        for (IOfflineFeatures f : ticksFeatures) {
            f.writeRowValues(ticksRow, wb);
        }
        ticksRow.endRow();
    }

    /** Write one data row to waves.csv (call when opponent fire detected). */
    public void writeWaveRow(Whiteboard wb) {
        wavesRow.beginRow();
        wavesRow.writeRaw(wb.getBattleId());
        wavesRow.writeRaw(Integer.toString(wb.getRound()));
        wavesRow.writeRaw(Long.toString(wb.getTick()));
        for (IOfflineFeatures f : wavesFeatures) {
            f.writeRowValues(wavesRow, wb);
        }
        wavesRow.endRow();
    }

    /** Write one data row to scores.csv (call at round end). */
    public void writeScoreRow(Whiteboard wb, long ticksInRound) {
        scoresRow.beginRow();
        scoresRow.writeRaw(wb.getBattleId());
        scoresRow.writeRaw(Integer.toString(wb.getRound()));
        scoresRow.writeRaw(Long.toString(ticksInRound));
        for (IOfflineFeatures f : scoresFeatures) {
            f.writeRowValues(scoresRow, wb);
        }
        scoresRow.endRow();
    }

    public void close() throws IOException {
        ticksStream.close();
        wavesStream.close();
        scoresStream.close();
    }
}

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
 */
public final class CsvWriter implements Closeable {
    private final Transformer transformer;
    private final CsvRowWriter ticksRow;
    private final OutputStream ticksStream;
    private final List<IOfflineFeatures> ticksFeatures;

    public CsvWriter(Path outputDir, String battleId, String robotName, Transformer transformer)
            throws IOException {
        this.transformer = transformer;

        // Collect offline features by file type
        ticksFeatures = new ArrayList<IOfflineFeatures>();
        for (IInGameFeatures f : transformer.getFeatures()) {
            if (f instanceof IOfflineFeatures) {
                IOfflineFeatures off = (IOfflineFeatures) f;
                if (off.getFileType() == FileType.TICKS) {
                    ticksFeatures.add(off);
                }
            }
        }

        // Create output directory
        Path dir = outputDir.resolve(battleId).resolve(robotName);
        Files.createDirectories(dir);

        // Open ticks.csv
        ticksStream = new BufferedOutputStream(new FileOutputStream(dir.resolve("ticks.csv").toFile()));
        ticksRow = new CsvRowWriter(ticksStream);
    }

    /** Write header row for ticks.csv. */
    public void writeHeaders() {
        ticksRow.beginRow();
        ticksRow.writeHeaders("battle_id", "round", "tick", "scan_available");
        for (IOfflineFeatures f : ticksFeatures) {
            f.writeColumnNames(ticksRow);
        }
        ticksRow.endRow();
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

    public void close() throws IOException {
        ticksStream.close();
    }
}

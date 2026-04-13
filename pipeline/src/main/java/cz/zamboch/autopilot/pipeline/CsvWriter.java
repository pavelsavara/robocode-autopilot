package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Transformer;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Produces CSV files per battle per robot perspective.
 * Delegates column writing to IOfflineFeatureProcessor instances from the Transformer.
 */
public class CsvWriter implements Closeable {
    private final Path outputDir;
    private final String battleId;
    private final String robotName;
    private final Transformer transformer;

    public CsvWriter(Path outputDir, String battleId, String robotName, Transformer transformer) {
        this.outputDir = outputDir;
        this.battleId = battleId;
        this.robotName = robotName;
        this.transformer = transformer;
    }

    public void close() throws IOException {
        // TODO: Phase E — close output streams
    }
}

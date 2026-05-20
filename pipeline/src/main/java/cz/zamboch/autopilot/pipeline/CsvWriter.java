package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates and manages CSV output files for one perspective of a battle.
 * Routes features to the correct CSV file based on their FileType.
 * <p>
 * Output structure:
 * {@code <outputDir>/<battleId>/<robotName>/ticks.csv|waves.csv|scores.csv}
 */
public final class CsvWriter implements Closeable {
    private final CsvRowWriter ticksWriter;
    private final CsvRowWriter wavesWriter;
    private final CsvRowWriter scoresWriter;

    private final List<Feature> ticksFeatures = new ArrayList<Feature>();
    private final List<Feature> wavesFeatures = new ArrayList<Feature>();
    private final List<Feature> scoresFeatures = new ArrayList<Feature>();

    public CsvWriter(File outputDir) throws IOException {
        outputDir.mkdirs();

        ticksWriter = new CsvRowWriter(new FileOutputStream(new File(outputDir, "ticks.csv")));
        wavesWriter = new CsvRowWriter(new FileOutputStream(new File(outputDir, "waves.csv")));
        scoresWriter = new CsvRowWriter(new FileOutputStream(new File(outputDir, "scores.csv")));

        // Group features by file type
        for (Feature f : Feature.values()) {
            switch (f.getFileType()) {
                case TICKS:
                    ticksFeatures.add(f);
                    break;
                case WAVES:
                    wavesFeatures.add(f);
                    break;
                case SCORES:
                    scoresFeatures.add(f);
                    break;
            }
        }
    }

    /** Write header rows for all three CSV files. */
    public void writeHeaders(String battleId) throws IOException {
        // ticks.csv: battle_id, round, tick, then all TICKS features
        ticksWriter.beginRow();
        ticksWriter.writeHeaders("battle_id", "round", "tick");
        for (Feature f : ticksFeatures) {
            ticksWriter.writeHeader(f.name().toLowerCase());
        }
        ticksWriter.endRow();

        // waves.csv: battle_id, round, tick, fire_power, then all WAVES features
        wavesWriter.beginRow();
        wavesWriter.writeHeaders("battle_id", "round", "tick", "fire_power");
        for (Feature f : wavesFeatures) {
            wavesWriter.writeHeader(f.name().toLowerCase());
        }
        wavesWriter.endRow();

        // scores.csv: battle_id, round, result, then all SCORES features
        scoresWriter.beginRow();
        scoresWriter.writeHeaders("battle_id", "round", "result");
        for (Feature f : scoresFeatures) {
            scoresWriter.writeHeader(f.name().toLowerCase());
        }
        scoresWriter.endRow();
    }

    /** Write one row to ticks.csv (called every tick). */
    public void writeTickRow(Whiteboard wb, String battleId, int round) throws IOException {
        ticksWriter.beginRow();
        ticksWriter.writeRaw(battleId);
        ticksWriter.writeInt(round);
        ticksWriter.writeLong((long) wb.getFeature(Feature.TICK));
        for (Feature f : ticksFeatures) {
            ticksWriter.writeDouble(wb, f);
        }
        ticksWriter.endRow();
    }

    /** Write one row to waves.csv (called when opponent fire is detected). */
    public void writeWaveRow(Whiteboard wb, String battleId, int round) throws IOException {
        wavesWriter.beginRow();
        wavesWriter.writeRaw(battleId);
        wavesWriter.writeInt(round);
        wavesWriter.writeLong((long) wb.getFeature(Feature.TICK));
        wavesWriter.writeRaw(Double.toString(wb.getFeature(Feature.OPPONENT_FIRE_POWER)));
        for (Feature f : wavesFeatures) {
            wavesWriter.writeDouble(wb, f);
        }
        wavesWriter.endRow();
    }

    /** Write one row to scores.csv (called at end of each round). */
    public void writeScoreRow(Whiteboard wb, String battleId, int round) throws IOException {
        scoresWriter.beginRow();
        scoresWriter.writeRaw(battleId);
        scoresWriter.writeInt(round);
        scoresWriter.writeInt((int) wb.getFeature(Feature.ROUND_RESULT));
        for (Feature f : scoresFeatures) {
            scoresWriter.writeDouble(wb, f);
        }
        scoresWriter.endRow();
    }

    @Override
    public void close() throws IOException {
        ticksWriter.close();
        wavesWriter.close();
        scoresWriter.close();
    }
}

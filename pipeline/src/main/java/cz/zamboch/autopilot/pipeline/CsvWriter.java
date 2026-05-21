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
 * {@code <outputDir>/<battleId>/<robotName>/ticks.csv|their-waves.csv|our-waves.csv|scores.csv}
 */
public final class CsvWriter implements Closeable {
    private final CsvRowWriter ticksWriter;
    private final CsvRowWriter theirWavesWriter;
    private final CsvRowWriter ourWavesWriter;
    private final CsvRowWriter scoresWriter;

    private final List<Feature> ticksFeatures = new ArrayList<Feature>();
    private final List<Feature> theirWavesFeatures = new ArrayList<Feature>();
    private final List<Feature> ourWavesFeatures = new ArrayList<Feature>();
    private final List<Feature> scoresFeatures = new ArrayList<Feature>();

    public CsvWriter(File outputDir) throws IOException {
        outputDir.mkdirs();

        ticksWriter = new CsvRowWriter(new FileOutputStream(new File(outputDir, "ticks.csv")));
        theirWavesWriter = new CsvRowWriter(new FileOutputStream(new File(outputDir, "their-waves.csv")));
        ourWavesWriter = new CsvRowWriter(new FileOutputStream(new File(outputDir, "our-waves.csv")));
        scoresWriter = new CsvRowWriter(new FileOutputStream(new File(outputDir, "scores.csv")));

        // Group features by file type
        for (Feature f : Feature.values()) {
            switch (f.getFileType()) {
                case TICKS:
                    ticksFeatures.add(f);
                    break;
                case THEIR_WAVES:
                    theirWavesFeatures.add(f);
                    break;
                case OUR_WAVES:
                    ourWavesFeatures.add(f);
                    break;
                case SCORES:
                    if (f != Feature.ROUND_RESULT) {
                        scoresFeatures.add(f);
                    }
                    break;
            }
        }
    }

    /** Write header rows for all CSV files. */
    public void writeHeaders(String battleId) throws IOException {
        // ticks.csv: battle_id, round, tick, then all TICKS features
        ticksWriter.beginRow();
        ticksWriter.writeHeaders("battle_id", "round", "tick");
        for (Feature f : ticksFeatures) {
            ticksWriter.writeHeader(f.name().toLowerCase());
        }
        ticksWriter.endRow();

        // their-waves.csv: battle_id, round, tick, then all THEIR_WAVES features
        theirWavesWriter.beginRow();
        theirWavesWriter.writeHeaders("battle_id", "round", "tick");
        for (Feature f : theirWavesFeatures) {
            theirWavesWriter.writeHeader(f.name().toLowerCase());
        }
        theirWavesWriter.endRow();

        // our-waves.csv: battle_id, round, tick, then all OUR_WAVES features
        ourWavesWriter.beginRow();
        ourWavesWriter.writeHeaders("battle_id", "round", "tick");
        for (Feature f : ourWavesFeatures) {
            ourWavesWriter.writeHeader(f.name().toLowerCase());
        }
        ourWavesWriter.endRow();

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

    /** Write one row to their-waves.csv (called when their wave resolves). */
    public void writeTheirWaveRow(Whiteboard wb, String battleId, int round) throws IOException {
        theirWavesWriter.beginRow();
        theirWavesWriter.writeRaw(battleId);
        theirWavesWriter.writeInt(round);
        theirWavesWriter.writeLong((long) wb.getFeature(Feature.TICK));
        for (Feature f : theirWavesFeatures) {
            theirWavesWriter.writeDouble(wb, f);
        }
        theirWavesWriter.endRow();
    }

    /** Write one row to our-waves.csv (called when we fire a bullet). */
    public void writeOurWaveRow(Whiteboard wb, String battleId, int round) throws IOException {
        ourWavesWriter.beginRow();
        ourWavesWriter.writeRaw(battleId);
        ourWavesWriter.writeInt(round);
        ourWavesWriter.writeLong((long) wb.getFeature(Feature.TICK));
        for (Feature f : ourWavesFeatures) {
            ourWavesWriter.writeDouble(wb, f);
        }
        ourWavesWriter.endRow();
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
        theirWavesWriter.close();
        ourWavesWriter.close();
        scoresWriter.close();
    }
}

package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.OurWaveColumn;
import cz.zamboch.autopilot.core.TheirWaveColumn;
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
 * {@code <outputDir>/<battleId>/<robotName>/ticks.csv|scan.csv|their-waves.csv|autopilot-waves.csv|dejavu-waves.csv|scores.csv}
 */
public final class CsvWriter implements Closeable {
    private final CsvRowWriter ticksWriter;
    private final CsvRowWriter scanWriter;
    private final CsvRowWriter theirWavesWriter;
    private final CsvRowWriter autopilotWavesWriter;
    private final CsvRowWriter dejavuWavesWriter;
    private final CsvRowWriter scoresWriter;

    private final List<Feature> ticksFeatures = new ArrayList<Feature>();
    private final List<Feature> scanFeatures = new ArrayList<Feature>();
    private final List<Feature> theirWavesFeatures = new ArrayList<Feature>();
    private final List<Feature> ourWavesFeatures = new ArrayList<Feature>();
    private final List<Feature> scoresFeatures = new ArrayList<Feature>();

    public CsvWriter(File outputDir) throws IOException {
        outputDir.mkdirs();

        ticksWriter = new CsvRowWriter(new FileOutputStream(new File(outputDir, "ticks.csv")));
        scanWriter = new CsvRowWriter(new FileOutputStream(new File(outputDir, "scan.csv")));
        theirWavesWriter = new CsvRowWriter(new FileOutputStream(new File(outputDir, "their-waves.csv")));
        autopilotWavesWriter = new CsvRowWriter(new FileOutputStream(new File(outputDir, "autopilot-waves.csv")));
        dejavuWavesWriter = new CsvRowWriter(new FileOutputStream(new File(outputDir, "dejavu-waves.csv")));
        scoresWriter = new CsvRowWriter(new FileOutputStream(new File(outputDir, "scores.csv")));

        // Group features by file type
        for (Feature f : Feature.values()) {
            switch (f.getFileType()) {
                case TICKS:
                    if (f != Feature.TICK) {
                        ticksFeatures.add(f);
                    }
                    break;
                case SCAN:
                    if (!excludedFromScanCsv(f)) {
                        scanFeatures.add(f);
                    }
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
                case DECISIONS:
                    // intentionally excluded from CSV output
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

        // scan.csv: battle_id, round, tick, then all SCAN features
        scanWriter.beginRow();
        scanWriter.writeHeaders("battle_id", "round", "tick");
        for (Feature f : scanFeatures) {
            scanWriter.writeHeader(scanHeader(f));
        }
        scanWriter.endRow();

        // their-waves.csv: battle_id, round, tick, then all THEIR_WAVES features
        theirWavesWriter.beginRow();
        theirWavesWriter.writeHeaders("battle_id", "round", "tick");
        for (Feature f : theirWavesFeatures) {
            theirWavesWriter.writeHeader(f.name().toLowerCase());
        }
        theirWavesWriter.endRow();

        // autopilot-waves.csv: battle_id, round, tick, then all OUR_WAVES features
        autopilotWavesWriter.beginRow();
        autopilotWavesWriter.writeHeaders("battle_id", "round", "tick");
        for (Feature f : ourWavesFeatures) {
            autopilotWavesWriter.writeHeader(f.name().toLowerCase());
        }
        autopilotWavesWriter.endRow();

        // dejavu-waves.csv: same schema as autopilot-waves.csv, but sourced from
        // reconstructed real fire commands.
        dejavuWavesWriter.beginRow();
        dejavuWavesWriter.writeHeaders("battle_id", "round", "tick");
        for (Feature f : ourWavesFeatures) {
            dejavuWavesWriter.writeHeader(f.name().toLowerCase());
        }
        dejavuWavesWriter.endRow();

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

    /** Write one row to scan.csv (called when a scan row exists for this tick). */
    public void writeScanRow(Whiteboard wb, String battleId, int round) throws IOException {
        scanWriter.beginRow();
        scanWriter.writeRaw(battleId);
        scanWriter.writeInt(round);
        scanWriter.writeLong((long) wb.getFeature(Feature.TICK));
        for (Feature f : scanFeatures) {
            if (f == Feature.OPPONENT_ID) {
                scanWriter.writeString(wb.getStringFeature(f));
            } else {
                scanWriter.writeRaw(format(wb.getFeature(f)));
            }
        }
        scanWriter.endRow();
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

    /** Write one row to their-waves.csv from a resolved their-wave ring slot. */
    public void writeTheirWaveRow(Whiteboard wb, int slot, String battleId, int round) throws IOException {
        theirWavesWriter.beginRow();
        theirWavesWriter.writeRaw(battleId);
        theirWavesWriter.writeInt(round);
        theirWavesWriter.writeLong((long) wb.getFeature(Feature.TICK));
        for (Feature f : theirWavesFeatures) {
            theirWavesWriter.writeRaw(format(wb.getTheirWave(slot, TheirWaveColumn.values()[f.columnIndex()])));
        }
        theirWavesWriter.endRow();
    }

    /** Write one row to autopilot-waves.csv from staged OUR_WAVES features. */
    public void writeOurWaveRow(Whiteboard wb, String battleId, int round) throws IOException {
        autopilotWavesWriter.beginRow();
        autopilotWavesWriter.writeRaw(battleId);
        autopilotWavesWriter.writeInt(round);
        autopilotWavesWriter.writeLong((long) wb.getFeature(Feature.TICK));
        for (Feature f : ourWavesFeatures) {
            autopilotWavesWriter.writeDouble(wb, f);
        }
        autopilotWavesWriter.endRow();
    }

    /** Write one row to autopilot-waves.csv from a resolved our-wave ring slot. */
    public void writeOurWaveRow(Whiteboard wb, int slot, String battleId, int round) throws IOException {
        autopilotWavesWriter.beginRow();
        autopilotWavesWriter.writeRaw(battleId);
        autopilotWavesWriter.writeInt(round);
        autopilotWavesWriter.writeLong((long) wb.getFeature(Feature.TICK));
        for (Feature f : ourWavesFeatures) {
            autopilotWavesWriter.writeRaw(format(wb.getOurWave(slot, OurWaveColumn.values()[f.columnIndex()])));
        }
        autopilotWavesWriter.endRow();
    }

    /** Write one row to dejavu-waves.csv using the OUR_WAVES schema. */
    public void writeDejavuWaveRow(double[] valuesByColumn, String battleId, int round, long tick) throws IOException {
        dejavuWavesWriter.beginRow();
        dejavuWavesWriter.writeRaw(battleId);
        dejavuWavesWriter.writeInt(round);
        dejavuWavesWriter.writeLong(tick);
        for (Feature f : ourWavesFeatures) {
            dejavuWavesWriter.writeRaw(format(valuesByColumn[f.columnIndex()]));
        }
        dejavuWavesWriter.endRow();
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
        try {
            ticksWriter.close();
        } finally {
            try {
                scanWriter.close();
            } finally {
                try {
                    theirWavesWriter.close();
                } finally {
                    try {
                        autopilotWavesWriter.close();
                    } finally {
                        try {
                            dejavuWavesWriter.close();
                        } finally {
                            scoresWriter.close();
                        }
                    }
                }
            }
        }
    }

    private static String scanHeader(Feature f) {
        String name = f.name().toLowerCase();
        return name.startsWith("scan_") ? name : "scan_" + name;
    }

    private static boolean excludedFromScanCsv(Feature f) {
        return f == Feature.OPPONENT_ID
                || f == Feature.PREV_SCAN_OPPONENT_ENERGY
                || f == Feature.OUR_BULLET_DAMAGE_TO_OPPONENT
                || f == Feature.OPPONENT_BULLET_ENERGY_GAIN
                || f == Feature.RAM_DAMAGE_TO_OPPONENT
                || f == Feature.OPPONENT_WALL_HIT_DAMAGE;
    }

    private static String format(double value) {
        return Double.isNaN(value) ? "NaN" : Double.toString(value);
    }
}

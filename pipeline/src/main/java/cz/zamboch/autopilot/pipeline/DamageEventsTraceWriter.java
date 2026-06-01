package cz.zamboch.autopilot.pipeline;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Per-tick CSV trace of Layer 2 (autopilot damage-observation drift): one row
 * per (round, tick, channel) where either the god-view event total or the
 * observed accumulator is non-zero. Drives the BeepBoop-energy-events.md
 * analysis (mirror of BeepBoop-fired-bullets.md for Layer 3).
 * <p>
 * Off by default; enable with system property {@code damage.events.dir}
 * (Gradle: {@code -PdamageEventsDir=...}). Append mode so all opponents share
 * one file; opponent column distinguishes them.
 * <p>
 * Channels: {@code OUR_BULLET_DMG}, {@code OPP_BULLET_GAIN}, {@code RAM_DMG},
 * {@code OPP_WALL_DMG} (same order as the validator).
 */
public final class DamageEventsTraceWriter implements Closeable {

    private static final String HEADER = "opponent,round,tick,channel,gv,obs,drift,ourEnergy,oppEnergy,ourState,oppState";

    private final BufferedWriter w;
    private final String opponent;

    public DamageEventsTraceWriter(File dir, String opponent) throws IOException {
        dir.mkdirs();
        this.opponent = opponent;
        File f = new File(dir, "damage-events.csv");
        boolean needHeader = !f.exists() || f.length() == 0;
        this.w = new BufferedWriter(new FileWriter(f, true));
        if (needHeader) {
            w.write(HEADER);
            w.write('\n');
        }
    }

    public void write(int round, long tick, String channel,
            double gv, double obs,
            double ourEnergy, double oppEnergy,
            String ourState, String oppState) {
        try {
            w.write(opponent);
            w.write(',');
            w.write(Integer.toString(round));
            w.write(',');
            w.write(Long.toString(tick));
            w.write(',');
            w.write(channel);
            w.write(',');
            w.write(d(gv));
            w.write(',');
            w.write(d(obs));
            w.write(',');
            w.write(d(obs - gv));
            w.write(',');
            w.write(d(ourEnergy));
            w.write(',');
            w.write(d(oppEnergy));
            w.write(',');
            w.write(ourState == null ? "" : ourState);
            w.write(',');
            w.write(oppState == null ? "" : oppState);
            w.write('\n');
            w.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String d(double v) {
        return Double.isNaN(v) ? "" : Double.toString(v);
    }

    @Override
    public void close() throws IOException {
        w.close();
    }
}

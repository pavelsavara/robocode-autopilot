package cz.zamboch.autopilot.pipeline;

import robocode.control.snapshot.IDebugProperty;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * Dumps the raw {@link IDebugProperty} feature state of both sides of the
 * fidelity comparison to long-format CSV files for offline diffing:
 * <ul>
 * <li>{@code in-game.csv} — what the live {@code Autopilot} published
 * ({@code IRobotSnapshot.getDebugProperties()}).</li>
 * <li>{@code observer.csv} — what the observer shadow {@code Autopilot}
 * published (retained by {@link ObserverRobotPeer}).</li>
 * </ul>
 * Both sides emit through the identical {@code Autopilot.doTurn} publish path, so
 * a row-by-row diff of the two files exactly mirrors what
 * {@link Layer0DebugFidelityValidator} judges — but with <b>every</b> feature on
 * <b>every</b> tick (matches included), not just the mismatches, and without the
 * gradle-stdout truncation that makes per-tick console diagnostics unreliable.
 * <p>
 * Schema (both files): {@code opponent,round,perspective,tick,key,value}. The
 * identity columns are mandatory — rows from the 6 opponents × 2 perspectives ×
 * N rounds share tick numbers, so without them rows collide when joined.
 * <p>
 * Off by default; enabled by setting the {@code debug.csv.dir} system property to
 * an output directory. Files are opened in append mode so every
 * {@code BattleRunner.runBattle} call accumulates into one combined pair.
 */
public final class DebugPropertyCsvWriter implements Closeable {

    private static final String HEADER = "opponent,round,perspective,tick,key,value";

    private final BufferedWriter live;
    private final BufferedWriter observer;
    private final String opponent;

    public DebugPropertyCsvWriter(File dir, String opponent) throws IOException {
        dir.mkdirs();
        this.opponent = opponent;
        this.live = open(new File(dir, "in-game.csv"));
        this.observer = open(new File(dir, "observer.csv"));
    }

    private static BufferedWriter open(File file) throws IOException {
        boolean needHeader = !file.exists() || file.length() == 0;
        BufferedWriter w = new BufferedWriter(new FileWriter(file, true));
        if (needHeader) {
            w.write(HEADER);
            w.write('\n');
        }
        return w;
    }

    /** Write the live robot's published debug properties for this tick. */
    public void writeLive(int round, int perspective, long tick, IDebugProperty[] props) {
        if (props == null) {
            return;
        }
        try {
            for (IDebugProperty p : props) {
                writeRow(live, round, perspective, tick, p.getKey(), p.getValue());
            }
            live.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Write the observer shadow's published debug properties for this tick. */
    public void writeObserver(int round, int perspective, long tick, Map<String, String> props) {
        if (props == null) {
            return;
        }
        try {
            for (Map.Entry<String, String> e : props.entrySet()) {
                writeRow(observer, round, perspective, tick, e.getKey(), e.getValue());
            }
            observer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeRow(BufferedWriter w, int round, int perspective, long tick,
            String key, String value) throws IOException {
        w.write(escape(opponent));
        w.write(',');
        w.write(Integer.toString(round));
        w.write(',');
        w.write(Integer.toString(perspective));
        w.write(',');
        w.write(Long.toString(tick));
        w.write(',');
        w.write(escape(key));
        w.write(',');
        w.write(escape(value));
        w.write('\n');
    }

    /** Minimal CSV escaping: quote when the field contains a comma, quote, or newline. */
    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0) {
            return s;
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    @Override
    public void close() throws IOException {
        try {
            live.close();
        } finally {
            observer.close();
        }
    }
}

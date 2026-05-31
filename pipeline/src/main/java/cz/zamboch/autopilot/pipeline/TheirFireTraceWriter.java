package cz.zamboch.autopilot.pipeline;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Lightweight per-event CSV trace of incoming-fire detection (Layer 2 "their"):
 * every god-view opponent fire and every robot-side inferred opponent fire is
 * appended as a row so unpaired / spurious events can be diffed offline.
 * <p>
 * Off by default; enabled by setting the {@code their.fires.dir} system property
 * to an output directory. One file {@code their-fires.csv} aggregates all
 * opponents (opponent column distinguishes them).
 * <p>
 * Schema: {@code opponent,round,perspective,tick,kind,bulletId,power,x,y,headingOrBearing,
 * opponentEnergy,prevScanEnergy,scanGap,bulletDmg,bulletGain,ramDmg,wallDmg,oppState,selfState}.
 * {@code kind} is {@code GV} (god-view) or {@code RS} (robot-side).
 */
public final class TheirFireTraceWriter implements Closeable {

    private static final String HEADER = "opponent,round,perspective,tick,kind,bulletId,power,x,y,heading,"
            + "oppEnergy,prevScanEnergy,scanGap,bulletDmg,bulletGain,ramDmg,wallDmg,oppState,selfState";

    private final BufferedWriter w;
    private final String opponent;

    public TheirFireTraceWriter(File dir, String opponent) throws IOException {
        dir.mkdirs();
        this.opponent = opponent;
        File f = new File(dir, "their-fires.csv");
        boolean needHeader = !f.exists() || f.length() == 0;
        this.w = new BufferedWriter(new FileWriter(f, true));
        if (needHeader) {
            w.write(HEADER);
            w.write('\n');
        }
    }

    public void write(int round, int perspective, long tick, String kind, int bulletId,
            double power, double x, double y, double heading,
            double oppEnergy, double prevScanEnergy, int scanGap,
            double bulletDmg, double bulletGain, double ramDmg, double wallDmg,
            String oppState, String selfState) {
        try {
            w.write(opponent);
            w.write(',');
            w.write(Integer.toString(round));
            w.write(',');
            w.write(Integer.toString(perspective));
            w.write(',');
            w.write(Long.toString(tick));
            w.write(',');
            w.write(kind);
            w.write(',');
            w.write(Integer.toString(bulletId));
            w.write(',');
            w.write(d(power));
            w.write(',');
            w.write(d(x));
            w.write(',');
            w.write(d(y));
            w.write(',');
            w.write(d(heading));
            w.write(',');
            w.write(d(oppEnergy));
            w.write(',');
            w.write(d(prevScanEnergy));
            w.write(',');
            w.write(Integer.toString(scanGap));
            w.write(',');
            w.write(d(bulletDmg));
            w.write(',');
            w.write(d(bulletGain));
            w.write(',');
            w.write(d(ramDmg));
            w.write(',');
            w.write(d(wallDmg));
            w.write(',');
            w.write(oppState == null ? "" : oppState);
            w.write(',');
            w.write(selfState == null ? "" : selfState);
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

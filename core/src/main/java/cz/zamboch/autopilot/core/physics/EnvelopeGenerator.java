package cz.zamboch.autopilot.core.physics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Offline generator for reachable-envelope tables. Exhaustively explores
 * all control sequences (accel × turn) from each initial |velocity| up to
 * a given horizon, collecting the set of reachable (dx, dy) offsets
 * relative to the starting position at heading = 0.
 *
 * <p>Run {@link #main} to regenerate the embedded table data in
 * {@link ReachableEnvelope}. The output is a Java source snippet.
 *
 * <p>State deduplication uses a quantised key: position on a 2px grid,
 * velocity rounded to 0.5 px/tick, heading rounded to 1°. This keeps the
 * state count at ~700k for t=10 (matching the research simulation).
 */
public final class EnvelopeGenerator {

    /** Grid size for position deduplication during generation (px). */
    private static final int GEN_GRID = 2;

    /** Velocity quantisation step for state dedup (px/tick). */
    private static final double VEL_QUANT = 0.5;

    /** Heading quantisation step for state dedup (radians). */
    private static final double HDG_QUANT = Math.toRadians(1.0);

    /** Acceleration options: decel-2, decel-1, coast, accel+1. */
    private static final double[] ACCELS = {-2.0, -1.0, 0.0, 1.0};

    /** Turn fractions of maxTurnRate: full-left, half-left, straight, half-right, full-right. */
    private static final double[] TURN_FRACS = {-1.0, -0.5, 0.0, 0.5, 1.0};

    /** Large battlefield to avoid wall clamping during generation. */
    private static final int GEN_BF = 100000;

    private EnvelopeGenerator() {}

    /** Quantised state key for deduplication. */
    private static long stateKey(double x, double y, double velocity, double heading) {
        int gx = (int) Math.round(x / GEN_GRID);
        int gy = (int) Math.round(y / GEN_GRID);
        int gv = (int) Math.round(velocity / VEL_QUANT);
        // Normalise heading to [0, 2π)
        double h = heading % (2 * Math.PI);
        if (h < 0) h += 2 * Math.PI;
        int gh = (int) Math.round(h / HDG_QUANT);
        // Pack into long: gx(16) | gy(16) | gv(8) | gh(16)
        return ((long) (gx & 0xFFFF) << 40)
                | ((long) (gy & 0xFFFF) << 24)
                | ((long) (gv & 0xFF) << 16)
                | (long) (gh & 0xFFFF);
    }

    /**
     * Generate the reachable envelope for a given initial speed and horizon.
     *
     * @param initSpeed initial |velocity| (0–8). Heading is always 0 (north).
     * @param horizon   ticks into the future
     * @param outGrid   grid size for output positions (px)
     * @return list of unique (dx, dy) offsets reachable at the given horizon
     */
    public static List<int[]> generate(int initSpeed, int horizon, int outGrid) {
        // Start at center of large battlefield, heading=0
        double startX = GEN_BF / 2.0;
        double startY = GEN_BF / 2.0;

        // Current frontier: set of (RobotState-like) entries, deduped by quantised key
        // Store as parallel arrays for efficiency
        List<double[]> frontier = new ArrayList<double[]>();
        Set<Long> seen = new HashSet<Long>();

        // Initial state
        double[] init = {startX, startY, 0.0, initSpeed};
        frontier.add(init);
        seen.add(stateKey(0, 0, initSpeed, 0));

        // Expand tick by tick
        for (int t = 0; t < horizon; t++) {
            List<double[]> next = new ArrayList<double[]>();
            Set<Long> nextSeen = new HashSet<Long>();

            for (double[] s : frontier) {
                double sx = s[0], sy = s[1], sh = s[2], sv = s[3];

                for (double accel : ACCELS) {
                    double maxTurn = RobotPhysics.maxTurnRate(sv);
                    for (double frac : TURN_FRACS) {
                        double turnRate = frac * maxTurn;
                        RobotState rs = RobotPhysics.step(
                                new RobotState(sx, sy, sh, sv),
                                accel, turnRate, GEN_BF, GEN_BF);

                        // Relative position for dedup key
                        double dx = rs.x - startX;
                        double dy = rs.y - startY;
                        long key = stateKey(dx, dy, rs.velocity, rs.heading);

                        if (!nextSeen.contains(key)) {
                            nextSeen.add(key);
                            next.add(new double[]{rs.x, rs.y, rs.heading, rs.velocity});
                        }
                    }
                }
            }
            frontier = next;
            seen = nextSeen;
        }

        // Collect unique (dx, dy) positions on the output grid
        Set<Long> posSet = new HashSet<Long>();
        List<int[]> result = new ArrayList<int[]>();

        for (double[] s : frontier) {
            double dx = s[0] - startX;
            double dy = s[1] - startY;
            int gx = (int) Math.round(dx / outGrid);
            int gy = (int) Math.round(dy / outGrid);
            long posKey = ((long) gx << 32) | (gy & 0xFFFFFFFFL);
            if (!posSet.contains(posKey)) {
                posSet.add(posKey);
                result.add(new int[]{gx * outGrid, gy * outGrid});
            }
        }

        return result;
    }

    /**
     * Generate all tables and write as Java source file (EnvelopeData.java).
     * Also writes binary envelope.bin for reference.
     *
     * Usage: java EnvelopeGenerator [output-dir]
     */
    public static void main(String[] args) throws Exception {
        int horizon = 10;
        int outGrid = 2;

        String outDir = args.length > 0 ? args[0]
                : "core/src/main/java/cz/zamboch/autopilot/core/physics";

        // Generate all tables
        List<int[]>[] tables = new List[9];
        for (int v = 0; v <= 8; v++) {
            System.err.println("Generating v=" + v + " ...");
            tables[v] = generate(v, horizon, outGrid);
            System.err.println("  -> " + tables[v].size() + " positions");
        }

        // Write Java source
        java.io.PrintWriter pw = new java.io.PrintWriter(
                new java.io.FileWriter(outDir + "/EnvelopeData.java"));
        pw.println("package cz.zamboch.autopilot.core.physics;");
        pw.println();
        pw.println("/**");
        pw.println(" * Auto-generated envelope tables. DO NOT EDIT.");
        pw.println(" * Generated by {@link EnvelopeGenerator} with horizon="
                + horizon + ", grid=" + outGrid + "px.");
        pw.println(" * Each factory method returns interleaved (dx, dy) byte pairs");
        pw.println(" * relative to heading=0 (north). Indexed by |velocity| 0..8.");
        pw.println(" * Values fit in signed byte (-128..127); max offset is ~80px.");
        pw.println(" *");
        pw.println(" * <p>Each velocity's data is in its own method to stay under the");
        pw.println(" * JVM's 64 KB per-method bytecode limit (total data is ~34 KB).");
        pw.println(" */");
        pw.println("final class EnvelopeData {");
        pw.println("    private EnvelopeData() {}");
        pw.println();

        for (int v = 0; v <= 8; v++) {
            List<int[]> points = tables[v];
            pw.println("    /** " + points.size() + " positions for |v|=" + v + ". */");
            pw.print("    private static byte[] v" + v + "() { return new byte[]{");
            for (int i = 0; i < points.size(); i++) {
                if (i > 0) pw.print(",");
                if (i % 16 == 0) pw.print("\n        ");
                int dx = points.get(i)[0];
                int dy = points.get(i)[1];
                if (dx < -128 || dx > 127 || dy < -128 || dy > 127) {
                    throw new IllegalStateException(
                            "Value out of byte range: v=" + v + " dx=" + dx + " dy=" + dy);
                }
                pw.print(dx + "," + dy);
            }
            pw.println("\n    }; }");
            pw.println();
        }

        pw.println("    static final byte[][] ALL = {v0(),v1(),v2(),v3(),v4(),v5(),v6(),v7(),v8()};");
        pw.println("}");
        pw.close();
        System.err.println("Wrote " + outDir + "/EnvelopeData.java");
    }
}

package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import robocode.control.snapshot.IDebugProperty;
import robocode.control.snapshot.IRobotSnapshot;

import java.util.EnumMap;
import java.util.Map;

/**
 * Validates that a robot's debug properties match the pipeline-computed
 * whiteboard exactly. Both sources observe the same events, so any
 * difference is a pipeline bug. Collects mismatches and prints a summary.
 */
public final class DebugValidator {

    private static final double EPSILON = 1e-4;

    private static final int CHECKS = 0;
    private static final int MISMATCHES = 1;

    private final EnumMap<Feature, int[]> stats = new EnumMap<>(Feature.class);

    // Track mean absolute error for OUR_BREAK_GF (robot-stale vs pipeline-godview)
    private double gfErrorSum = 0;
    private int gfErrorCount = 0;
    // Diagnostic: track NaN state of each side
    private int gfRobotNaN = 0;
    private int gfPipelineNaN = 0;
    private int gfBothNaN = 0;
    private int gfBothSet = 0;

    /**
     * Compare each debug property from the robot snapshot against the
     * corresponding whiteboard feature.
     */
    public void validate(IRobotSnapshot robot, Whiteboard wb) {
        IDebugProperty[] props = robot.getDebugProperties();
        if (props == null || props.length == 0)
            return;

        for (IDebugProperty prop : props) {
            String key = prop.getKey();
            Feature feature;
            try {
                feature = Feature.valueOf(key);
            } catch (IllegalArgumentException e) {
                continue;
            }

            if (feature == Feature.ROUND_RESULT || feature == Feature.ROUND_HIT_RATE
                    || feature == Feature.OPPONENT_ID)
                continue;

            double robotValue = parseValue(prop.getValue());
            double pipelineValue = wb.getFeature(feature);

            // Track GF error diagnostic (before NaN-skip)
            if (feature == Feature.OUR_BREAK_GF) {
                boolean rNaN = Double.isNaN(robotValue);
                boolean pNaN = Double.isNaN(pipelineValue);
                if (rNaN && pNaN) gfBothNaN++;
                else if (rNaN) gfRobotNaN++;
                else if (pNaN) gfPipelineNaN++;
                else {
                    gfBothSet++;
                    gfErrorSum += Math.abs(robotValue - pipelineValue);
                    gfErrorCount++;
                }
            }

            // Both NaN = both unset/no data this tick — nothing to compare
            if (Double.isNaN(robotValue) && Double.isNaN(pipelineValue))
                continue;

            int[] s = stats.computeIfAbsent(feature, k -> new int[2]);
            s[CHECKS]++;

            // One side NaN, other not = definite mismatch (not masked by NaN math)
            boolean mismatch;
            if (Double.isNaN(robotValue) || Double.isNaN(pipelineValue)) {
                mismatch = true;
            } else {
                mismatch = Math.abs(robotValue - pipelineValue) > EPSILON;
            }
            if (mismatch) {
                s[MISMATCHES]++;
            }
        }
    }

    public int getMismatchCount() {
        int total = 0;
        for (int[] s : stats.values())
            total += s[MISMATCHES];
        return total;
    }

    /**
     * Get mismatch count excluding OUR_BREAK_* features (which are expected
     * to differ because robot uses stale scan data vs pipeline god-view).
     */
    public int getNonBreakMismatchCount() {
        int total = 0;
        for (Map.Entry<Feature, int[]> entry : stats.entrySet()) {
            if (entry.getKey().name().startsWith("OUR_BREAK_"))
                continue;
            total += entry.getValue()[MISMATCHES];
        }
        return total;
    }

    /** Mean absolute error between robot and pipeline OUR_BREAK_GF values. */
    public double getMeanAbsoluteGFError() {
        return gfErrorCount > 0 ? gfErrorSum / gfErrorCount : 0.0;
    }

    /** Number of GF comparisons made. */
    public int getGFErrorCount() {
        return gfErrorCount;
    }

    public void printSummary() {
        System.out.println("=== DEBUG VALIDATOR ===");
        System.out.println(String.format("%-32s %10s %10s %10s",
                "Feature", "Checks", "Mismatches", "Accuracy"));
        int totalChecks = 0, totalMismatches = 0;
        for (Map.Entry<Feature, int[]> entry : stats.entrySet()) {
            int checks = entry.getValue()[0];
            int mismatches = entry.getValue()[1];
            totalChecks += checks;
            totalMismatches += mismatches;
            double accuracy = checks > 0 ? 100.0 * (checks - mismatches) / checks : 100.0;
            System.out.println(String.format("%-32s %10d %10d %9.1f%%",
                    entry.getKey().name(), checks, mismatches, accuracy));
        }
        double totalAccuracy = totalChecks > 0
                ? 100.0 * (totalChecks - totalMismatches) / totalChecks
                : 100.0;
        System.out.println(String.format("%-32s %10d %10d %9.1f%%",
                "TOTAL", totalChecks, totalMismatches, totalAccuracy));
        System.out.println(String.format("GF diagnostic: bothNaN=%d robotNaN=%d pipelineNaN=%d bothSet=%d",
                gfBothNaN, gfRobotNaN, gfPipelineNaN, gfBothSet));
        System.out.println("Features seen: " + stats.keySet().size() + " -> " + stats.keySet());
    }

    private static double parseValue(String s) {
        return "NaN".equals(s) ? Double.NaN : Double.parseDouble(s);
    }
}

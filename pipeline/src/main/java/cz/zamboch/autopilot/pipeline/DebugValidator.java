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

            if (feature == Feature.ROUND_RESULT)
                continue;

            double robotValue = parseValue(prop.getValue());
            double pipelineValue = wb.getFeature(feature);

            if (Double.isNaN(robotValue) && Double.isNaN(pipelineValue))
                continue;

            int[] s = stats.computeIfAbsent(feature, k -> new int[2]);
            s[CHECKS]++;

            if (Math.abs(robotValue - pipelineValue) > EPSILON) {
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
    }

    private static double parseValue(String s) {
        return "NaN".equals(s) ? Double.NaN : Double.parseDouble(s);
    }
}

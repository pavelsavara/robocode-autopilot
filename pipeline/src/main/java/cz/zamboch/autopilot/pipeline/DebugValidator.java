package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import robocode.control.snapshot.IDebugProperty;
import robocode.control.snapshot.IRobotSnapshot;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Validates that autopilot debug properties match pipeline-computed whiteboard
 * features.
 * <p>
 * Supports three validation modes per feature:
 * <ul>
 * <li><b>Exact</b> — values must match within a fixed epsilon (default
 * 1e-4)</li>
 * <li><b>Delayed</b> — value may arrive 1–2 ticks late; keeps a short
 * history</li>
 * <li><b>Margin</b> — allows a configurable absolute error tolerance</li>
 * </ul>
 * <p>
 * Features not registered with a specific mode default to exact matching.
 * Call {@link #validate} each tick for the autopilot's robot snapshot.
 */
public final class DebugValidator {

    /** Validation mode for a feature. */
    public enum Mode {
        /** Values must match within epsilon. */
        EXACT,
        /** Value may arrive up to {@code maxDelay} ticks late. */
        DELAYED,
        /** Values must match within a configurable margin. */
        MARGIN
    }

    private static final double DEFAULT_EPSILON = 1e-4;
    private static final int DEFAULT_MAX_DELAY = 2;
    private static final int HISTORY_CAPACITY = 4;

    private final FeatureRule[] rules;
    private int mismatchCount;

    public DebugValidator() {
        rules = new FeatureRule[Feature.values().length];
    }

    // --- Configuration ---

    /** Register a feature for exact validation (default mode). */
    public void exact(Feature feature) {
        rules[feature.ordinal()] = new FeatureRule(Mode.EXACT, DEFAULT_EPSILON, 0);
    }

    /** Register a feature for delayed validation (checks last N ticks). */
    public void delayed(Feature feature, int maxDelay) {
        rules[feature.ordinal()] = new FeatureRule(Mode.DELAYED, DEFAULT_EPSILON, maxDelay);
    }

    /** Register a feature for margin validation with a custom tolerance. */
    public void margin(Feature feature, double tolerance) {
        rules[feature.ordinal()] = new FeatureRule(Mode.MARGIN, tolerance, 0);
    }

    /** Skip validation for a feature entirely. */
    public void skip(Feature feature) {
        rules[feature.ordinal()] = null;
    }

    // --- Validation ---

    /**
     * Validate debug properties from the robot snapshot against the whiteboard.
     * Records pipeline history for delayed checks.
     *
     * @throws AssertionError on exact/margin mismatches (immediate failure)
     */
    public void validate(IRobotSnapshot robot, Whiteboard wb) {
        IDebugProperty[] props = robot.getDebugProperties();
        if (props == null || props.length == 0)
            return;

        long tick = (long) wb.getFeature(Feature.TICK);

        for (IDebugProperty prop : props) {
            String key = prop.getKey();
            Feature feature;
            try {
                feature = Feature.valueOf(key);
            } catch (IllegalArgumentException e) {
                continue;
            }

            // ROUND_RESULT only set at round end, skip mid-round
            if (feature == Feature.ROUND_RESULT)
                continue;

            FeatureRule rule = rules[feature.ordinal()];
            if (rule == null) {
                // Default: exact match
                rule = new FeatureRule(Mode.EXACT, DEFAULT_EPSILON, 0);
            }

            double robotValue = parseValue(prop.getValue());
            double pipelineValue = wb.getFeature(feature);

            switch (rule.mode) {
                case EXACT:
                    checkExact(feature, robotValue, pipelineValue, tick, rule.epsilon);
                    break;
                case MARGIN:
                    checkMargin(feature, robotValue, pipelineValue, tick, rule.epsilon);
                    break;
                case DELAYED:
                    rule.recordPipelineValue(pipelineValue);
                    checkDelayed(feature, robotValue, rule, tick);
                    break;
            }
        }
    }

    /** Number of mismatches detected so far. */
    public int getMismatchCount() {
        return mismatchCount;
    }

    // --- Internal checks ---

    private void checkExact(Feature feature, double robot, double pipeline,
            long tick, double epsilon) {
        if (bothNaN(robot, pipeline))
            return;
        if (Math.abs(robot - pipeline) > epsilon) {
            mismatchCount++;
            throw new AssertionError("MISMATCH tick " + tick
                    + " feature " + feature.name()
                    + ": robot=" + formatValue(robot)
                    + " pipeline=" + formatValue(pipeline));
        }
    }

    private void checkMargin(Feature feature, double robot, double pipeline,
            long tick, double tolerance) {
        if (bothNaN(robot, pipeline))
            return;
        if (Double.isNaN(robot) || Double.isNaN(pipeline))
            return; // one missing = skip
        if (Math.abs(robot - pipeline) > tolerance) {
            mismatchCount++;
            throw new AssertionError("MISMATCH tick " + tick
                    + " feature " + feature.name()
                    + ": robot=" + formatValue(robot)
                    + " pipeline=" + formatValue(pipeline)
                    + " tolerance=" + formatValue(tolerance));
        }
    }

    private void checkDelayed(Feature feature, double robotValue,
            FeatureRule rule, long tick) {
        if (Double.isNaN(robotValue))
            return;

        // Check if any recent pipeline value matches
        for (double v : rule.history) {
            if (!Double.isNaN(v) && Math.abs(robotValue - v) <= rule.epsilon) {
                return; // match found in history
            }
        }

        // Only fail if history is full (we've had enough ticks to see the value)
        if (rule.history.size() > rule.maxDelay) {
            mismatchCount++;
            throw new AssertionError("MISMATCH (delayed) tick " + tick
                    + " feature " + feature.name()
                    + ": robot=" + formatValue(robotValue)
                    + " not found in last " + rule.maxDelay + " pipeline ticks");
        }
    }

    // --- Helpers ---

    private static boolean bothNaN(double a, double b) {
        return Double.isNaN(a) && Double.isNaN(b);
    }

    private static double parseValue(String s) {
        return "NaN".equals(s) ? Double.NaN : Double.parseDouble(s);
    }

    private static String formatValue(double v) {
        if (Double.isNaN(v))
            return "NaN";
        return String.format("%.4f", v);
    }

    // --- Rule storage ---

    private static final class FeatureRule {
        final Mode mode;
        final double epsilon;
        final int maxDelay;
        final Deque<Double> history; // only used for DELAYED mode

        FeatureRule(Mode mode, double epsilon, int maxDelay) {
            this.mode = mode;
            this.epsilon = epsilon;
            this.maxDelay = maxDelay;
            this.history = (mode == Mode.DELAYED)
                    ? new ArrayDeque<>(HISTORY_CAPACITY)
                    : null;
        }

        void recordPipelineValue(double value) {
            if (history == null)
                return;
            if (history.size() >= HISTORY_CAPACITY) {
                history.pollFirst();
            }
            history.addLast(value);
        }
    }
}

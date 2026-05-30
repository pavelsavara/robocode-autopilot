package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import robocode.control.snapshot.IDebugProperty;
import robocode.control.snapshot.IRobotSnapshot;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Layer 0 — IDebugProperty Fidelity (in-game robot vs observer).
 * <p>
 * The foundational fidelity layer. It proves that the observer (a deterministic
 * shadow {@code Autopilot}) received exactly the same partial information the
 * in-game robot had, and therefore computed the exact same state.
 * <p>
 * Each tick, every {@link IDebugProperty} the live {@code Autopilot} published
 * (its full internal feature state) is compared against the observer's
 * robot-side {@link Whiteboard} value for the same feature. Because the robot is
 * 100% deterministic, the match must be exact across <b>all</b> features — no
 * exclusions (waves, {@code GUN_AIM_*}, scores, and breaks all included).
 * <p>
 * This layer is independent of any god-view computation. It reads only the
 * observer's robot-side whiteboard, never a god-view-mutated one.
 */
public final class Layer0DebugFidelityValidator {

    private static final double EPSILON = 1e-4;

    private static final class FeatureStats {
        int checks;
        int mismatches;
    }

    private final EnumMap<Feature, FeatureStats> stats = new EnumMap<>(Feature.class);

    /**
     * Per-wave-column fidelity stats, keyed by the wave column name (the part of
     * a {@code COLUMN/waveId} debug-property key before the slash). Counts both
     * value mismatches and waves present on only one side (live-only / observer-only).
     */
    private final Map<String, FeatureStats> waveStats = new HashMap<>();

    /**
     * Compare debug properties from the live {@code Autopilot} against the
     * observer's robot-side whiteboard. Runs every tick — both sides process the
     * same reconstructed events, so all features must match regardless of scan
     * state. Comparison is gated on the debug properties and whiteboard belonging
     * to the same tick.
     */
    public void validate(IRobotSnapshot liveRobot, Whiteboard observerWb) {
        double tick = observerWb.getFeature(Feature.TICK);
        if (Double.isNaN(tick)) {
            throw new IllegalStateException("TICK must be set before Layer 0 validate is called");
        }

        IDebugProperty[] props = liveRobot.getDebugProperties();
        if (props == null) {
            return;
        }

        // Gate on same tick (handles dead-robot and Robocode timing-offset edges).
        double debugTick = debugValue(props, "TICK");
        if (Double.isNaN(debugTick) || Math.abs(debugTick - tick) > EPSILON) {
            return;
        }

        // Snapshot the observer's in-flight waves as COLUMN/waveId properties so we
        // can match them against the live robot's published wave set by wave id.
        Map<String, String> observerWaves = new HashMap<>();
        observerWb.forEachAliveWaveProperty(observerWaves::put);
        // Also snapshot waves the observer resolved this tick (RES_* keys): the only
        // validation of the virtual waves' break geometry, which is invisible to the
        // alive-wave path because a resolved wave has already left the alive set.
        observerWb.forEachJustResolvedWaveBreak(observerWaves::put);

        for (IDebugProperty prop : props) {
            String key = prop.getKey();

            // Wave properties use a COLUMN/waveId key; validate them separately and
            // consume the matching observer entry.
            if (key.indexOf('/') >= 0) {
                validateWaveProperty(key, prop.getValue(), observerWaves);
                continue;
            }

            Feature feature;
            try {
                feature = Feature.valueOf(key);
            } catch (IllegalArgumentException e) {
                continue; // unknown feature name — skip
            }

            // String feature (OPPONENT_ID) is published but not numerically compared;
            // its numeric derivative OPPONENT_ID_HASH is compared instead.
            if (feature == Feature.OPPONENT_ID) {
                continue;
            }

            double debug;
            try {
                debug = "NaN".equals(prop.getValue()) ? Double.NaN : Double.parseDouble(prop.getValue());
            } catch (NumberFormatException e) {
                continue; // non-numeric — skip
            }

            double wbValue = observerWb.getFeature(feature);

            FeatureStats s = stats.computeIfAbsent(feature, k -> new FeatureStats());
            s.checks++;

            if (Double.isNaN(debug) && Double.isNaN(wbValue)) {
                continue; // both NaN = match
            }

            if (Double.isNaN(debug) || Double.isNaN(wbValue)) {
                s.mismatches++;
                continue;
            }

            if (Math.abs(debug - wbValue) > EPSILON) {
                s.mismatches++;
            }
        }

        // Any observer wave property left unconsumed = a wave alive on the observer
        // but not on the live robot (e.g. it broke a tick later on the observer).
        for (Map.Entry<String, String> e : observerWaves.entrySet()) {
            FeatureStats s = waveStats.computeIfAbsent(waveColumn(e.getKey()), k -> new FeatureStats());
            s.checks++;
            s.mismatches++;
        }
    }

    /**
     * Compare one live wave property ({@code COLUMN/waveId}) against the matching
     * observer wave property, consuming it from {@code observerWaves}. A wave id
     * present on only one side is a mismatch (wave lifetime / break-tick drift).
     */
    private void validateWaveProperty(String key, String liveValueStr,
            Map<String, String> observerWaves) {
        FeatureStats s = waveStats.computeIfAbsent(waveColumn(key), k -> new FeatureStats());
        s.checks++;

        String obsValueStr = observerWaves.remove(key);
        if (obsValueStr == null) {
            s.mismatches++;
            return;
        }

        double debug = parseDebug(liveValueStr);
        double wbValue = parseDebug(obsValueStr);

        if (Double.isNaN(debug) && Double.isNaN(wbValue)) {
            return; // both NaN = match
        }

        if (Double.isNaN(debug) || Double.isNaN(wbValue)) {
            s.mismatches++;
            return;
        }

        if (Math.abs(debug - wbValue) > EPSILON) {
            s.mismatches++;
        }
    }

    /** Column name portion of a {@code COLUMN/waveId} wave-property key. */
    private static String waveColumn(String key) {
        int slash = key.indexOf('/');
        return slash >= 0 ? key.substring(0, slash) : key;
    }

    /** Parse a debug-property string value to double, mapping {@code "NaN"} to NaN. */
    private static double parseDebug(String value) {
        if (value == null || "NaN".equals(value)) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static double debugValue(IDebugProperty[] props, String key) {
        for (IDebugProperty prop : props) {
            if (key.equals(prop.getKey())) {
                try {
                    return "NaN".equals(prop.getValue()) ? Double.NaN : Double.parseDouble(prop.getValue());
                } catch (NumberFormatException e) {
                    return Double.NaN;
                }
            }
        }
        return Double.NaN;
    }

    // ========== Getters ==========

    /** Total mismatches across all features. */
    public int getMismatches() {
        int total = 0;
        for (FeatureStats s : stats.values()) {
            total += s.mismatches;
        }
        for (FeatureStats s : waveStats.values()) {
            total += s.mismatches;
        }
        return total;
    }

    /** Total comparisons performed across all features. */
    public int getChecks() {
        int total = 0;
        for (FeatureStats s : stats.values()) {
            total += s.checks;
        }
        for (FeatureStats s : waveStats.values()) {
            total += s.checks;
        }
        return total;
    }

    public int getMismatches(Feature feature) {
        FeatureStats s = stats.get(feature);
        return s != null ? s.mismatches : 0;
    }

    public int getChecks(Feature feature) {
        FeatureStats s = stats.get(feature);
        return s != null ? s.checks : 0;
    }

    /**
     * Non-vacuous guard: at least one feature must have been compared.
     *
     * @throws IllegalStateException if no comparisons were performed
     */
    public void assertNonVacuous() {
        if (getChecks() == 0) {
            throw new IllegalStateException("Layer 0 vacuous: 0 debug-property comparisons performed");
        }
    }

    public void printSummary() {
        System.out.println("=== Layer 0 — IDebugProperty Fidelity ===");
        System.out.printf("  Checks: %d, Mismatches: %d%n", getChecks(), getMismatches());
        for (var entry : stats.entrySet()) {
            if (entry.getValue().mismatches > 0) {
                System.out.printf("    %s: checks=%d, mismatches=%d%n",
                        entry.getKey(), entry.getValue().checks, entry.getValue().mismatches);
            }
        }
        for (var entry : waveStats.entrySet()) {
            if (entry.getValue().mismatches > 0) {
                System.out.printf("    wave %s: checks=%d, mismatches=%d%n",
                        entry.getKey(), entry.getValue().checks, entry.getValue().mismatches);
            }
        }
        System.out.println("=========================================");
    }
}

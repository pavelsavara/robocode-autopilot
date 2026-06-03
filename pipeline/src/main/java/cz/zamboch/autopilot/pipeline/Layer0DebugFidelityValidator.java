package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import robocode.control.snapshot.IDebugProperty;
import robocode.control.snapshot.IRobotSnapshot;

import java.util.EnumMap;
import java.util.EnumSet;
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
 * robot-side {@link Whiteboard} value for the same feature. Raw mismatches are
 * always counted and reported across <b>all</b> features (waves,
 * {@code GUN_AIM_*}, scores, and breaks all included). The assertion-facing
 * {@link #getUnexpectedMismatches()} only waives a tick when that same tick has
 * the known snapshot-pure scan timing ambiguity and no unrelated or wave drift.
 * <p>
 * This layer is independent of any god-view computation. It reads only the
 * observer's robot-side whiteboard, never a god-view-mutated one.
 */
public final class Layer0DebugFidelityValidator {

    private static final double EPSILON = 1e-4;
    private static final EnumSet<Feature> SCAN_UNCERTAIN_FEATURES = EnumSet.of(
            Feature.DISTANCE,
            Feature.BEARING_RADIANS,
            Feature.OPPONENT_HEADING,
            Feature.OPPONENT_VELOCITY,
            Feature.OPPONENT_ENERGY,
            Feature.SCAN_TICK,
            Feature.OUR_BULLET_DAMAGE_TO_OPPONENT,
            Feature.OPPONENT_BULLET_ENERGY_GAIN,
            Feature.RAM_DAMAGE_TO_OPPONENT,
            Feature.OPPONENT_WALL_HIT_DAMAGE,
            Feature.OPPONENT_BEARING_ABSOLUTE,
            Feature.OPPONENT_X,
            Feature.OPPONENT_Y,
            Feature.OPPONENT_LATERAL_VELOCITY,
            Feature.OPPONENT_ADVANCING_VELOCITY,
            Feature.GUN_AIM_POWER,
            Feature.GUN_AIM_ANGLE,
            Feature.GUN_AIM_GF,
            Feature.TICKS_SINCE_SCAN);

    private static final EnumSet<Feature> SCAN_TIMING_DERIVED_FEATURES = EnumSet.of(
            Feature.OUR_AIM_X,
            Feature.OUR_AIM_Y,
            Feature.OUR_AIM_DISTANCE,
            Feature.OUR_AIM_BEARING_ABSOLUTE,
            Feature.THEIR_AIM_OUR_X,
            Feature.THEIR_AIM_OUR_Y,
            Feature.THEIR_AIM_DISTANCE,
            Feature.THEIR_AIM_BEARING,
            Feature.OUR_BREAK_TICK,
            Feature.OUR_BREAK_OPPONENT_X,
            Feature.OUR_BREAK_OPPONENT_Y,
            Feature.OUR_BREAK_GF,
            Feature.OUR_BREAK_BEARING_OFFSET,
            Feature.THEIR_BREAK_TICK,
            Feature.THEIR_BREAK_OUR_X,
            Feature.THEIR_BREAK_OUR_Y,
            Feature.THEIR_BREAK_GF,
            Feature.THEIR_BREAK_BEARING_OFFSET);

    private static final class FeatureStats {
        int checks;
        int mismatches;
    }

    private final EnumMap<Feature, FeatureStats> stats = new EnumMap<>(Feature.class);
    private int unexpectedMismatches;
    private int waivedMismatches;

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

        int tickFeatureMismatches = 0;
        int tickScanFeatureMismatches = 0;
        int tickScanTimingDerivedMismatches = 0;
        int tickUnexpectedFeatureMismatches = 0;
        int tickWaveMismatches = 0;
        boolean tickHasScanTimingSignal = false;

        for (IDebugProperty prop : props) {
            String key = prop.getKey();

            // Wave properties use a COLUMN/waveId key; validate them separately and
            // consume the matching observer entry.
            if (key.indexOf('/') >= 0) {
                tickWaveMismatches += validateWaveProperty(key, prop.getValue(), observerWaves);
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

            if (valuesMismatch(debug, wbValue)) {
                s.mismatches++;
                tickFeatureMismatches++;
                if (SCAN_UNCERTAIN_FEATURES.contains(feature)) {
                    tickScanFeatureMismatches++;
                    tickHasScanTimingSignal |= feature == Feature.SCAN_TICK || feature == Feature.TICKS_SINCE_SCAN;
                } else if (SCAN_TIMING_DERIVED_FEATURES.contains(feature)) {
                    tickScanTimingDerivedMismatches++;
                } else {
                    tickUnexpectedFeatureMismatches++;
                }
            }
        }

        // Any observer wave property left unconsumed = a wave alive on the observer
        // but not on the live robot (e.g. it broke a tick later on the observer).
        for (Map.Entry<String, String> e : observerWaves.entrySet()) {
            FeatureStats s = waveStats.computeIfAbsent(waveColumn(e.getKey()), k -> new FeatureStats());
            s.checks++;
            s.mismatches++;
            tickWaveMismatches++;
        }

        int tickMismatches = tickFeatureMismatches + tickWaveMismatches;
        if (tickMismatches == 0) {
            return;
        }

        boolean scanTimingOnly = tickWaveMismatches == 0
                && tickUnexpectedFeatureMismatches == 0
                && tickScanFeatureMismatches > 0
                && tickHasScanTimingSignal
                && tickScanFeatureMismatches + tickScanTimingDerivedMismatches == tickFeatureMismatches;
        if (scanTimingOnly) {
            waivedMismatches += tickMismatches;
        } else {
            unexpectedMismatches += tickMismatches;
        }
    }

    /**
     * Compare one live wave property ({@code COLUMN/waveId}) against the matching
     * observer wave property, consuming it from {@code observerWaves}. A wave id
     * present on only one side is a mismatch (wave lifetime / break-tick drift).
     */
    private int validateWaveProperty(String key, String liveValueStr,
            Map<String, String> observerWaves) {
        FeatureStats s = waveStats.computeIfAbsent(waveColumn(key), k -> new FeatureStats());
        s.checks++;

        String obsValueStr = observerWaves.remove(key);
        if (obsValueStr == null) {
            s.mismatches++;
            return 1;
        }

        double debug = parseDebug(liveValueStr);
        double wbValue = parseDebug(obsValueStr);

        if (valuesMismatch(debug, wbValue)) {
            s.mismatches++;
            return 1;
        }
        return 0;
    }

    private static boolean valuesMismatch(double debug, double wbValue) {
        if (Double.isNaN(debug) && Double.isNaN(wbValue)) {
            return false;
        }

        if (Double.isNaN(debug) || Double.isNaN(wbValue)) {
            return true;
        }

        return Math.abs(debug - wbValue) > EPSILON;
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

    /**
    * Mismatches that should fail Layer 0 after removing only the known
    * snapshot-pure ambiguity where scan delivery timing is not recoverable from
    * snapshots. Waivers are classified per validation tick, not by aggregate
    * feature totals.
     */
    public int getUnexpectedMismatches() {
        return unexpectedMismatches;
    }

    public int getWaivedMismatches() {
        return waivedMismatches;
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
        if (unexpectedMismatches > 0 || waivedMismatches > 0) {
            System.out.printf("  Unexpected mismatches: %d, waived scan-timing mismatches: %d%n",
                unexpectedMismatches, waivedMismatches);
        }
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

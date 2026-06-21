package cz.zamboch.autopilot.pipeline;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for how every {@code OUR_WAVES} wave column is treated
 * when reconciling the two outgoing-wave producers — the core observer shadow
 * ({@code autopilot-waves.csv}) and the pure DeJaVu reconstruction
 * ({@code dejavu-waves.csv}).
 * <p>
 * Every column is either <b>compared</b> (must agree to floating precision across
 * producers) or <b>excluded</b> (with a stated reason). Two tests enforce this:
 * <ul>
 * <li>{@link WaveReconciliationTest} (fast, runs in the normal {@code test} task)
 * fails the build when a new {@code OUR_WAVES} feature is added without being
 * classified here — no more silent gaps.</li>
 * <li>{@link BattleCSVProducer#godViewAndDejavuWavesReconcile} (integration) runs a
 * real battle and asserts every <em>compared</em> column actually reconciles.</li>
 * </ul>
 * Adding a wave feature therefore forces a conscious choice: compare it (and make
 * both producers populate it consistently) or exclude it with a justification.
 */
final class WaveReconciliation {

    private WaveReconciliation() {
    }

    /** Reconstructable columns compared to floating precision. */
    static final List<String> EXACT_COLS = List.of(
            "our_fire_distance", "our_fire_x", "our_fire_y",
            "our_fire_opponent_x", "our_fire_opponent_y",
            "our_fire_lateral_velocity", "our_fire_advancing_velocity", "our_fire_direction",
            "our_aim_x", "our_aim_y", "our_aim_opponent_x", "our_aim_opponent_y",
            "our_aim_distance", "our_aim_lateral_velocity", "our_aim_lag1_gf");

    /** Reconstructable angle columns compared modulo 2*pi. */
    static final List<String> ANGLE_COLS = List.of(
            "our_fire_bearing_absolute", "our_aim_bearing_absolute");

    /** Break-time reconstructable columns (compared only when both sides resolved). */
    static final List<String> BREAK_EXACT_COLS = List.of(
            "our_break_gf", "our_break_opponent_x", "our_break_opponent_y", "our_break_hit");

    /** Break-time angle columns. */
    static final List<String> BREAK_ANGLE_COLS = List.of("our_break_bearing_offset");

    /** Power-agreement gate: mea = f(power); waves where it disagrees are not a fair comparison. */
    static final String GATE_COL = "our_fire_mea";

    /** Columns deliberately not cross-compared, each mapped to the reason why. */
    static final Map<String, String> EXCLUDED_WITH_REASON;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("our_fire_tick", "wave match key");
        m.put("our_fire_bullet_id", "producer bullet-id spaces differ");
        m.put("our_fire_aim_gf", "decision: model-predicted (observer) vs realized (dejavu)");
        m.put("our_fire_is_real", "row filter (always 1.0 for compared rows)");
        m.put("our_fire_bullet_speed", "determined by power; covered by the our_fire_mea gate");
        m.put("our_fire_power", "decision; covered by the our_fire_mea gate");
        m.put("our_break_tick", "resolution timing differs by up to a tick at round boundaries");
        EXCLUDED_WITH_REASON = m;
    }

    /** Every column that has been consciously classified (compared, gate, or excluded). */
    static Set<String> classified() {
        Set<String> s = new HashSet<>();
        s.addAll(EXACT_COLS);
        s.addAll(ANGLE_COLS);
        s.addAll(BREAK_EXACT_COLS);
        s.addAll(BREAK_ANGLE_COLS);
        s.add(GATE_COL);
        s.addAll(EXCLUDED_WITH_REASON.keySet());
        return s;
    }

    /** Lower-cased CSV header name of a wave feature. */
    static boolean isWaveColumn(String header) {
        return header.startsWith("our_");
    }

    static List<String> all(String... cols) {
        return Arrays.asList(cols);
    }
}

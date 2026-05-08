package cz.zamboch.autopilot.core.ml;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps CSV column names (used during Python training) to {@link Feature} enum values
 * (used in the Java robot). This is the bridge between the offline ML pipeline and
 * the in-game feature system.
 *
 * <p>Also provides a helper to extract a feature vector from Whiteboard in the order
 * expected by a trained model.</p>
 */
public final class FeatureMapping {

    private static final Map<String, Feature> CSV_TO_FEATURE = new HashMap<String, Feature>();

    static {
        // Spatial
        put("distance", Feature.DISTANCE);
        put("bearing_to_opponent_abs", Feature.BEARING_TO_OPPONENT_ABS);
        put("opponent_dist_to_wall_min", Feature.OPPONENT_DIST_TO_WALL_MIN);
        put("distance_norm", Feature.DISTANCE_NORM);

        // Position
        put("our_x", Feature.OUR_X);
        put("our_y", Feature.OUR_Y);
        put("our_heading", Feature.OUR_HEADING);
        put("our_velocity", Feature.OUR_VELOCITY);
        put("opponent_x", Feature.OPPONENT_X);
        put("opponent_y", Feature.OPPONENT_Y);
        put("opponent_heading", Feature.OPPONENT_HEADING);

        // Movement
        put("opponent_velocity", Feature.OPPONENT_VELOCITY);
        put("opponent_lateral_velocity", Feature.OPPONENT_LATERAL_VELOCITY);
        put("opponent_advancing_velocity", Feature.OPPONENT_ADVANCING_VELOCITY);
        put("opponent_heading_delta", Feature.OPPONENT_HEADING_DELTA);
        put("opponent_lateral_direction", Feature.OPPONENT_LATERAL_DIRECTION);
        put("opponent_velocity_delta", Feature.OPPONENT_VELOCITY_DELTA);
        put("opponent_is_decelerating", Feature.OPPONENT_IS_DECELERATING);
        put("opponent_time_since_direction_change", Feature.OPPONENT_TIME_SINCE_DIRECTION_CHANGE);
        put("opponent_angular_velocity", Feature.OPPONENT_ANGULAR_VELOCITY);
        put("opponent_max_turn_rate", Feature.OPPONENT_MAX_TURN_RATE);
        put("opponent_guess_factor", Feature.OPPONENT_GUESS_FACTOR);
        put("opponent_avg_lateral_velocity_10", Feature.OPPONENT_AVG_LATERAL_VELOCITY_10);
        put("opponent_avg_lateral_velocity_30", Feature.OPPONENT_AVG_LATERAL_VELOCITY_30);
        put("opponent_heading_delta_variability_10", Feature.OPPONENT_HEADING_DELTA_VARIABILITY_10);
        put("opponent_velocity_variability_10", Feature.OPPONENT_VELOCITY_VARIABILITY_10);
        put("opponent_time_since_velocity_change", Feature.OPPONENT_TIME_SINCE_VELOCITY_CHANGE);
        put("opponent_distance_since_direction_change", Feature.OPPONENT_DISTANCE_SINCE_DIRECTION_CHANGE);

        // Energy
        put("opponent_energy", Feature.OPPONENT_ENERGY);
        put("energy_ratio", Feature.ENERGY_RATIO);

        // State normalisation
        put("our_lateral_velocity", Feature.OUR_LATERAL_VELOCITY);
        put("our_dist_to_wall_min", Feature.OUR_DIST_TO_WALL_MIN);

        // Timing
        put("our_gun_heat", Feature.OUR_GUN_HEAT);
        put("ticks_since_scan", Feature.TICKS_SINCE_SCAN);

        // Targeting
        put("our_bullet_speed", Feature.OUR_BULLET_SPEED);
        put("our_bullet_travel_time", Feature.OUR_BULLET_TRAVEL_TIME);
        put("mea_for_our_bullet", Feature.MEA_FOR_OUR_BULLET);
        put("ticks_since_we_fired", Feature.TICKS_SINCE_WE_FIRED);
        put("our_wave_distance", Feature.OUR_WAVE_DISTANCE);
        put("our_wave_remaining", Feature.OUR_WAVE_REMAINING);
        put("linear_target_angle", Feature.LINEAR_TARGET_ANGLE);
        put("linear_target_offset", Feature.LINEAR_TARGET_OFFSET);
        put("circular_target_angle", Feature.CIRCULAR_TARGET_ANGLE);
        put("circular_target_offset", Feature.CIRCULAR_TARGET_OFFSET);
        put("gf_bearing_offset", Feature.GF_BEARING_OFFSET);
        put("gf_current_at_power_1", Feature.GF_CURRENT_AT_POWER_1);
        put("gf_current_at_power_1_5", Feature.GF_CURRENT_AT_POWER_1_5);
        put("gf_current_at_power_2", Feature.GF_CURRENT_AT_POWER_2);

        // Opponent prediction
        put("opponent_wall_ahead_distance", Feature.OPPONENT_WALL_AHEAD_DISTANCE);
        put("opponent_inferred_gun_heat", Feature.OPPONENT_INFERRED_GUN_HEAT);

        // Wave/timing derived
        put("opponent_bullet_speed", Feature.OPPONENT_BULLET_SPEED);
        put("mea_for_opponent_bullet", Feature.MEA_FOR_OPPONENT_BULLET);
        put("ticks_since_opponent_fired", Feature.TICKS_SINCE_OPPONENT_FIRED);
        put("opponent_wave_distance", Feature.OPPONENT_WAVE_DISTANCE);
        put("opponent_wave_remaining", Feature.OPPONENT_WAVE_REMAINING);
        put("opponent_wave_eta", Feature.OPPONENT_WAVE_ETA);
        put("escape_angle_coverage", Feature.ESCAPE_ANGLE_COVERAGE);

        // Multi-wave
        put("n_opponent_waves_in_flight", Feature.N_OPPONENT_WAVES_IN_FLIGHT);
        put("n_our_waves_in_flight", Feature.N_OUR_WAVES_IN_FLIGHT);
        put("nearest_opponent_wave_gap", Feature.NEAREST_OPPONENT_WAVE_GAP);
        put("total_opponent_wave_damage", Feature.TOTAL_OPPONENT_WAVE_DAMAGE);
        put("nearest_our_wave_gap", Feature.NEAREST_OUR_WAVE_GAP);

        // Envelope
        put("envelope_fill_ratio", Feature.ENVELOPE_FILL_RATIO);
        put("reachable_distance_min", Feature.REACHABLE_DISTANCE_MIN);
        put("reachable_distance_max", Feature.REACHABLE_DISTANCE_MAX);
        put("reachable_gf_range", Feature.REACHABLE_GF_RANGE);

        // Battlefield geometry
        put("opponent_center_distance", Feature.OPPONENT_CENTER_DISTANCE);
        put("opponent_corner_proximity", Feature.OPPONENT_CORNER_PROXIMITY);

        // Scan coverage
        put("ticks_between_scans", Feature.TICKS_BETWEEN_SCANS);
        put("scan_arc_width", Feature.SCAN_ARC_WIDTH);
        put("radar_locked", Feature.RADAR_LOCKED);
        put("radar_turn_direction", Feature.RADAR_TURN_DIRECTION);

        // Combat progress
        put("cumulative_damage_dealt", Feature.CUMULATIVE_DAMAGE_DEALT);
        put("cumulative_damage_received", Feature.CUMULATIVE_DAMAGE_RECEIVED);
        put("cumulative_our_hit_rate", Feature.CUMULATIVE_OUR_HIT_RATE);
        put("cumulative_opponent_hit_rate", Feature.CUMULATIVE_OPPONENT_HIT_RATE);
        put("cumulative_our_shots_fired", Feature.CUMULATIVE_OUR_SHOTS_FIRED);
        put("cumulative_opponent_shots_detected", Feature.CUMULATIVE_OPPONENT_SHOTS_DETECTED);

        // Window features (20-tick sliding stats)
        put("distance_wmean", Feature.DISTANCE_WMEAN);
        put("distance_wstd", Feature.DISTANCE_WSTD);
        put("bearing_to_opponent_abs_wmean", Feature.BEARING_TO_OPPONENT_ABS_WMEAN);
        put("bearing_to_opponent_abs_wstd", Feature.BEARING_TO_OPPONENT_ABS_WSTD);
        put("opponent_dist_to_wall_min_wmean", Feature.OPPONENT_DIST_TO_WALL_MIN_WMEAN);
        put("opponent_dist_to_wall_min_wstd", Feature.OPPONENT_DIST_TO_WALL_MIN_WSTD);
        put("our_gun_heat_wmean", Feature.OUR_GUN_HEAT_WMEAN);
        put("our_gun_heat_wstd", Feature.OUR_GUN_HEAT_WSTD);
        put("ticks_since_scan_wmean", Feature.TICKS_SINCE_SCAN_WMEAN);
        put("ticks_since_scan_wstd", Feature.TICKS_SINCE_SCAN_WSTD);
        put("opponent_energy_wmean", Feature.OPPONENT_ENERGY_WMEAN);
        put("opponent_energy_wstd", Feature.OPPONENT_ENERGY_WSTD);
        put("our_x_wmean", Feature.OUR_X_WMEAN);
        put("our_x_wstd", Feature.OUR_X_WSTD);
        put("our_y_wmean", Feature.OUR_Y_WMEAN);
        put("our_y_wstd", Feature.OUR_Y_WSTD);
        put("our_heading_wmean", Feature.OUR_HEADING_WMEAN);
        put("our_heading_wstd", Feature.OUR_HEADING_WSTD);
        put("our_velocity_wmean", Feature.OUR_VELOCITY_WMEAN);
        put("our_velocity_wstd", Feature.OUR_VELOCITY_WSTD);

        // Opponent profile
        put("opponent_strength_rating", Feature.OPPONENT_STRENGTH_RATING);
        put("our_position_advantage", Feature.OUR_POSITION_ADVANTAGE);
        put("opponent_position_advantage", Feature.OPPONENT_POSITION_ADVANTAGE);
    }

    private static void put(String csvName, Feature feature) {
        CSV_TO_FEATURE.put(csvName, feature);
    }

    /**
     * Build a Feature[] index from CSV column names.
     * Entry i = the Feature enum value for the i-th model input.
     * Returns null at position i if the CSV name has no Feature mapping.
     */
    public static Feature[] buildIndex(String[] csvNames) {
        Feature[] index = new Feature[csvNames.length];
        for (int i = 0; i < csvNames.length; i++) {
            index[i] = CSV_TO_FEATURE.get(csvNames[i]);
        }
        return index;
    }

    /**
     * Extract a feature vector from Whiteboard in the order specified by featureIndex.
     * Missing features (null in index or not set in Whiteboard) default to NaN.
     *
     * @param wb           current whiteboard state
     * @param featureIndex Feature[] built by {@link #buildIndex}
     * @param out          pre-allocated output array (length = featureIndex.length)
     */
    public static void extract(Whiteboard wb, Feature[] featureIndex, double[] out) {
        for (int i = 0; i < featureIndex.length; i++) {
            Feature f = featureIndex[i];
            if (f != null && wb.hasFeature(f)) {
                out[i] = wb.getFeature(f);
            } else {
                out[i] = Double.NaN;
            }
        }
    }
}

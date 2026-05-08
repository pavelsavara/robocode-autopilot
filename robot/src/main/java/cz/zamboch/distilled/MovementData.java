package cz.zamboch.distilled;

import cz.zamboch.autopilot.core.ml.GbmTreeEnsemble;

import java.io.DataInputStream;
import java.io.InputStream;

/**
 * Auto-generated data loader for the movement GBM model.
 * Loads variable-length tree arrays from the binary resource file.
 *
 * <p>Model: 200 trees, 24,438 total nodes, 1 class(es),
 * 76 input features. Base score: -0.01561521.</p>
 */
public final class MovementData {

    private MovementData() {}

    /** Feature column names in model input order. */
    public static final String[] FEATURE_NAMES = {
        "distance", "bearing_to_opponent_abs", "opponent_dist_to_wall_min", "our_gun_heat", "ticks_since_scan", "opponent_energy", "our_x", "our_y", "our_heading", "our_velocity", "opponent_x", "opponent_y", "opponent_heading", "cumulative_damage_dealt", "cumulative_damage_received", "cumulative_our_hit_rate", "cumulative_opponent_hit_rate", "cumulative_our_shots_fired", "cumulative_opponent_shots_detected", "opponent_center_distance", "opponent_corner_proximity", "opponent_velocity", "opponent_advancing_velocity", "opponent_heading_delta", "energy_ratio", "our_lateral_velocity", "our_dist_to_wall_min", "our_bullet_speed", "our_bullet_travel_time", "mea_for_our_bullet", "ticks_since_we_fired", "opponent_wall_ahead_distance", "n_our_waves_in_flight", "nearest_our_wave_gap", "envelope_fill_ratio", "reachable_distance_min", "reachable_distance_max", "reachable_gf_range", "opponent_velocity_delta", "opponent_is_decelerating", "opponent_time_since_direction_change", "opponent_angular_velocity", "opponent_max_turn_rate", "distance_norm", "linear_target_angle", "linear_target_offset", "circular_target_angle", "circular_target_offset", "gf_bearing_offset", "gf_current_at_power_1", "gf_current_at_power_1_5", "gf_current_at_power_2", "opponent_heading_delta_variability_10", "opponent_velocity_variability_10", "opponent_time_since_velocity_change", "opponent_distance_since_direction_change", "distance_wmean", "distance_wstd", "bearing_to_opponent_abs_wmean", "bearing_to_opponent_abs_wstd", "opponent_dist_to_wall_min_wmean", "opponent_dist_to_wall_min_wstd", "our_gun_heat_wmean", "our_gun_heat_wstd", "ticks_since_scan_wmean", "ticks_since_scan_wstd", "opponent_energy_wmean", "opponent_energy_wstd", "our_x_wmean", "our_x_wstd", "our_y_wmean", "our_y_wstd", "our_heading_wmean", "our_heading_wstd", "our_velocity_wmean", "our_velocity_wstd"
    };

    /** Number of trees. */
    public static final int N_TREES = 200;

    /** Base score (global bias). */
    public static final double BASE_SCORE = -0.01561521;

    /** Number of output classes (1 = regression/binary). */
    public static final int N_CLASSES = 1;

    /**
     * Load the tree ensemble from the embedded binary resource.
     * Called once at robot startup.
     *
     * <p>Binary format: variable-length trees, each preceded by its node count.
     * Trees are flattened into a single contiguous array with an offset table.</p>
     */
    public static GbmTreeEnsemble load() {
        try {
            InputStream is = MovementData.class.getResourceAsStream(
                    "/movement.bin");
            if (is == null) {
                throw new RuntimeException("movement.bin not found in JAR");
            }
            DataInputStream dis = new DataInputStream(is);

            int nTrees = dis.readInt();
            float baseScoreF = dis.readFloat();
            double baseScore = baseScoreF;
            int nClasses = dis.readShort();

            int[] offsets = new int[nTrees];
            int totalNodes = 0;

            // Compact format: int16 features/children, float32 thresholds/leaves
            int[][] allFeat = new int[nTrees][];
            float[][] allThresh = new float[nTrees][];
            int[][] allLeft = new int[nTrees][];
            int[][] allRight = new int[nTrees][];
            float[][] allLeaf = new float[nTrees][];

            for (int t = 0; t < nTrees; t++) {
                int n = dis.readShort();
                offsets[t] = totalNodes;
                totalNodes += n;

                allFeat[t] = new int[n];
                allThresh[t] = new float[n];
                allLeft[t] = new int[n];
                allRight[t] = new int[n];
                allLeaf[t] = new float[n];

                for (int i = 0; i < n; i++) allFeat[t][i] = dis.readShort();
                for (int i = 0; i < n; i++) allThresh[t][i] = dis.readFloat();
                for (int i = 0; i < n; i++) allLeft[t][i] = dis.readShort();
                for (int i = 0; i < n; i++) allRight[t][i] = dis.readShort();
                for (int i = 0; i < n; i++) allLeaf[t][i] = dis.readFloat();
            }
            dis.close();

            // Widen to double arrays for GbmTreeEnsemble
            int[] featureIndex = new int[totalNodes];
            double[] threshold = new double[totalNodes];
            int[] leftChild = new int[totalNodes];
            int[] rightChild = new int[totalNodes];
            double[] leafValue = new double[totalNodes];

            for (int t = 0; t < nTrees; t++) {
                int off = offsets[t];
                int n = allFeat[t].length;
                for (int i = 0; i < n; i++) {
                    featureIndex[off + i] = allFeat[t][i];
                    threshold[off + i] = allThresh[t][i];
                    leftChild[off + i] = allLeft[t][i];
                    rightChild[off + i] = allRight[t][i];
                    leafValue[off + i] = allLeaf[t][i];
                }
            }

            return new GbmTreeEnsemble(nTrees, offsets,
                    featureIndex, threshold, leftChild, rightChild,
                    leafValue, baseScore, nClasses);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load movement model", e);
        }
    }
}

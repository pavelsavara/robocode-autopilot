package cz.zamboch.trivial;

/**
 * Offline-generated opponent profile lookup table.
 * Stub implementation — returns neutral defaults until nb15 generates real data.
 *
 * <p>Will be replaced by auto-generated code from notebook 15
 * containing ~200 opponent entries with strength ratings and
 * position heatmaps.</p>
 */
public final class OpponentProfileData {

    private OpponentProfileData() {}

    /** Number of cells per axis in the position heatmap (20px grid). */
    public static final int HEATMAP_CELLS = 40; // 800px / 20px

    /**
     * Get the overall strength rating for an opponent.
     *
     * @param botIdHash FNV-1a hash of the opponent's bot ID (part before first space)
     * @return strength in [0,1]: 0 = weakest, 1 = strongest, 0.5 = unknown
     */
    public static double getStrengthRating(int botIdHash) {
        // Stub: return neutral for all opponents
        return 0.5;
    }

    /**
     * Get position advantage at a cell from the per-opponent heatmap.
     *
     * @param botIdHash FNV-1a hash of the opponent's bot ID
     * @param cellX grid cell X index (0-based)
     * @param cellY grid cell Y index (0-based)
     * @return advantage in [-1,1]: positive = favourable, 0 = neutral/unknown
     */
    public static double getPositionAdvantage(int botIdHash, int cellX, int cellY) {
        // Stub: all positions neutral
        return 0.0;
    }

    /** Convert battlefield coordinate to heatmap cell index. */
    public static int toCell(double coord, int bfSize) {
        int cell = (int) (coord * HEATMAP_CELLS / bfSize);
        return Math.max(0, Math.min(HEATMAP_CELLS - 1, cell));
    }
}

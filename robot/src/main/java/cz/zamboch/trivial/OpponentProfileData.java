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
}

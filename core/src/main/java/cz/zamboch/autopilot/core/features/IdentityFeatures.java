package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Computes a stable numeric hash of the opponent's name for use as
 * a segmentation / fingerprint key in ML models.
 * Uses FNV-1a (32-bit) for good distribution and determinism.
 * No dependencies on other features.
 */
public class IdentityFeatures implements IInGameFeatures {

    private static final Feature[] OUTPUTS = { Feature.OPPONENT_NAME_HASH };
    private static final Feature[] DEPS = {};

    // FNV-1a 32-bit constants
    private static final int FNV_OFFSET_BASIS = 0x811c9dc5;
    private static final int FNV_PRIME = 0x01000193;

    public Feature[] getOutputFeatures() {
        return OUTPUTS;
    }

    public Feature[] getDependencies() {
        return DEPS;
    }

    public void process(Whiteboard wb) {
        String name = wb.getOpponentName();
        if (name != null) {
            wb.setFeature(Feature.OPPONENT_NAME_HASH, fnv1a32(name));
        }
    }

    static int fnv1a32(String s) {
        int hash = FNV_OFFSET_BASIS;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= FNV_PRIME;
        }
        return hash;
    }
}

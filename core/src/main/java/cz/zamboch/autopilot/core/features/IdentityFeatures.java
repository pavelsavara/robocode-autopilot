package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Computes OPPONENT_ID_HASH from the OPPONENT_ID string feature.
 * The name is stored via wb.setStringFeature(OPPONENT_ID, name) by the
 * caller (Autopilot or pipeline Player).
 */
public final class IdentityFeatures implements IInGameFeatures {
    private static final Feature[] DEPS = { Feature.OPPONENT_ID };
    private static final Feature[] OUTPUTS = { Feature.OPPONENT_ID_HASH };

    /**
     * Cached hash — once computed, never changes (opponent identity is constant).
     * Instance-level: each Autopilot (live robot, observer-0, observer-1) gets its own.
     * The live robot must reuse the same IdentityFeatures instance across rounds.
     */
    private double cachedHash = Double.NaN;

    public Feature[] getDependencies() {
        return DEPS;
    }

    public Feature[] getOutputFeatures() {
        return OUTPUTS;
    }

    public FileType getFileType() {
        return FileType.TICKS;
    }

    public void process(Whiteboard wb) {
        if (Double.isNaN(cachedHash)) {
            String name = wb.getStringFeature(Feature.OPPONENT_ID);
            if (name == null) {
                return;
            }
            // Parse opponent bot ID (strip version suffix after space)
            int sp = name.indexOf(' ');
            String botId = (sp < 0) ? name : name.substring(0, sp);
            cachedHash = RoboMath.fnv1a32(botId);
        }
        wb.setFeature(Feature.OPPONENT_ID_HASH, cachedHash);
    }

    /** Reset cached state — for testing only. */
    void resetCache() {
        cachedHash = Double.NaN;
    }
}

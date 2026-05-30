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
     * Cached hash for the most recently seen opponent name, to avoid recomputing
     * FNV-1a every tick. Keyed by {@link #cachedName}: recomputed only when the
     * OPPONENT_ID string changes.
     * <p>
     * IMPORTANT: the output OPPONENT_ID_HASH is only set when OPPONENT_ID is
     * present. Before the first scan of a round (OPPONENT_ID null after the
     * round-start clear), the hash is left unset (NaN). This mirrors the live
     * robot, which Robocode re-instantiates each round — so its identity is
     * unknown until the first scan. Emitting the hash unconditionally once cached
     * would diverge from the live robot on every round's pre-scan ticks.
     */
    private double cachedHash = Double.NaN;
    private String cachedName;

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
        String name = wb.getStringFeature(Feature.OPPONENT_ID);
        if (name == null) {
            // Identity unknown (pre-scan / round start) — leave OPPONENT_ID_HASH unset
            // (NaN). The live robot is re-instantiated per round, so it likewise has no
            // identity until the first scan.
            return;
        }
        if (Double.isNaN(cachedHash) || !name.equals(cachedName)) {
            // Parse opponent bot ID (strip version suffix after space)
            int sp = name.indexOf(' ');
            String botId = (sp < 0) ? name : name.substring(0, sp);
            cachedHash = RoboMath.fnv1a32(botId);
            cachedName = name;
        }
        wb.setFeature(Feature.OPPONENT_ID_HASH, cachedHash);
    }

    /** Reset cached state — for testing only. */
    void resetCache() {
        cachedHash = Double.NaN;
        cachedName = null;
    }
}

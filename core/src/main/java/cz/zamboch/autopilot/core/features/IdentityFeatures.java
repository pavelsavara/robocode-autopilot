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
 * <p>
 * Stateless: the hash is recomputed each scan directly from the OPPONENT_ID
 * string. It is only set when OPPONENT_ID is present; before the first scan of a
 * round (OPPONENT_ID null after the round-start clear) the hash is left unset
 * (NaN). This mirrors the live robot, which Robocode re-instantiates each round,
 * so its identity is unknown until the first scan.
 */
public final class IdentityFeatures implements IInGameFeatures {
    private static final Feature[] DEPS = { Feature.OPPONENT_ID };
    private static final Feature[] OUTPUTS = { Feature.OPPONENT_ID_HASH };

    public Feature[] getDependencies() {
        return DEPS;
    }

    public Feature[] getOutputFeatures() {
        return OUTPUTS;
    }

    public FileType getFileType() {
        return FileType.SCAN;
    }

    public void process(Whiteboard wb) {
        if (!wb.hasCurrentScan()) {
            return;
        }
        String name = wb.getStringFeature(Feature.OPPONENT_ID);
        if (name == null) {
            // Identity unknown (pre-scan / round start) - leave OPPONENT_ID_HASH unset
            // (NaN). The live robot is re-instantiated per round, so it likewise has no
            // identity until the first scan.
            return;
        }
        // Parse opponent bot ID (strip version suffix after space)
        int sp = name.indexOf(' ');
        String botId = (sp < 0) ? name : name.substring(0, sp);
        wb.setCurrentScanFeature(Feature.OPPONENT_ID_HASH, RoboMath.fnv1a32(botId));
    }
}

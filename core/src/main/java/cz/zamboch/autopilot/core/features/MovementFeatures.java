package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Computes lateral/advancing velocity decomposition relative to bearing line.
 */
public final class MovementFeatures implements IInGameFeatures {
    private static final Feature[] DEPS = {
            Feature.OPPONENT_BEARING_ABSOLUTE,
            Feature.OPPONENT_VELOCITY,
            Feature.OPPONENT_HEADING
    };
    private static final Feature[] OUTPUTS = {
            Feature.OPPONENT_LATERAL_VELOCITY,
            Feature.OPPONENT_ADVANCING_VELOCITY
    };

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
        double oppVelocity = wb.getFeature(Feature.OPPONENT_VELOCITY);
        double oppHeading = wb.getFeature(Feature.OPPONENT_HEADING);
        double absoluteBearing = wb.getFeature(Feature.OPPONENT_BEARING_ABSOLUTE);

        if (Double.isNaN(oppVelocity) || Double.isNaN(oppHeading) || Double.isNaN(absoluteBearing)) {
            return;
        }

        // Bearing from opponent to us is absoluteBearing + PI
        double bearingFromOpponent = absoluteBearing + Math.PI;
        double relativeHeading = oppHeading - bearingFromOpponent;

        wb.setFeature(Feature.OPPONENT_LATERAL_VELOCITY, oppVelocity * Math.sin(relativeHeading));
        wb.setFeature(Feature.OPPONENT_ADVANCING_VELOCITY, oppVelocity * Math.cos(relativeHeading));
    }
}

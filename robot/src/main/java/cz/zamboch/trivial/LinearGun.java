package cz.zamboch.trivial;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.IGunStrategy;

/**
 * Linear gun — aims where the opponent will be if they continue
 * at constant velocity and heading.
 */
public final class LinearGun implements IGunStrategy {

    @Override
    public double getFireAngle(Whiteboard wb) {
        if (!wb.hasFeature(Feature.LINEAR_TARGET_ANGLE)) {
            return wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS);
        }
        return wb.getFeature(Feature.LINEAR_TARGET_ANGLE);
    }

    @Override
    public double getConfidence(Whiteboard wb) {
        return 0.7;
    }

    @Override
    public String getName() {
        return "linear";
    }
}

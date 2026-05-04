package cz.zamboch.trivial;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.IGunStrategy;

/**
 * Circular gun — aims where the opponent will be if they continue
 * turning at their current rate.
 */
public final class CircularGun implements IGunStrategy {

    @Override
    public double getFireAngle(Whiteboard wb) {
        if (!wb.hasFeature(Feature.CIRCULAR_TARGET_ANGLE)) {
            return wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS);
        }
        return wb.getFeature(Feature.CIRCULAR_TARGET_ANGLE);
    }

    @Override
    public double getConfidence(Whiteboard wb) {
        return 0.8;
    }

    @Override
    public String getName() {
        return "circular";
    }
}

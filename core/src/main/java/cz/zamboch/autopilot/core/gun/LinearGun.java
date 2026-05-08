package cz.zamboch.autopilot.core.gun;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.IGunStrategy;

/**
 * Linear gun — fires at the linear extrapolation of the opponent's position.
 * Reads the pre-computed {@code LINEAR_TARGET_ANGLE} from TargetingFeatures.
 */
public final class LinearGun implements IGunStrategy {

    @Override
    public double getFireAngle(Whiteboard wb) {
        if (wb.hasFeature(Feature.LINEAR_TARGET_ANGLE)) {
            return wb.getFeature(Feature.LINEAR_TARGET_ANGLE);
        }
        // Fallback to head-on if linear targeting not yet computed
        return wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS);
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

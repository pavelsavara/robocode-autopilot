package cz.zamboch.autopilot.core.gun;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.IGunStrategy;

/**
 * Circular gun — fires at the circular extrapolation of the opponent's position.
 * Reads the pre-computed {@code CIRCULAR_TARGET_ANGLE} from TargetingFeatures.
 */
public final class CircularGun implements IGunStrategy {

    @Override
    public double getFireAngle(Whiteboard wb) {
        if (wb.hasFeature(Feature.CIRCULAR_TARGET_ANGLE)) {
            return wb.getFeature(Feature.CIRCULAR_TARGET_ANGLE);
        }
        // Fallback to head-on if circular targeting not yet computed
        return wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS);
    }

    @Override
    public double getConfidence(Whiteboard wb) {
        return 1.0; // Highest priority — best general-purpose gun
    }

    @Override
    public String getName() {
        return "circular";
    }
}

package cz.zamboch.trivial;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.IGunStrategy;

/**
 * Head-on gun — aims directly at the opponent's current position.
 */
public final class HeadOnGun implements IGunStrategy {

    @Override
    public double getFireAngle(Whiteboard wb) {
        return wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS);
    }

    @Override
    public double getConfidence(Whiteboard wb) {
        return 1.0;
    }

    @Override
    public String getName() {
        return "head-on";
    }
}

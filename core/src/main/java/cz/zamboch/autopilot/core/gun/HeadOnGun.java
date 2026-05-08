package cz.zamboch.autopilot.core.gun;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.IGunStrategy;

/**
 * Head-on gun — fires directly at the opponent's current position.
 * Simple baseline that hits stationary or slow-moving opponents.
 */
public final class HeadOnGun implements IGunStrategy {

    @Override
    public double getFireAngle(Whiteboard wb) {
        return wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS);
    }

    @Override
    public double getConfidence(Whiteboard wb) {
        return 0.3; // Low priority — only useful against stationary opponents
    }

    @Override
    public String getName() {
        return "head-on";
    }
}

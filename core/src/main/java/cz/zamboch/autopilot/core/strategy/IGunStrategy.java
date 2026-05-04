package cz.zamboch.autopilot.core.strategy;

import cz.zamboch.autopilot.core.Whiteboard;

/**
 * A gun aiming strategy. Each strategy represents a different model of
 * opponent movement (head-on, linear, circular, ML-based, etc.).
 * All registered strategies are evaluated every scan tick; the best
 * performer is selected via virtual-gun tracking.
 */
public interface IGunStrategy {

    /**
     * Compute the absolute gun angle to fire at.
     * Called every scan tick for every registered strategy.
     */
    double getFireAngle(Whiteboard wb);

    /**
     * Confidence in this tick's prediction, [0, 1].
     * Used as tiebreaker when hit rates are close.
     */
    double getConfidence(Whiteboard wb);

    /** Human-readable name for logging. */
    String getName();
}

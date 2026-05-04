package cz.zamboch.trivial;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.IRadarStrategy;
import cz.zamboch.autopilot.core.util.RoboMath;

/**
 * Narrow lock radar — oscillates tightly on the opponent for
 * near-100% scan rate. Overshoots slightly to maintain lock.
 */
public final class NarrowLockRadar implements IRadarStrategy {

    @Override
    public double getRadarTurn(Whiteboard wb) {
        if (!wb.hasFeature(Feature.BEARING_TO_OPPONENT_ABS)) {
            // No scan yet — sweep
            return Double.POSITIVE_INFINITY;
        }
        double radarHeading = wb.getOurRadarHeading();
        double bearing = wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS);
        double turn = RoboMath.normalRelativeAngle(bearing - radarHeading);
        // Overshoot slightly to maintain lock
        return turn + Math.signum(turn) * Math.toRadians(2);
    }

    @Override
    public String getName() {
        return "narrow-lock";
    }
}

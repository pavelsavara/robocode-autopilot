package cz.zamboch.autopilot.core.strategy;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Narrow scan-event lock on the opponent.
 *
 * <p>
 * Robocode scans against the opponent's 36x36 body rectangle, not only its
 * center. In 1v1 the relative center can move at most 16 px in one tick, so a
 * radar ray crossing the last scanned center still intersects the next tick's
 * body. This strategy therefore prioritizes crossing the last actually scanned
 * center every tick instead of predicting a future center and adding a wide
 * center-point overshoot.
 */
public final class NarrowLockRadar implements IRadarStrategy {

    private static final double CROSSING_OVERSHOOT = Math.toRadians(1.0);

    private final Whiteboard wb;
    private double lastTurnDirection = 1.0; // +1 cw, -1 ccw

    public NarrowLockRadar(Whiteboard wb) {
        this.wb = wb;
    }

    @Override
    public double getRadarTurn() {
        double radarHeading = wb.getFeature(Feature.RADAR_HEADING);
        double latestBearing = latestKnownBearing();

        if (Double.isNaN(latestBearing) || Double.isNaN(radarHeading)) {
            return lastTurnDirection * Double.POSITIVE_INFINITY;
        }

        double toCenter = RoboMath.normalRelativeAngle(latestBearing - radarHeading);
        double direction = Math.signum(toCenter);
        if (direction == 0.0) {
            direction = -lastTurnDirection;
        }

        lastTurnDirection = direction;
        return toCenter + direction * CROSSING_OVERSHOOT;
    }

    private double latestKnownBearing() {
        for (int n = 0; n < Whiteboard.TICK_RING_DEPTH; n++) {
            double opponentX = wb.getFeatureNTicksAgo(Feature.OPPONENT_X, n);
            double opponentY = wb.getFeatureNTicksAgo(Feature.OPPONENT_Y, n);
            double ourX = wb.getFeature(Feature.OUR_X);
            double ourY = wb.getFeature(Feature.OUR_Y);
            if (!Double.isNaN(opponentX) && !Double.isNaN(opponentY)
                    && !Double.isNaN(ourX) && !Double.isNaN(ourY)) {
                return Math.atan2(opponentX - ourX, opponentY - ourY);
            }
        }
        return Double.NaN;
    }

    @Override
    public String getName() {
        return "NarrowLockRadar";
    }

}
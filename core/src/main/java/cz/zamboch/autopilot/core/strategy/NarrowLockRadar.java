package cz.zamboch.autopilot.core.strategy;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Narrow oscillating radar lock on the opponent.
 * Overshoots by a fixed 2° for reliable 1v1 lock.
 */
public final class NarrowLockRadar implements IRadarStrategy {

    private static final double OVERSHOOT = Math.toRadians(2);

    private final Whiteboard wb;

    public NarrowLockRadar(Whiteboard wb) {
        this.wb = wb;
    }

    @Override
    public double getRadarTurn() {
        double absoluteBearing = wb.getFeature(Feature.OPPONENT_BEARING_ABSOLUTE);
        if (Double.isNaN(absoluteBearing)) {
            return Double.POSITIVE_INFINITY; // spin until we find opponent
        }
        double radarHeading = wb.getFeature(Feature.RADAR_HEADING);
        double radarTurn = RoboMath.normalRelativeAngle(absoluteBearing - radarHeading);
        return radarTurn + Math.signum(radarTurn) * OVERSHOOT;
    }

    @Override
    public String getName() {
        return "NarrowLockRadar";
    }

}

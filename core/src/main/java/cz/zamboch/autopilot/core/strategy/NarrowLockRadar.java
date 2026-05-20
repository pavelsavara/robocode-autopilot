package cz.zamboch.autopilot.core.strategy;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Narrow oscillating radar lock on the opponent.
 * Overshoots by a fixed 2° for reliable 1v1 lock.
 * When lock is lost, spins in the last known direction instead of
 * arbitrary positive infinity.
 */
public final class NarrowLockRadar implements IRadarStrategy {

    private static final double OVERSHOOT = Math.toRadians(2);

    private final Whiteboard wb;
    private double lastTurnDirection = 1.0; // +1 or -1, initial spin clockwise

    public NarrowLockRadar(Whiteboard wb) {
        this.wb = wb;
    }

    @Override
    public double getRadarTurn() {
        double absoluteBearing = wb.getFeature(Feature.OPPONENT_BEARING_ABSOLUTE);
        if (Double.isNaN(absoluteBearing)) {
            // Spin in the last known direction to re-acquire lock
            return lastTurnDirection * Double.POSITIVE_INFINITY;
        }
        double radarHeading = wb.getFeature(Feature.RADAR_HEADING);
        double radarTurn = RoboMath.normalRelativeAngle(absoluteBearing - radarHeading);
        lastTurnDirection = Math.signum(radarTurn) != 0 ? Math.signum(radarTurn) : lastTurnDirection;
        return radarTurn + Math.signum(radarTurn) * OVERSHOOT;
    }

    @Override
    public String getName() {
        return "NarrowLockRadar";
    }

}

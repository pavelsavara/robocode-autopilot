package cz.zamboch.autopilot.core.strategy;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Oscillating radar lock on the opponent. Relies on
 * {@code setAdjustRadarForRobotTurn(true)} and
 * {@code setAdjustRadarForGunTurn(true)} so the commanded radar turn equals the
 * radar's actual angular motion (clamped to ±45°/tick).
 *
 * <p>
 * Each tick we command {@code relativeBearing + sign * OVERSHOOT}. In steady
 * state the radar end position alternates between {@code opp + OVERSHOOT} and
 * {@code opp - (45° - OVERSHOOT)}; the 45°-wide arc swept each tick straddles
 * the opponent with margins {@code OVERSHOOT} on one side and {@code 45° -
 * OVERSHOOT} on the other. {@code OVERSHOOT = 22.5°} maximizes the min margin
 * (worst-case opponent angular velocity we can tolerate without a missed scan).
 */
public final class OvershootLockRadar implements IRadarStrategy {

    private static final double OVERSHOOT = Math.toRadians(22.5);

    private final Whiteboard wb;
    private double lastTurnDirection = 1.0; // +1 cw, -1 ccw

    public OvershootLockRadar(Whiteboard wb) {
        this.wb = wb;
    }

    @Override
    public double getRadarTurn() {
        double absoluteBearing = wb.getFeature(Feature.OPPONENT_BEARING_ABSOLUTE);
        if (Double.isNaN(absoluteBearing)) {
            return lastTurnDirection * Double.POSITIVE_INFINITY;
        }
        double radarHeading = wb.getFeature(Feature.RADAR_HEADING);
        double radarTurn = RoboMath.normalRelativeAngle(absoluteBearing - radarHeading);
        double dir = Math.signum(radarTurn);
        if (dir != 0) {
            lastTurnDirection = dir;
        }
        return radarTurn + lastTurnDirection * OVERSHOOT;
    }

    @Override
    public String getName() {
        return "OvershootLockRadar";
    }

}

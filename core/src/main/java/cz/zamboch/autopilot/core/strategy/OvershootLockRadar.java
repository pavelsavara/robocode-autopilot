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
        double radarHeading = wb.getFeature(Feature.RADAR_HEADING);

        // Collect the two most recent KNOWN absolute bearings from the tick ring.
        // On a no-scan tick the current slot is NaN; walking the ring lets us keep
        // a controlled lock through a brief scan gap instead of an open-loop spin.
        double latestBearing = Double.NaN;
        double previousBearing = Double.NaN;
        for (int n = 0; n < Whiteboard.TICK_RING_DEPTH; n++) {
            double v = wb.getFeatureNTicksAgo(Feature.OPPONENT_BEARING_ABSOLUTE, n);
            if (!Double.isNaN(v)) {
                if (Double.isNaN(latestBearing)) {
                    latestBearing = v;
                } else {
                    previousBearing = v;
                    break;
                }
            }
        }

        // No track within the ring (round start, or opponent lost for several
        // ticks): fall back to a full sweep to re-acquire.
        if (Double.isNaN(latestBearing) || Double.isNaN(radarHeading)) {
            return lastTurnDirection * Double.POSITIVE_INFINITY;
        }

        // Lead the lock by the recent angular rate so the swept arc is centred on
        // where the opponent will be when the sweep passes it, not where it last
        // was. The lead (a few degrees) is small versus OVERSHOOT, so the 22.5°
        // straddle is preserved.
        double predicted = latestBearing;
        if (!Double.isNaN(previousBearing)) {
            predicted += RoboMath.normalRelativeAngle(latestBearing - previousBearing);
        }

        double radarTurn = RoboMath.normalRelativeAngle(predicted - radarHeading);
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

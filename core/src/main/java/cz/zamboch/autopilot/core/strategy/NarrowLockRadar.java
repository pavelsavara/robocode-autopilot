package cz.zamboch.autopilot.core.strategy;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Narrow oscillating radar lock on the opponent.
 * <ol>
 * <li>Predicts where the opponent will be next tick using current
 * position/velocity/heading and aims there instead of where they were
 * this tick (#1 predictive lead).</li>
 * <li>Sizes overshoot adaptively from the opponent's angular velocity
 * relative to us so a fast laterally-moving target can't slip out of
 * the swept arc (#2 adaptive overshoot).</li>
 * <li>Subtracts the body and gun turns that will be carried on this tick
 * (each clamped to engine limits) so the radar's own command lands on
 * the predicted bearing regardless of gun slew direction (#3 carry
 * compensation).</li>
 * <li>On a lost lock, does a single bounded 45° sweep centered on the last
 * known bearing before escalating to a full spin (#4 bounded
 * re-acquire).</li>
 * </ol>
 */
public final class NarrowLockRadar implements IRadarStrategy {

    private static final double BASE_OVERSHOOT = Math.toRadians(2);
    private static final double MAX_GUN_TURN = Math.toRadians(20);
    private static final double MAX_RADAR_TURN = Math.toRadians(45);

    private final Whiteboard wb;
    private double lastTurnDirection = 1.0; // +1 cw, -1 ccw
    private double lastBearing = Double.NaN;
    private int consecutiveMisses = 0;

    public NarrowLockRadar(Whiteboard wb) {
        this.wb = wb;
    }

    @Override
    public double getRadarTurn(double bodyTurnDesired, double gunTurnDesired) {
        double absoluteBearing = wb.getFeature(Feature.OPPONENT_BEARING_ABSOLUTE);
        double radarHeading = wb.getFeature(Feature.RADAR_HEADING);

        if (Double.isNaN(absoluteBearing)) {
            consecutiveMisses++;
            // First miss: bounded 45° sweep centered on last known bearing,
            // accounting for the gun/body carry so the sweep actually lands
            // where we want. After that, fall back to unbounded spin.
            if (consecutiveMisses == 1 && !Double.isNaN(lastBearing) && !Double.isNaN(radarHeading)) {
                double carry = bodyCarry(bodyTurnDesired) + gunCarry(gunTurnDesired);
                double toLast = RoboMath.normalRelativeAngle(lastBearing - radarHeading);
                return toLast - carry + lastTurnDirection * MAX_RADAR_TURN;
            }
            return lastTurnDirection * Double.POSITIVE_INFINITY;
        }
        consecutiveMisses = 0;

        // Angular velocity of opponent relative to us, from last two scans.
        double angularVel = 0.0;
        if (!Double.isNaN(lastBearing)) {
            angularVel = RoboMath.normalRelativeAngle(absoluteBearing - lastBearing);
        }

        // #1 Predictive lead: where will the opponent be at the END of this
        // tick (i.e. the bearing the radar should actually hit)?
        double predicted = predictBearing(absoluteBearing, angularVel);

        // #3 Carry compensation: radar physically rotates by body+gun+radar
        // (each clamped). Subtract the planned body and gun motion so the
        // radar's own command places it on `predicted`.
        double carry = bodyCarry(bodyTurnDesired) + gunCarry(gunTurnDesired);
        double netTurn = RoboMath.normalRelativeAngle(predicted - radarHeading) - carry;

        // Direction memory for the lost-lock case.
        double dirSign = Math.signum(netTurn);
        if (dirSign == 0) {
            dirSign = Math.signum(angularVel);
        }
        if (dirSign != 0) {
            lastTurnDirection = dirSign;
        }

        // #2 Adaptive overshoot: cover the opponent's angular displacement
        // between now and the next scan plus a constant safety margin.
        double overshoot = Math.max(BASE_OVERSHOOT, Math.abs(angularVel) + BASE_OVERSHOOT);

        lastBearing = absoluteBearing;
        return netTurn + lastTurnDirection * overshoot;
    }

    /** Predict opponent absolute bearing at end of this tick. */
    private double predictBearing(double currentBearing, double angularVel) {
        double oppX = wb.getFeature(Feature.OPPONENT_X);
        double oppY = wb.getFeature(Feature.OPPONENT_Y);
        double oppV = wb.getFeature(Feature.OPPONENT_VELOCITY);
        double oppH = wb.getFeature(Feature.OPPONENT_HEADING);
        double ourX = wb.getFeature(Feature.OUR_X);
        double ourY = wb.getFeature(Feature.OUR_Y);
        if (Double.isNaN(oppX) || Double.isNaN(oppV) || Double.isNaN(oppH)
                || Double.isNaN(ourX)) {
            // Fallback: linearly extrapolate bearing using observed angular vel.
            return currentBearing + angularVel;
        }
        double nextOppX = oppX + oppV * Math.sin(oppH);
        double nextOppY = oppY + oppV * Math.cos(oppH);
        // Our own translation across one tick is small relative to range and
        // we do not yet know our committed `ahead` here; ignoring it is the
        // same simplification used elsewhere in this strategy stack.
        return Math.atan2(nextOppX - ourX, nextOppY - ourY);
    }

    private double bodyCarry(double bodyTurnDesired) {
        if (Double.isNaN(bodyTurnDesired)) {
            return 0.0;
        }
        double v = wb.getFeature(Feature.OUR_VELOCITY);
        double maxBody = Math.toRadians(10.0 - 0.75 * (Double.isNaN(v) ? 0.0 : Math.abs(v)));
        return clamp(bodyTurnDesired, maxBody);
    }

    private double gunCarry(double gunTurnDesired) {
        if (Double.isNaN(gunTurnDesired)) {
            return 0.0;
        }
        return clamp(gunTurnDesired, MAX_GUN_TURN);
    }

    private static double clamp(double v, double limit) {
        if (v > limit) {
            return limit;
        }
        if (v < -limit) {
            return -limit;
        }
        return v;
    }

    @Override
    public String getName() {
        return "NarrowLockRadar";
    }

}

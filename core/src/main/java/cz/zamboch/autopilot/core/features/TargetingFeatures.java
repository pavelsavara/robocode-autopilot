package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.util.RoboMath;

/**
 * Targeting features: linear/circular targeting baselines and GuessFactor
 * coordinates at standard bullet powers. Designed for in-game use — no I/O.
 *
 * <p>Computes:</p>
 * <ul>
 *   <li>Linear target angle &amp; offset (law-of-sines exact solution)</li>
 *   <li>Circular target angle &amp; offset (iterative simulation)</li>
 *   <li>GF bearing offset and current GF at powers 1.0, 1.5, 2.0</li>
 *   <li>Opponent's estimated GuessFactor (proxy: our_lateral_velocity / 8)</li>
 * </ul>
 *
 * <p>Not final — the pipeline subclass {@code TargetingOfflineFeatures}
 * extends this to add CSV output.</p>
 */
public class TargetingFeatures implements IInGameFeatures {

    private static final double DEFAULT_FIRE_POWER = 2.0;
    private static final double MAX_VELOCITY = 8.0;
    private static final double ROBOT_HALF_SIZE = 18.0;

    private static final Feature[] OUTPUTS = {
            Feature.LINEAR_TARGET_ANGLE,
            Feature.LINEAR_TARGET_OFFSET,
            Feature.CIRCULAR_TARGET_ANGLE,
            Feature.CIRCULAR_TARGET_OFFSET,
            Feature.GF_BEARING_OFFSET,
            Feature.GF_CURRENT_AT_POWER_1,
            Feature.GF_CURRENT_AT_POWER_1_5,
            Feature.GF_CURRENT_AT_POWER_2,
            Feature.OPPONENT_GUESS_FACTOR
    };

    private static final Feature[] DEPS = {
            Feature.DISTANCE,
            Feature.BEARING_TO_OPPONENT_ABS,
            Feature.OPPONENT_VELOCITY,
            Feature.OPPONENT_LATERAL_DIRECTION,
            Feature.OUR_LATERAL_VELOCITY
    };

    public Feature[] getOutputFeatures() { return OUTPUTS; }
    public Feature[] getDependencies() { return DEPS; }

    public void process(Whiteboard wb) {
        if (!wb.isScanAvailableThisTick()
                || !wb.hasFeature(Feature.BEARING_TO_OPPONENT_ABS)
                || !wb.hasFeature(Feature.OPPONENT_VELOCITY)) {
            return;
        }

        double bearing = wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS);
        double oppVel = wb.getFeature(Feature.OPPONENT_VELOCITY);
        double oppHeading = wb.getOpponentHeading();

        // Use strategy-computed fire power budget (set after previous scan's strategy computation)
        // for a better estimate of our bullet speed. Falls back to last actual fire power, then default.
        double ourPower;
        if (wb.getCurrentFirePowerBudget() > 0) {
            ourPower = wb.getCurrentFirePowerBudget();
        } else if (wb.getLastOurFireTick() >= 0) {
            ourPower = wb.getLastOurFirePower();
        } else {
            ourPower = DEFAULT_FIRE_POWER;
        }
        double ourSpeed = 20.0 - 3.0 * ourPower;

        // Linear target — exact non-iterative formula via law of sines.
        double sinArg = oppVel / ourSpeed * Math.sin(oppHeading - bearing);
        if (sinArg > 1.0) sinArg = 1.0;
        else if (sinArg < -1.0) sinArg = -1.0;
        double linOffset = Math.asin(sinArg);
        double linAngle = RoboMath.normalAbsoluteAngle(bearing + linOffset);
        wb.setFeature(Feature.LINEAR_TARGET_ANGLE, linAngle);
        wb.setFeature(Feature.LINEAR_TARGET_OFFSET, linOffset);

        // Circular target — iterative simulation.
        double headingDelta = wb.hasFeature(Feature.OPPONENT_HEADING_DELTA)
                ? wb.getFeature(Feature.OPPONENT_HEADING_DELTA)
                : 0.0;
        double velDelta = wb.hasFeature(Feature.OPPONENT_VELOCITY_DELTA)
                ? wb.getFeature(Feature.OPPONENT_VELOCITY_DELTA)
                : 0.0;
        double circAngle = circularTargetAngle(
                wb.getOurX(), wb.getOurY(),
                wb.getOpponentX(), wb.getOpponentY(),
                oppHeading, oppVel, headingDelta, velDelta,
                ourSpeed,
                wb.getBattlefieldWidth(), wb.getBattlefieldHeight());
        wb.setFeature(Feature.CIRCULAR_TARGET_ANGLE, circAngle);
        wb.setFeature(Feature.CIRCULAR_TARGET_OFFSET,
                RoboMath.normalRelativeAngle(circAngle - bearing));

        // GF-bearing offset and current-position GF at standard powers.
        double gunHeading = wb.getOurGunHeading();
        double bearingOffset = RoboMath.normalRelativeAngle(bearing - gunHeading);
        wb.setFeature(Feature.GF_BEARING_OFFSET, bearingOffset);

        int latDir = wb.hasFeature(Feature.OPPONENT_LATERAL_DIRECTION)
                ? (int) wb.getFeature(Feature.OPPONENT_LATERAL_DIRECTION)
                : 0;
        wb.setFeature(Feature.GF_CURRENT_AT_POWER_1, gfAt(bearingOffset, latDir, 1.0));
        wb.setFeature(Feature.GF_CURRENT_AT_POWER_1_5, gfAt(bearingOffset, latDir, 1.5));
        wb.setFeature(Feature.GF_CURRENT_AT_POWER_2, gfAt(bearingOffset, latDir, 2.0));

        // Where WE sit in opponent's GF space — proxy via our lateral velocity / MAX_VELOCITY.
        double ourLatVel = wb.hasFeature(Feature.OUR_LATERAL_VELOCITY)
                ? wb.getFeature(Feature.OUR_LATERAL_VELOCITY)
                : 0.0;
        double oppGf = ourLatVel / MAX_VELOCITY;
        if (oppGf > 1.0) oppGf = 1.0;
        else if (oppGf < -1.0) oppGf = -1.0;
        wb.setFeature(Feature.OPPONENT_GUESS_FACTOR, oppGf);
    }

    /**
     * Compute the GuessFactor for a given bearing offset and lateral direction
     * at the specified bullet power.
     */
    static double gfAt(double bearingOffset, int latDir, double power) {
        double bulletSpeed = 20.0 - 3.0 * power;
        double mea = Math.asin(8.0 / bulletSpeed);
        double effectiveDir = latDir == 0 ? 1 : latDir;
        double gf = (bearingOffset * effectiveDir) / mea;
        if (gf > 1.0) gf = 1.0;
        else if (gf < -1.0) gf = -1.0;
        return gf;
    }

    /**
     * Iterative circular targeting: project the opponent forward along their current
     * heading-delta arc until our bullet would intercept.
     *
     * <p>Physics model (matching Robocode engine):</p>
     * <ul>
     *   <li>Turn-then-move ordering (heading updates before position)</li>
     *   <li>Turn rate capped at {@code 10 − 0.75·|velocity|} deg/tick</li>
     *   <li>Velocity acceleration/deceleration applied each tick, clamped to [-8, 8]</li>
     *   <li>Wall collision zeroes velocity and acceleration</li>
     * </ul>
     */
    static double circularTargetAngle(
            double ourX, double ourY,
            double oppX, double oppY,
            double oppHeading, double oppVel, double headingDelta,
            double velDelta,
            double ourSpeed,
            int bfW, int bfH) {
        double px = oppX, py = oppY, ph = oppHeading;
        double vel = oppVel;
        double vd = velDelta;
        double t = 0;
        int maxIter = 256;
        while (++t * ourSpeed < Math.hypot(ourX - px, ourY - py) && --maxIter > 0) {
            // Apply velocity acceleration, clamped to physics limits
            vel += vd;
            if (vel > MAX_VELOCITY) vel = MAX_VELOCITY;
            else if (vel < -MAX_VELOCITY) vel = -MAX_VELOCITY;

            // Cap heading delta to physics-limited turn rate at current velocity
            double maxTurn = Math.toRadians(10.0 - 0.75 * Math.abs(vel));
            if (maxTurn < 0) maxTurn = 0;
            double dt = headingDelta;
            if (dt > maxTurn) dt = maxTurn;
            else if (dt < -maxTurn) dt = -maxTurn;

            // Robocode physics: turn first, then move
            ph += dt;
            double newPx = px + Math.sin(ph) * vel;
            double newPy = py + Math.cos(ph) * vel;

            // Wall collision: clamp position, zero velocity and acceleration
            boolean hitWall = false;
            if (newPx < ROBOT_HALF_SIZE) { newPx = ROBOT_HALF_SIZE; hitWall = true; }
            else if (newPx > bfW - ROBOT_HALF_SIZE) { newPx = bfW - ROBOT_HALF_SIZE; hitWall = true; }
            if (newPy < ROBOT_HALF_SIZE) { newPy = ROBOT_HALF_SIZE; hitWall = true; }
            else if (newPy > bfH - ROBOT_HALF_SIZE) { newPy = bfH - ROBOT_HALF_SIZE; hitWall = true; }

            if (hitWall) {
                vel = 0;
                vd = 0;
            }

            px = newPx;
            py = newPy;
        }
        return RoboMath.normalAbsoluteAngle(Math.atan2(px - ourX, py - ourY));
    }

    /** Backward-compatible overload — velDelta defaults to 0. */
    static double circularTargetAngle(
            double ourX, double ourY,
            double oppX, double oppY,
            double oppHeading, double oppVel, double headingDelta,
            double ourSpeed,
            int bfW, int bfH) {
        return circularTargetAngle(ourX, ourY, oppX, oppY, oppHeading, oppVel,
                headingDelta, 0.0, ourSpeed, bfW, bfH);
    }
}

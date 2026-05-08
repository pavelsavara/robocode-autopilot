package cz.zamboch.distilled;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.IGunStrategy;
import cz.zamboch.autopilot.core.util.RoboMath;

/**
 * Predictive gun — uses the GBM movement prediction ({@code PREDICTED_LAT_VEL_5})
 * to aim where the opponent WILL be, not where they are now.
 *
 * <p>Iteratively simulates the opponent forward using predicted lateral velocity
 * until the bullet would intercept. This is similar to the circular gun but uses
 * the ML model's 5-tick-ahead lateral velocity prediction instead of assuming
 * constant turn rate.</p>
 *
 * <p>Competes in the VirtualGunManager alongside head-on, linear, circular,
 * and VCS guns. Expected to outperform circular for opponents whose movement
 * changes faster than a constant turn rate.</p>
 */
public final class PredictiveGun implements IGunStrategy {

    private static final double DEFAULT_FIRE_POWER = 2.0;
    private static final int MAX_ITER = 256;

    @Override
    public double getFireAngle(Whiteboard wb) {
        double bearing = wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS);

        if (!wb.hasFeature(Feature.PREDICTED_LAT_VEL_5)
                || !wb.hasFeature(Feature.DISTANCE)
                || !wb.hasFeature(Feature.OPPONENT_VELOCITY)) {
            return bearing;
        }

        double predictedLatVel = wb.getFeature(Feature.PREDICTED_LAT_VEL_5);
        double distance = wb.getFeature(Feature.DISTANCE);
        double oppVel = wb.getFeature(Feature.OPPONENT_VELOCITY);
        double oppHeading = wb.getOpponentHeading();

        // Compute the predicted heading delta that would produce the predicted
        // lateral velocity, given current velocity and bearing.
        // lateralVel = vel × sin(heading - bearing)
        // We want heading' such that vel × sin(heading' - bearing) = predictedLatVel
        // So heading' - bearing = asin(predictedLatVel / vel)
        // headingDelta ≈ (heading' - bearing) - (heading - bearing) = asin(pLV/v) - asin(cLV/v)
        // Simpler: use iterative simulation with blended velocity.

        double ourPower = wb.getLastOurFireTick() >= 0
                ? wb.getLastOurFirePower() : DEFAULT_FIRE_POWER;
        double ourSpeed = 20.0 - 3.0 * ourPower;

        // Iterative forward simulation: project opponent using a blend of
        // current and predicted lateral velocity over the bullet flight time.
        double oppX = wb.getOpponentX();
        double oppY = wb.getOpponentY();
        double ourX = wb.getOurX();
        double ourY = wb.getOurY();
        int bfW = wb.getBattlefieldWidth();
        int bfH = wb.getBattlefieldHeight();

        // Estimate heading delta from predicted lateral velocity change.
        // Current lateral vel: oppVel × sin(oppHeading - bearing)
        double currentLatVel = oppVel * Math.sin(oppHeading - bearing);
        double latVelDelta = predictedLatVel - currentLatVel;

        // Convert lat-vel change to approximate heading change rate over 5 ticks.
        // dLatVel = vel × cos(relHeading) × dHeading
        // dHeading ≈ dLatVel / (vel × cos(relHeading))
        double relHeading = oppHeading - bearing;
        double cosRel = Math.cos(relHeading);
        double headingDelta;
        if (Math.abs(oppVel * cosRel) > 0.5) {
            headingDelta = (latVelDelta / 5.0) / (oppVel * cosRel);
            // Clamp to physically possible turn rate
            double maxTurn = Math.toRadians(10.0 - 0.75 * Math.abs(oppVel));
            headingDelta = Math.max(-maxTurn, Math.min(maxTurn, headingDelta));
        } else {
            // Low velocity or perpendicular — fall back to zero turn rate (linear)
            headingDelta = 0;
        }

        // Add current heading delta (from TargetingFeatures) as base turn rate,
        // adjusted toward predicted
        double currentHeadingDelta = wb.hasFeature(Feature.OPPONENT_HEADING_DELTA)
                ? wb.getFeature(Feature.OPPONENT_HEADING_DELTA) : 0;
        double blendedDelta = 0.5 * currentHeadingDelta + 0.5 * headingDelta;

        // Forward simulation
        double px = oppX, py = oppY, ph = oppHeading;
        int t = 0;
        while (++t < MAX_ITER) {
            ph += blendedDelta;
            px += oppVel * Math.sin(ph);
            py += oppVel * Math.cos(ph);

            // Wall clamping
            px = Math.max(18, Math.min(bfW - 18, px));
            py = Math.max(18, Math.min(bfH - 18, py));

            double bulletDist = t * ourSpeed;
            double targetDist = Math.hypot(px - ourX, py - ourY);

            if (bulletDist >= targetDist) {
                return RoboMath.normalAbsoluteAngle(
                        Math.atan2(px - ourX, py - ourY));
            }
        }

        // Didn't converge — fall back to bearing
        return bearing;
    }

    @Override
    public double getConfidence(Whiteboard wb) {
        // Higher confidence when the ML model is loaded (confidence > 0)
        double conf = wb.hasFeature(Feature.PREDICTED_LAT_VEL_5_CONFIDENCE)
                ? wb.getFeature(Feature.PREDICTED_LAT_VEL_5_CONFIDENCE) : 0;
        // Scale: 0.0 (no model) to 0.85 (full model)
        return 0.1 + 0.75 * conf;
    }

    @Override
    public String getName() {
        return "predictive";
    }
}

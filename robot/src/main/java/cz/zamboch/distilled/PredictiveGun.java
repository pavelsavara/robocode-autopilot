package cz.zamboch.distilled;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.IGunStrategy;
import cz.zamboch.autopilot.core.util.RoboMath;

/**
 * Predictive gun — uses the GBM movement prediction ({@code PREDICTED_LAT_VEL_5})
 * to aim where the opponent WILL be, not where they are now.
 *
 * <p>Simulates the opponent forward using the predicted lateral velocity directly
 * (perpendicular to our bearing line) plus the current advancing velocity
 * (along the bearing line). No heading-delta conversion — the ML prediction
 * is used as-is, avoiding lossy conversions.</p>
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

        if (!wb.hasFeature(Feature.DISTANCE)
                || !wb.hasFeature(Feature.OPPONENT_VELOCITY)) {
            return bearing;
        }

        double distance = wb.getFeature(Feature.DISTANCE);

        // Use predicted lateral velocity if available, else current (= linear gun)
        double latVel;
        if (wb.hasFeature(Feature.PREDICTED_LAT_VEL_5)) {
            latVel = wb.getFeature(Feature.PREDICTED_LAT_VEL_5);
        } else if (wb.hasFeature(Feature.OPPONENT_LATERAL_VELOCITY)) {
            latVel = wb.getFeature(Feature.OPPONENT_LATERAL_VELOCITY);
        } else {
            return bearing;
        }

        double advVel = wb.hasFeature(Feature.OPPONENT_ADVANCING_VELOCITY)
                ? wb.getFeature(Feature.OPPONENT_ADVANCING_VELOCITY) : 0;

        double ourPower = wb.getLastOurFireTick() >= 0
                ? wb.getLastOurFirePower() : DEFAULT_FIRE_POWER;
        double ourSpeed = 20.0 - 3.0 * ourPower;

        double oppX = wb.getOpponentX();
        double oppY = wb.getOpponentY();
        double ourX = wb.getOurX();
        double ourY = wb.getOurY();
        int bfW = wb.getBattlefieldWidth();
        int bfH = wb.getBattlefieldHeight();

        // Decompose lateral and advancing velocity into absolute dx/dy.
        // Lateral = perpendicular to bearing, advancing = along bearing.
        double sinB = Math.sin(bearing);
        double cosB = Math.cos(bearing);
        // Lateral direction: perpendicular to bearing (right-hand rule)
        double dxPerTick = latVel * cosB + advVel * sinB;
        double dyPerTick = -latVel * sinB + advVel * cosB;

        // Iterative forward simulation
        double px = oppX, py = oppY;
        int t = 0;
        while (++t < MAX_ITER) {
            px += dxPerTick;
            py += dyPerTick;

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

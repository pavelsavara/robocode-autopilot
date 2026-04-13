package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.util.RoboMath;

/**
 * Computes movement features: velocity, lateral velocity, advancing velocity, heading delta.
 * Depends on BEARING_TO_OPPONENT_ABS from SpatialFeatures.
 */
public class MovementFeatures implements IInGameFeatures {

    private static final Feature[] OUTPUTS = {
            Feature.OPPONENT_VELOCITY,
            Feature.OPPONENT_LATERAL_VELOCITY,
            Feature.OPPONENT_ADVANCING_VELOCITY,
            Feature.OPPONENT_HEADING_DELTA
    };

    private static final Feature[] DEPS = {
            Feature.BEARING_TO_OPPONENT_ABS
    };

    public Feature[] getOutputFeatures() {
        return OUTPUTS;
    }

    public Feature[] getDependencies() {
        return DEPS;
    }

    public void process(Whiteboard wb) {
        if (!wb.isScanAvailableThisTick()) {
            return;
        }

        // Opponent velocity (direct from scan)
        double velocity = wb.getOpponentVelocity();
        wb.setFeature(Feature.OPPONENT_VELOCITY, velocity);

        // Lateral and advancing velocity relative to our bearing
        if (wb.hasFeature(Feature.BEARING_TO_OPPONENT_ABS)) {
            double absBearing = wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS);
            double opponentHeading = wb.getOpponentHeading();

            // Relative heading: opponent's heading relative to the bearing line
            double relativeHeading = RoboMath.normalRelativeAngle(opponentHeading - absBearing);

            // Lateral velocity: component perpendicular to bearing line
            double lateralVelocity = velocity * Math.sin(relativeHeading);
            wb.setFeature(Feature.OPPONENT_LATERAL_VELOCITY, lateralVelocity);

            // Advancing velocity: component along bearing line (positive = approaching)
            double advancingVelocity = -velocity * Math.cos(relativeHeading);
            wb.setFeature(Feature.OPPONENT_ADVANCING_VELOCITY, advancingVelocity);
        }

        // Heading delta (turn rate) — requires previous scan heading
        double prevHeading = wb.getPrevOpponentHeading();
        if (!Double.isNaN(prevHeading)) {
            double headingDelta = RoboMath.normalRelativeAngle(
                    wb.getOpponentHeading() - prevHeading);
            wb.setFeature(Feature.OPPONENT_HEADING_DELTA, headingDelta);
        }
    }
}

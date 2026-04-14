package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Movement segmentation features: lateral direction, acceleration,
 * deceleration flag, time since direction change.
 * Depends on OPPONENT_LATERAL_VELOCITY and OPPONENT_VELOCITY from MovementFeatures.
 */
public class MovementSegmentationFeatures implements IInGameFeatures {

    private double prevVelocity = Double.NaN;
    private long prevVelocityTick = -1;
    private int prevLateralDirection = 0;
    private long ticksSinceDirectionChange = 0;

    private static final Feature[] OUTPUTS = {
            Feature.OPPONENT_LATERAL_DIRECTION,
            Feature.OPPONENT_VELOCITY_DELTA,
            Feature.OPPONENT_IS_DECELERATING,
            Feature.OPPONENT_TIME_SINCE_DIRECTION_CHANGE
    };

    private static final Feature[] DEPS = {
            Feature.OPPONENT_LATERAL_VELOCITY,
            Feature.OPPONENT_VELOCITY
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

        double latVel = wb.getFeature(Feature.OPPONENT_LATERAL_VELOCITY);
        double vel = wb.getFeature(Feature.OPPONENT_VELOCITY);
        long tick = wb.getTick();

        // Lateral direction: sign of lateral velocity
        int lateralDirection;
        if (latVel > 0) {
            lateralDirection = 1;
        } else if (latVel < 0) {
            lateralDirection = -1;
        } else {
            lateralDirection = 0;
        }
        wb.setFeature(Feature.OPPONENT_LATERAL_DIRECTION, lateralDirection);

        // Velocity delta (acceleration)
        if (!Double.isNaN(prevVelocity) && prevVelocityTick >= 0) {
            long deltaTicks = tick - prevVelocityTick;
            if (deltaTicks > 0) {
                wb.setFeature(Feature.OPPONENT_VELOCITY_DELTA, (vel - prevVelocity) / deltaTicks);
            }
        }

        // Is decelerating
        boolean isDecelerating = !Double.isNaN(prevVelocity) && Math.abs(vel) < Math.abs(prevVelocity);
        wb.setFeature(Feature.OPPONENT_IS_DECELERATING, isDecelerating ? 1.0 : 0.0);

        // Time since direction change
        if (lateralDirection != 0 && prevLateralDirection != 0
                && lateralDirection != prevLateralDirection) {
            ticksSinceDirectionChange = 0;
        } else {
            ticksSinceDirectionChange++;
        }
        wb.setFeature(Feature.OPPONENT_TIME_SINCE_DIRECTION_CHANGE, ticksSinceDirectionChange);

        // Update state for next tick
        prevVelocity = vel;
        prevVelocityTick = tick;
        prevLateralDirection = lateralDirection;
    }
}

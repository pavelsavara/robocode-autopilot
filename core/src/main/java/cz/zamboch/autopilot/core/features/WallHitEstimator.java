package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Estimates opponent wall-hit damage from scan data (for the live robot).
 * <p>
 * The pipeline uses god-view {@code RobotState.HIT_WALL} detection;
 * the robot cannot observe opponent wall hits directly, so it estimates
 * based on position near wall + previous velocity toward it.
 * <p>
 * Heuristic: if opponent is at a wall edge and their previous velocity
 * toward that wall exceeded what they could have braked in the available
 * ticks, they must have hit it. The estimated impact velocity is the
 * previous velocity component toward the wall minus maximum possible braking
 * (2 units/tick × ticksSinceScan).
 * <p>
 * Only sets OPPONENT_WALL_HIT_DAMAGE if it hasn't already been set
 * (i.e., pipeline's exact value takes priority).
 */
public final class WallHitEstimator implements IInGameFeatures {

    /** Distance from edge at which a robot center touches the wall (half body = 18). */
    private static final double WALL_MARGIN = 18.0;
    /** Tolerance for detecting "at wall" position. */
    private static final double WALL_TOLERANCE = 1.0;
    /** Max deceleration per tick in Robocode. */
    private static final double DECEL_PER_TICK = 2.0;

    private static final Feature[] DEPS = {
            Feature.TICK, Feature.LAST_SCAN_TICK, Feature.TICKS_SINCE_SCAN,
            Feature.OPPONENT_X, Feature.OPPONENT_Y,
            Feature.OPPONENT_VELOCITY, Feature.OPPONENT_HEADING,
            Feature.BATTLEFIELD_WIDTH, Feature.BATTLEFIELD_HEIGHT
    };
    private static final Feature[] OUTPUTS = {
            Feature.OPPONENT_WALL_HIT_DAMAGE
    };

    public Feature[] getDependencies() {
        return DEPS;
    }

    public Feature[] getOutputFeatures() {
        return OUTPUTS;
    }

    public FileType getFileType() {
        return FileType.TICKS;
    }

    public void process(Whiteboard wb) {
        double tick = wb.getFeature(Feature.TICK);
        double lastScanTick = wb.getFeature(Feature.LAST_SCAN_TICK);

        // Only compute on scan ticks
        if (Double.isNaN(tick) || Double.isNaN(lastScanTick) || tick != lastScanTick) {
            return;
        }

        // Don't override pipeline's exact value
        double existing = wb.getFeature(Feature.OPPONENT_WALL_HIT_DAMAGE);
        if (!Double.isNaN(existing) && existing > 0) {
            return;
        }

        double opX = wb.getFeature(Feature.OPPONENT_X);
        double opY = wb.getFeature(Feature.OPPONENT_Y);
        double bfWidth = wb.getFeature(Feature.BATTLEFIELD_WIDTH);
        double bfHeight = wb.getFeature(Feature.BATTLEFIELD_HEIGHT);

        if (Double.isNaN(opX) || Double.isNaN(opY) ||
                Double.isNaN(bfWidth) || Double.isNaN(bfHeight)) {
            return;
        }

        // Get previous scan's velocity and heading
        double prevVelocity = wb.getPreviousTickFeature(Feature.OPPONENT_VELOCITY);
        double prevHeading = wb.getPreviousTickFeature(Feature.OPPONENT_HEADING);

        if (Double.isNaN(prevVelocity) || Double.isNaN(prevHeading)) {
            return;
        }

        double ticksSinceScan = wb.getFeature(Feature.TICKS_SINCE_SCAN);
        if (Double.isNaN(ticksSinceScan) || ticksSinceScan < 1) {
            return;
        }

        // Compute velocity components (Robocode heading: 0=north, clockwise)
        // vx = velocity * sin(heading), vy = velocity * cos(heading)
        double vx = prevVelocity * Math.sin(prevHeading);
        double vy = prevVelocity * Math.cos(prevHeading);

        // Check each wall and find maximum estimated damage
        double maxDamage = 0;

        // Left wall: x ≈ WALL_MARGIN, hit if moving left (vx < 0)
        if (opX <= WALL_MARGIN + WALL_TOLERANCE && vx < 0) {
            maxDamage = Math.max(maxDamage, estimateWallDamage(-vx, ticksSinceScan));
        }
        // Right wall: x ≈ bfWidth - WALL_MARGIN, hit if moving right (vx > 0)
        if (opX >= bfWidth - WALL_MARGIN - WALL_TOLERANCE && vx > 0) {
            maxDamage = Math.max(maxDamage, estimateWallDamage(vx, ticksSinceScan));
        }
        // Bottom wall: y ≈ WALL_MARGIN, hit if moving down (vy < 0)
        if (opY <= WALL_MARGIN + WALL_TOLERANCE && vy < 0) {
            maxDamage = Math.max(maxDamage, estimateWallDamage(-vy, ticksSinceScan));
        }
        // Top wall: y ≈ bfHeight - WALL_MARGIN, hit if moving up (vy > 0)
        if (opY >= bfHeight - WALL_MARGIN - WALL_TOLERANCE && vy > 0) {
            maxDamage = Math.max(maxDamage, estimateWallDamage(vy, ticksSinceScan));
        }

        if (maxDamage > 0) {
            double current = wb.getFeature(Feature.OPPONENT_WALL_HIT_DAMAGE);
            wb.setFeature(Feature.OPPONENT_WALL_HIT_DAMAGE,
                    (Double.isNaN(current) ? 0 : current) + maxDamage);
        }
    }

    /**
     * Estimate wall-hit damage given the speed toward a wall and available
     * braking time.
     * <p>
     * Conservative estimate: assumes opponent braked maximally for all
     * available ticks. Impact velocity = speed - 2*ticks. If they couldn't
     * fully stop, they hit the wall with the remaining velocity.
     */
    private static double estimateWallDamage(double speedTowardWall, double ticksSinceScan) {
        // Maximum braking over ticksSinceScan ticks
        double maxBraking = DECEL_PER_TICK * ticksSinceScan;
        double impactVelocity = speedTowardWall - maxBraking;

        if (impactVelocity <= 0) {
            // They could have fully stopped — no guaranteed wall hit
            return 0;
        }

        // Robocode wall damage formula: max(abs(velocity) * 0.5 - 1, 0)
        return Math.max(impactVelocity * 0.5 - 1, 0);
    }
}

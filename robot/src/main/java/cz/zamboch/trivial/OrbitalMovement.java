package cz.zamboch.trivial;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.IMovementStrategy;
import cz.zamboch.autopilot.core.strategy.MovementCommand;
import cz.zamboch.autopilot.core.strategy.StrategyParams;
import cz.zamboch.autopilot.core.util.RoboMath;

import java.util.Random;

/**
 * Orbital movement — circles the opponent at the preferred distance.
 * Uses smooth wall avoidance instead of hard direction reversal.
 * Anti-profiling: randomly varies orbit angle and speed to flatten
 * our GF profile.
 */
public final class OrbitalMovement implements IMovementStrategy {

    private static final double WALL_MARGIN = 100;
    /** Minimum ticks between wall-triggered direction reversals. */
    private static final int WALL_FLIP_COOLDOWN = 15;
    /** Minimum ticks between random direction reversals. */
    private static final int FLIP_MIN_TICKS = 15;
    /** Range added to FLIP_MIN_TICKS for random reversal interval (15..35). */
    private static final int FLIP_RANGE_TICKS = 20;
    /** Hysteresis half-width (radians) for forward/backward decision. */
    private static final double AHEAD_HYSTERESIS = 0.15;
    /** Wall smoothing zone: within this distance, orbit angle is deflected. */
    private static final double WALL_SMOOTH_ZONE = 140;
    /** Maximum angular deflection for wall smoothing (~25 degrees). */
    private static final double WALL_SMOOTH_MAX_DEFLECTION = Math.toRadians(25);
    /** Maximum random orbit angle variation (~15 degrees). */
    private static final double ANGLE_JITTER_MAX = Math.toRadians(15);
    /** Minimum speed for velocity oscillation. */
    private static final double MIN_SPEED_AHEAD = 80;
    /** Maximum speed (normal full speed). */
    private static final double MAX_SPEED_AHEAD = 150;
    /** Ticks between random jitter re-rolls. */
    private static final int JITTER_INTERVAL = 12;

    private final Random rng = new Random();
    private int direction = 1; // +1 = clockwise, -1 = counter-clockwise
    private long lastFlipTick = -100;
    /** Next tick at which a random direction flip is allowed. */
    private long nextFlipTick = 0;
    private boolean goingForward = true;
    /** Current random angle offset for anti-profiling. */
    private double angleJitter = 0;
    /** Current speed factor for velocity oscillation. */
    private double speedAhead = MAX_SPEED_AHEAD;
    /** Tick when jitter was last re-rolled. */
    private long lastJitterTick = -100;

    @Override
    public void getCommand(Whiteboard wb, StrategyParams params, MovementCommand out) {
        double bearing = wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS);
        double distance = wb.getFeature(Feature.DISTANCE);
        double ourHeading = wb.getOurHeading();

        // Random direction reversal for unpredictability (anti-profiling)
        if (wb.getTick() >= nextFlipTick) {
            direction = -direction;
            nextFlipTick = wb.getTick() + FLIP_MIN_TICKS + rng.nextInt(FLIP_RANGE_TICKS);
        }

        // Anti-profiling: re-roll angle jitter and speed at intervals
        if (wb.getTick() - lastJitterTick >= JITTER_INTERVAL) {
            angleJitter = (rng.nextDouble() * 2 - 1) * ANGLE_JITTER_MAX;
            // Velocity oscillation: random speed between MIN and MAX
            speedAhead = MIN_SPEED_AHEAD + rng.nextDouble() * (MAX_SPEED_AHEAD - MIN_SPEED_AHEAD);
            lastJitterTick = wb.getTick();
        }

        // Desired angle: perpendicular to opponent, adjusted for distance
        double desiredAngle;
        if (distance > 600) {
            // Too far — angle toward opponent aggressively
            desiredAngle = bearing + direction * Math.PI / 4;
        } else if (distance < 300) {
            // Too close — angle away from opponent aggressively
            desiredAngle = bearing + direction * 3 * Math.PI / 4;
        } else {
            // At preferred distance — orbit perpendicular with jitter
            desiredAngle = bearing + direction * Math.PI / 2 + angleJitter;
        }

        // Smooth wall avoidance: deflect angle away from nearby walls
        desiredAngle = applyWallSmoothing(desiredAngle, wb);

        double turn = RoboMath.normalRelativeAngle(desiredAngle - ourHeading);

        // Hard wall reversal as fallback: reverse if very close and cooldown passed
        double wallDist = wb.hasFeature(Feature.OUR_DIST_TO_WALL_MIN)
                ? wb.getFeature(Feature.OUR_DIST_TO_WALL_MIN) : 200;
        if (wallDist < WALL_MARGIN && (wb.getTick() - lastFlipTick) >= WALL_FLIP_COOLDOWN) {
            direction = -direction;
            lastFlipTick = wb.getTick();
            turn = RoboMath.normalRelativeAngle(
                    bearing + direction * Math.PI / 2 + angleJitter - ourHeading);
        }

        // Move with hysteresis to prevent oscillation, using varied speed
        double absTurn = Math.abs(turn);
        double threshold = goingForward
                ? Math.PI / 2 + AHEAD_HYSTERESIS
                : Math.PI / 2 - AHEAD_HYSTERESIS;

        double ahead;
        if (goingForward) {
            if (absTurn > threshold) {
                goingForward = false;
                ahead = -speedAhead;
            } else {
                ahead = speedAhead;
            }
        } else {
            if (absTurn < threshold) {
                goingForward = true;
                ahead = speedAhead;
            } else {
                ahead = -speedAhead;
            }
        }

        if (ahead < 0) {
            turn = RoboMath.normalRelativeAngle(turn + Math.PI);
        }

        out.set(ahead, turn);
    }

    /**
     * Smoothly deflect the target angle away from nearby walls.
     * Computes a repulsive push vector from all walls within
     * {@link #WALL_SMOOTH_ZONE} and blends it into the target angle.
     */
    private double applyWallSmoothing(double targetAngle, Whiteboard wb) {
        double x = wb.getOurX();
        double y = wb.getOurY();
        int bfW = wb.getBattlefieldWidth();
        int bfH = wb.getBattlefieldHeight();

        double pushX = 0;
        double pushY = 0;
        double distLeft = x - 18;
        double distRight = bfW - 18 - x;
        double distBottom = y - 18;
        double distTop = bfH - 18 - y;

        if (distLeft < WALL_SMOOTH_ZONE) {
            pushX += (WALL_SMOOTH_ZONE - distLeft) / WALL_SMOOTH_ZONE;
        }
        if (distRight < WALL_SMOOTH_ZONE) {
            pushX -= (WALL_SMOOTH_ZONE - distRight) / WALL_SMOOTH_ZONE;
        }
        if (distBottom < WALL_SMOOTH_ZONE) {
            pushY += (WALL_SMOOTH_ZONE - distBottom) / WALL_SMOOTH_ZONE;
        }
        if (distTop < WALL_SMOOTH_ZONE) {
            pushY -= (WALL_SMOOTH_ZONE - distTop) / WALL_SMOOTH_ZONE;
        }

        double pushMag = Math.hypot(pushX, pushY);
        if (pushMag < 0.01) {
            return targetAngle;
        }
        if (pushMag > 1.0) {
            pushX /= pushMag;
            pushY /= pushMag;
            pushMag = 1.0;
        }

        double pushAngle = Math.atan2(pushX, pushY);
        double deflection = pushMag * WALL_SMOOTH_MAX_DEFLECTION;
        double diff = RoboMath.normalRelativeAngle(pushAngle - targetAngle);
        double adjust = Math.signum(diff) * Math.min(Math.abs(diff), deflection);
        return targetAngle + adjust;
    }

    @Override
    public String getName() {
        return "orbital";
    }
}

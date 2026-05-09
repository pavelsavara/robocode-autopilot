package cz.zamboch.trivial;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.IMovementStrategy;
import cz.zamboch.autopilot.core.strategy.MovementCommand;
import cz.zamboch.autopilot.core.strategy.StrategyParams;
import cz.zamboch.autopilot.core.util.RoboMath;

/**
 * Orbital movement — circles the opponent at the preferred distance.
 * Reverses direction when approaching a wall, with cooldown to prevent
 * per-tick oscillation.
 */
public final class OrbitalMovement implements IMovementStrategy {

    private static final double WALL_MARGIN = 80;
    /** Minimum ticks between wall-triggered direction reversals. */
    private static final int WALL_FLIP_COOLDOWN = 25;

    private int direction = 1; // +1 = clockwise, -1 = counter-clockwise
    private long lastFlipTick = -100;

    @Override
    public void getCommand(Whiteboard wb, StrategyParams params, MovementCommand out) {
        double bearing = wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS);
        double distance = wb.getFeature(Feature.DISTANCE);
        double ourHeading = wb.getOurHeading();

        // Desired angle: perpendicular to opponent, adjusted for distance
        double desiredAngle;
        if (distance > params.preferredDistance + 50) {
            // Too far — angle toward opponent (45 degrees off perpendicular)
            desiredAngle = bearing + direction * Math.PI / 4;
        } else if (distance < params.preferredDistance - 50) {
            // Too close — angle away from opponent
            desiredAngle = bearing + direction * 3 * Math.PI / 4;
        } else {
            // At preferred distance — orbit perpendicular
            desiredAngle = bearing + direction * Math.PI / 2;
        }

        double turn = RoboMath.normalRelativeAngle(desiredAngle - ourHeading);

        // Wall avoidance: reverse direction if near a wall, with cooldown
        double wallDist = wb.hasFeature(Feature.OUR_DIST_TO_WALL_MIN)
                ? wb.getFeature(Feature.OUR_DIST_TO_WALL_MIN) : 200;
        if (wallDist < WALL_MARGIN && (wb.getTick() - lastFlipTick) >= WALL_FLIP_COOLDOWN) {
            direction = -direction;
            lastFlipTick = wb.getTick();
            turn = RoboMath.normalRelativeAngle(
                    bearing + direction * Math.PI / 2 - ourHeading);
        }

        // Move at max speed — large ahead value maintains velocity
        double ahead;
        if (Math.abs(turn) > Math.PI / 2) {
            // Reverse: turn the short way and go backward
            turn = RoboMath.normalRelativeAngle(turn + Math.PI);
            ahead = -150;
        } else {
            ahead = 150;
        }

        out.set(ahead, turn);
    }

    @Override
    public String getName() {
        return "orbital";
    }
}

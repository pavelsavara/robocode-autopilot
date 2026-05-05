package cz.zamboch.trivial;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.IMovementStrategy;
import cz.zamboch.autopilot.core.strategy.MovementCommand;
import cz.zamboch.autopilot.core.strategy.StrategyParams;
import cz.zamboch.autopilot.core.util.RoboMath;

/**
 * Orbital movement — circles the opponent at the preferred distance.
 * Reverses direction when approaching a wall.
 */
public final class OrbitalMovement implements IMovementStrategy {

    private static final double WALL_MARGIN = 50;
    private int direction = 1; // +1 = clockwise, -1 = counter-clockwise

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

        // Wall avoidance: reverse direction if we're near a wall
        double wallDist = wb.getFeature(Feature.OUR_DIST_TO_WALL_MIN);
        if (wallDist < WALL_MARGIN) {
            direction = -direction;
            turn = RoboMath.normalRelativeAngle(
                    bearing + direction * Math.PI / 2 - ourHeading);
        }

        // Move ahead if facing roughly correct direction, else turn first
        double ahead;
        if (Math.abs(turn) > Math.PI / 2) {
            // Reverse: turn the short way and go backward
            turn = RoboMath.normalRelativeAngle(turn + Math.PI);
            ahead = -100;
        } else {
            ahead = 100;
        }

        out.set(ahead, turn);
    }

    @Override
    public String getName() {
        return "orbital";
    }
}

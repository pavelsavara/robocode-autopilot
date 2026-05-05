package cz.zamboch.trivial;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.IMovementStrategy;
import cz.zamboch.autopilot.core.strategy.MovementCommand;
import cz.zamboch.autopilot.core.strategy.StrategyParams;

/**
 * Stop-and-go movement — stops when the opponent fires,
 * moves between fires.
 */
public final class StopAndGoMovement implements IMovementStrategy {

    private int direction = 1;

    @Override
    public void getCommand(Whiteboard wb, StrategyParams params, MovementCommand out) {
        double fireProb = wb.getFeature(Feature.PREDICTED_OPPONENT_FIRES_3);

        // If opponent likely firing, stop
        if (fireProb > 0.3 || wb.getFeature(Feature.OPPONENT_FIRED) > 0.5) {
            // Stop — but reverse direction for next move
            direction = -direction;
            out.set(0, 0);
            return;
        }

        // Move perpendicular to bearing
        double bearing = wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS);
        double ourHeading = wb.getOurHeading();
        double perpAngle = bearing + direction * Math.PI / 2;
        double turn = perpAngle - ourHeading;
        turn = cz.zamboch.autopilot.core.util.RoboMath.normalRelativeAngle(turn);

        double ahead;
        if (Math.abs(turn) > Math.PI / 2) {
            turn = cz.zamboch.autopilot.core.util.RoboMath.normalRelativeAngle(turn + Math.PI);
            ahead = -80;
        } else {
            ahead = 80;
        }

        out.set(ahead, turn);
    }

    @Override
    public String getName() {
        return "stop-and-go";
    }
}

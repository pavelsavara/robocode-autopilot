package cz.zamboch.autopilot.core.movement;

import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.IMovementStrategy;
import cz.zamboch.autopilot.core.strategy.MovementCommand;
import cz.zamboch.autopilot.core.strategy.StrategyParams;
import cz.zamboch.autopilot.core.util.RoboMath;

/**
 * Wave-surf movement strategy: uses {@link PathPlanner} to find the
 * lowest-danger reachable position, then computes the movement command
 * to head toward it.
 *
 * <p>This replaces the trivial orbital/random/stop-and-go strategies
 * with physics-based wave-surfing. The danger scoring uses the
 * pluggable {@link IPositionDanger} and {@link IWaveDanger} interfaces.</p>
 */
public final class WaveSurfMovement implements IMovementStrategy {

    private final PathPlanner planner;

    public WaveSurfMovement(PathPlanner planner) {
        this.planner = planner;
    }

    @Override
    public void getCommand(Whiteboard wb, StrategyParams params, MovementCommand out) {
        CandidatePosition target = planner.plan(wb, params);

        if (target == null) {
            // No candidates — stop and wait
            out.set(0, 0);
            return;
        }

        // Compute movement toward target position
        double ourX = wb.getOurX();
        double ourY = wb.getOurY();
        double ourHeading = wb.getOurHeading();

        double dx = target.x - ourX;
        double dy = target.y - ourY;
        double targetAngle = Math.atan2(dx, dy); // Robocode heading convention

        double turn = RoboMath.normalRelativeAngle(targetAngle - ourHeading);

        // If target is behind us, go backward
        double ahead;
        if (Math.abs(turn) > Math.PI / 2) {
            turn = RoboMath.normalRelativeAngle(turn + Math.PI);
            ahead = -Math.hypot(dx, dy);
        } else {
            ahead = Math.hypot(dx, dy);
        }

        // Clamp ahead to reasonable values
        if (ahead > 100) ahead = 100;
        if (ahead < -100) ahead = -100;

        out.set(ahead, turn);
    }

    @Override
    public String getName() {
        return "wave-surf";
    }
}

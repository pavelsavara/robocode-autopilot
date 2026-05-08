package cz.zamboch.autopilot.core.movement;

import cz.zamboch.autopilot.core.Feature;
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
 * <p>When no waves are active but the fire timing model predicts an
 * imminent fire (P > 0.7), pre-emptively starts lateral movement to
 * gain ~2 ticks of dodge time before the energy drop is detected.</p>
 */
public final class WaveSurfMovement implements IMovementStrategy {

    private final PathPlanner planner;
    private int preemptiveDir = 1;

    public WaveSurfMovement(PathPlanner planner) {
        this.planner = planner;
    }

    @Override
    public void getCommand(Whiteboard wb, StrategyParams params, MovementCommand out) {
        CandidatePosition target = planner.plan(wb, params);

        if (target == null) {
            // No wave candidates — check if fire is predicted
            if (shouldPreemptiveDodge(wb)) {
                preemptiveLateralMove(wb, out);
            } else {
                out.set(0, 0);
            }
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

    /**
     * Should we start dodging before a wave is detected?
     * Yes if the fire timing model predicts high probability of fire
     * within 3 ticks and there are no active opponent waves.
     */
    private boolean shouldPreemptiveDodge(Whiteboard wb) {
        if (!wb.hasFeature(Feature.PREDICTED_OPPONENT_FIRES_3)) {
            return false;
        }
        double fireProb = wb.getFeature(Feature.PREDICTED_OPPONENT_FIRES_3);
        return fireProb > 0.7 && wb.getOpponentWaves().isEmpty();
    }

    /**
     * Pre-emptive lateral movement: move perpendicular to the bearing
     * line before a wave is detected. Alternates direction to be
     * unpredictable. Gains ~2 ticks of dodge time at close range.
     */
    private void preemptiveLateralMove(Whiteboard wb, MovementCommand out) {
        if (!wb.hasFeature(Feature.BEARING_TO_OPPONENT_ABS)) {
            out.set(0, 0);
            return;
        }

        double bearing = wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS);
        double ourHeading = wb.getOurHeading();

        // Move perpendicular to bearing, alternating direction
        double perpAngle = bearing + preemptiveDir * Math.PI / 2;
        double turn = RoboMath.normalRelativeAngle(perpAngle - ourHeading);

        double ahead;
        if (Math.abs(turn) > Math.PI / 2) {
            turn = RoboMath.normalRelativeAngle(turn + Math.PI);
            ahead = -60;
        } else {
            ahead = 60;
        }

        // Reverse direction occasionally for unpredictability
        if (wb.getTick() % 30 == 0) {
            preemptiveDir = -preemptiveDir;
        }

        out.set(ahead, turn);
    }

    @Override
    public String getName() {
        return "wave-surf";
    }
}

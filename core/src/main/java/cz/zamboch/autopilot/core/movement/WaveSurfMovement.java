package cz.zamboch.autopilot.core.movement;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.IMovementStrategy;
import cz.zamboch.autopilot.core.strategy.MovementCommand;
import cz.zamboch.autopilot.core.strategy.StrategyParams;
import cz.zamboch.autopilot.core.util.RoboMath;

import java.util.Random;

/**
 * Wave-surf movement strategy: uses {@link PathPlanner} to find the
 * lowest-danger reachable position, then computes the movement command
 * to head toward it.
 *
 * <p>When no waves are active but the fire timing model predicts an
 * imminent fire, pre-emptively starts lateral movement proportional
 * to P(fire), ramping from 0 at P=0.3 to full speed at P=0.8.
 * Direction reverses at random intervals (15-45 ticks) for
 * unpredictability.</p>
 */
public final class WaveSurfMovement implements IMovementStrategy {

    private static final double DODGE_RAMP_LOW = 0.3;
    private static final double DODGE_RAMP_HIGH = 0.8;
    private static final int FLIP_MIN_TICKS = 15;
    private static final int FLIP_RANGE_TICKS = 31; // 15..45 inclusive

    private final PathPlanner planner;
    private final Random rng = new Random();
    private int preemptiveDir = 1;
    private long nextFlipTick = 0;

    public WaveSurfMovement(PathPlanner planner) {
        this.planner = planner;
    }

    @Override
    public void getCommand(Whiteboard wb, StrategyParams params, MovementCommand out) {
        CandidatePosition target = planner.plan(wb, params);

        if (target == null) {
            // No wave candidates — check if fire is predicted
            double dodgeScale = getDodgeScale(wb);
            if (dodgeScale > 0) {
                preemptiveLateralMove(wb, out, dodgeScale);
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
     * Returns the proportional dodge scale [0,1] based on fire probability.
     * Ramps linearly from 0 at P=0.3 to 1 at P=0.8.
     * Returns 0 (no dodge) if no prediction available or waves are active.
     */
    private double getDodgeScale(Whiteboard wb) {
        if (!wb.hasFeature(Feature.PREDICTED_OPPONENT_FIRES_3)) {
            return 0;
        }
        if (!wb.getOpponentWaves().isEmpty()) {
            return 0;
        }
        double fireProb = wb.getFeature(Feature.PREDICTED_OPPONENT_FIRES_3);
        double scale = 2.0 * (fireProb - DODGE_RAMP_LOW);
        return Math.max(0, Math.min(1, scale));
    }

    /**
     * Pre-emptive lateral movement proportional to predicted fire probability.
     * Moves perpendicular to bearing line, with speed ramping from 0 to 60
     * as P(fire) goes from 0.3 to 0.8. Direction reverses at random
     * intervals (15-45 ticks) for unpredictability.
     */
    private void preemptiveLateralMove(Whiteboard wb, MovementCommand out, double scale) {
        if (!wb.hasFeature(Feature.BEARING_TO_OPPONENT_ABS) || scale <= 0) {
            out.set(0, 0);
            return;
        }

        double bearing = wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS);
        double ourHeading = wb.getOurHeading();

        // Move perpendicular to bearing, alternating direction
        double perpAngle = bearing + preemptiveDir * Math.PI / 2;
        double turn = RoboMath.normalRelativeAngle(perpAngle - ourHeading);

        double fullAhead = 60;
        double ahead;
        if (Math.abs(turn) > Math.PI / 2) {
            turn = RoboMath.normalRelativeAngle(turn + Math.PI);
            ahead = -fullAhead * scale;
        } else {
            ahead = fullAhead * scale;
        }

        // Reverse direction at random intervals for unpredictability
        if (wb.getTick() >= nextFlipTick) {
            preemptiveDir = -preemptiveDir;
            nextFlipTick = wb.getTick() + FLIP_MIN_TICKS + rng.nextInt(FLIP_RANGE_TICKS);
        }

        out.set(ahead, turn);
    }

    @Override
    public String getName() {
        return "wave-surf";
    }
}

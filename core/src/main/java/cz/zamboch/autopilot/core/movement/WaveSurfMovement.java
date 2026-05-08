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
    /** Re-evaluate target at most every N ticks to prevent oscillation. */
    private static final int COMMIT_TICKS = 5;

    private final PathPlanner planner;
    private final Random rng = new Random();
    private int preemptiveDir = 1;
    private long nextFlipTick = 0;
    /** Committed target angle — prevents oscillation by holding direction. */
    private double committedAngle = Double.NaN;
    /** Tick when committed target was last updated. */
    private long commitTick = -100;
    /** Number of opponent waves when we last committed. */
    private int commitWaveCount = 0;

    public WaveSurfMovement(PathPlanner planner) {
        this.planner = planner;
    }

    @Override
    public void getCommand(Whiteboard wb, StrategyParams params, MovementCommand out) {
        CandidatePosition target = planner.plan(wb, params);

        if (target == null) {
            // No wave candidates — orbital movement
            committedAngle = Double.NaN;
            double dodgeScale = getDodgeScale(wb);
            if (dodgeScale > 0) {
                preemptiveLateralMove(wb, out, dodgeScale);
            } else {
                defaultLateralMove(wb, out);
            }
            return;
        }

        // Direction commitment: only re-evaluate when a new wave appears
        // or after COMMIT_TICKS have elapsed. This prevents oscillation.
        int currentWaves = wb.getOpponentWaves().size();
        boolean shouldRecommit = Double.isNaN(committedAngle)
                || currentWaves != commitWaveCount
                || (wb.getTick() - commitTick) >= COMMIT_TICKS;

        if (shouldRecommit) {
            double dx = target.x - wb.getOurX();
            double dy = target.y - wb.getOurY();
            committedAngle = Math.atan2(dx, dy);
            commitTick = wb.getTick();
            commitWaveCount = currentWaves;
        }

        double ourHeading = wb.getOurHeading();
        double turn = RoboMath.normalRelativeAngle(committedAngle - ourHeading);

        double ahead;
        if (Math.abs(turn) > Math.PI / 2) {
            turn = RoboMath.normalRelativeAngle(turn + Math.PI);
            ahead = -150;
        } else {
            ahead = 150;
        }

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
     * Moves perpendicular to bearing line at max speed when P(fire) is high.
     * Direction reverses at random intervals (15-45 ticks) for unpredictability.
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

        // Always use large ahead value for max speed
        double ahead;
        if (Math.abs(turn) > Math.PI / 2) {
            turn = RoboMath.normalRelativeAngle(turn + Math.PI);
            ahead = -150;
        } else {
            ahead = 150;
        }

        // Reverse direction at random intervals for unpredictability
        if (wb.getTick() >= nextFlipTick) {
            preemptiveDir = -preemptiveDir;
            nextFlipTick = wb.getTick() + FLIP_MIN_TICKS + rng.nextInt(FLIP_RANGE_TICKS);
        }

        out.set(ahead, turn);
    }

    /**
     * Default lateral movement when no waves are active and no fire is predicted.
     * Orbits the opponent perpendicular to the bearing line at max speed,
     * reversing direction randomly to avoid predictability.
     */
    private void defaultLateralMove(Whiteboard wb, MovementCommand out) {
        if (!wb.hasFeature(Feature.BEARING_TO_OPPONENT_ABS)) {
            out.set(0, 0);
            return;
        }

        double bearing = wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS);
        double ourHeading = wb.getOurHeading();

        // Move perpendicular to bearing (orbit the opponent) at max speed
        double perpAngle = bearing + preemptiveDir * Math.PI / 2;
        double turn = RoboMath.normalRelativeAngle(perpAngle - ourHeading);

        double ahead;
        if (Math.abs(turn) > Math.PI / 2) {
            turn = RoboMath.normalRelativeAngle(turn + Math.PI);
            ahead = -150;
        } else {
            ahead = 150;
        }

        // Random direction reversal for unpredictability
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

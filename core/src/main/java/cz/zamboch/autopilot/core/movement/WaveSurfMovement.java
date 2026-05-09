package cz.zamboch.autopilot.core.movement;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.WaveRecord;
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
 * Direction reverses at random intervals (25-55 ticks) for
 * unpredictability.</p>
 *
 * <p>Commitment: once a dodge direction is chosen under imminent wave,
 * the direction is held for at least {@link #MIN_COMMIT_TICKS} ticks
 * to prevent oscillation and maintain max speed.</p>
 */
public final class WaveSurfMovement implements IMovementStrategy {

    private static final double DODGE_RAMP_LOW = 0.3;
    private static final double DODGE_RAMP_HIGH = 0.8;
    /** Minimum ticks between random direction reversals. */
    static final int FLIP_MIN_TICKS = 25;
    /** Range added to FLIP_MIN_TICKS for random reversal interval (25..55). */
    static final int FLIP_RANGE_TICKS = 31;
    /** Minimum ticks to hold a dodge direction under imminent wave. */
    static final int MIN_COMMIT_TICKS = 2;
    /** Maximum ticks to hold a dodge direction under imminent wave. */
    static final int MAX_COMMIT_TICKS = 8;
    /** Wall distance threshold that triggers a direction reversal. */
    private static final double WALL_REVERSE_DIST = 60;
    /**
     * Hysteresis half-width (radians) for the forward/backward decision.
     * Prevents per-tick oscillation when turn angle hovers near PI/2.
     */
    static final double AHEAD_HYSTERESIS = 0.15;

    private final PathPlanner planner;
    private final Random rng = new Random();
    private int preemptiveDir = 1;
    private long nextFlipTick = 0;
    /** Whether the robot is currently going forward (true) or backward (false). */
    private boolean goingForward = true;
    /** How many ticks the current dodge commitment lasts. */
    private int commitDuration = MIN_COMMIT_TICKS;
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
        // Always maintain high-speed lateral movement.
        // Priority: imminent wave dodge > pre-emptive dodge > default orbit.

        boolean hasImminentWave = false;
        if (!wb.getOpponentWaves().isEmpty()) {
            for (int i = 0; i < wb.getOpponentWaves().size(); i++) {
                WaveRecord w = wb.getOpponentWaves().get(i);
                double dist = Math.hypot(wb.getOurX() - w.originX, wb.getOurY() - w.originY);
                double remaining = dist - w.radius(wb.getTick());
                double ticksUntil = w.bulletSpeed > 0 ? remaining / w.bulletSpeed : 99;
                if (ticksUntil < 12) {
                    hasImminentWave = true;
                    break;
                }
            }
        }

        if (hasImminentWave) {
            CandidatePosition target = planner.plan(wb, params);
            if (target != null) {
                double dx = target.x - wb.getOurX();
                double dy = target.y - wb.getOurY();
                double targetAngle = Math.atan2(dx, dy);

                // Proportional commitment: scale with wave time-to-impact.
                // Close waves -> short commit (react quickly).
                // Far waves -> longer commit (maintain speed toward target).
                int waveCount = wb.getOpponentWaves().size();
                long elapsed = wb.getTick() - commitTick;
                if (elapsed >= commitDuration || waveCount != commitWaveCount
                        || Double.isNaN(committedAngle)) {
                    committedAngle = targetAngle;
                    commitTick = wb.getTick();
                    commitWaveCount = waveCount;
                    commitDuration = computeCommitDuration(wb);
                }

                double ourHeading = wb.getOurHeading();
                double turn = RoboMath.normalRelativeAngle(committedAngle - ourHeading);
                double ahead = computeAhead(turn);
                turn = adjustTurnForReverse(turn, ahead);
                out.set(ahead, turn);
                return;
            }
        }

        // Pre-emptive dodge: fire timing model predicts imminent fire
        double dodgeScale = getDodgeScale(wb);
        if (dodgeScale > 0) {
            preemptiveLateralMove(wb, out, dodgeScale);
            return;
        }

        // Default: high-speed lateral orbit with wall awareness
        defaultLateralMove(wb, out);
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
     * Direction does NOT randomly flip during a pre-emptive dodge — only at
     * natural flip intervals when transitioning out of pre-emptive mode.
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

        double ahead = computeAhead(turn);
        turn = adjustTurnForReverse(turn, ahead);

        // No random direction flip during pre-emptive dodge — maintain commitment.
        // Flips only happen in defaultLateralMove or when wall-proximity triggers.

        out.set(ahead, turn);
    }

    /**
     * Default lateral movement when no waves are active and no fire is predicted.
     * Wall-aware: reverses direction when approaching a wall, with cooldown
     * to prevent per-tick oscillation near walls.
     */
    private void defaultLateralMove(Whiteboard wb, MovementCommand out) {
        if (!wb.hasFeature(Feature.BEARING_TO_OPPONENT_ABS)) {
            out.set(0, 0);
            return;
        }

        double bearing = wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS);
        double ourHeading = wb.getOurHeading();

        // Check wall proximity — reverse direction if heading toward a wall,
        // but only if we haven't flipped recently (prevents per-tick oscillation)
        double wallDist = wb.hasFeature(Feature.OUR_DIST_TO_WALL_MIN)
                ? wb.getFeature(Feature.OUR_DIST_TO_WALL_MIN) : 200;
        if (wallDist < WALL_REVERSE_DIST && wb.getTick() >= nextFlipTick) {
            preemptiveDir = -preemptiveDir;
            nextFlipTick = wb.getTick() + FLIP_MIN_TICKS + rng.nextInt(FLIP_RANGE_TICKS);
        }

        // Move perpendicular to bearing (orbit the opponent)
        double perpAngle = bearing + preemptiveDir * Math.PI / 2;
        double turn = RoboMath.normalRelativeAngle(perpAngle - ourHeading);

        double ahead = computeAhead(turn);
        turn = adjustTurnForReverse(turn, ahead);

        // Random direction reversal for unpredictability
        maybeFlipDirection(wb);

        out.set(ahead, turn);
    }

    /**
     * Flip lateral direction if enough ticks have passed since the last flip.
     * Shared by defaultLateralMove and preemptiveLateralMove.
     */
    private void maybeFlipDirection(Whiteboard wb) {
        if (wb.getTick() >= nextFlipTick) {
            preemptiveDir = -preemptiveDir;
            nextFlipTick = wb.getTick() + FLIP_MIN_TICKS + rng.nextInt(FLIP_RANGE_TICKS);
        }
    }

    /** Visible for testing: current lateral direction (+1 or -1). */
    int getPreemptiveDir() { return preemptiveDir; }

    /** Visible for testing: tick at which next direction flip is allowed. */
    long getNextFlipTick() { return nextFlipTick; }

    /** Visible for testing: whether currently going forward. */
    boolean isGoingForward() { return goingForward; }

    /** Visible for testing: current dodge commitment duration. */
    int getCommitDuration() { return commitDuration; }

    /**
     * Compute ahead value with hysteresis to prevent per-tick oscillation.
     * When the turn angle is near PI/2, the previous forward/backward decision
     * is maintained unless the angle moves beyond the hysteresis band.
     * Each unnecessary direction reversal costs ~12 ticks of sub-max-speed travel.
     */
    private double computeAhead(double turn) {
        double absTurn = Math.abs(turn);
        double threshold = goingForward
                ? Math.PI / 2 + AHEAD_HYSTERESIS   // going forward: only switch to backward when clearly past PI/2
                : Math.PI / 2 - AHEAD_HYSTERESIS;  // going backward: only switch to forward when clearly below PI/2

        if (goingForward) {
            if (absTurn > threshold) {
                goingForward = false;
                return -150;
            }
            return 150;
        } else {
            if (absTurn < threshold) {
                goingForward = true;
                return 150;
            }
            return -150;
        }
    }

    /**
     * If going backward, adjust the turn angle to point the short way around.
     */
    private static double adjustTurnForReverse(double turn, double ahead) {
        if (ahead < 0) {
            return RoboMath.normalRelativeAngle(turn + Math.PI);
        }
        return turn;
    }

    /**
     * Compute dodge commitment duration proportional to the closest wave's
     * time-to-impact. Close waves get short commitment (react quickly);
     * far waves get longer commitment (maintain speed toward target).
     *
     * @return commitment duration in ticks, clamped to [MIN_COMMIT_TICKS, MAX_COMMIT_TICKS]
     */
    private int computeCommitDuration(Whiteboard wb) {
        double minTicksUntil = 99;
        for (int i = 0; i < wb.getOpponentWaves().size(); i++) {
            WaveRecord w = wb.getOpponentWaves().get(i);
            double dist = Math.hypot(wb.getOurX() - w.originX, wb.getOurY() - w.originY);
            double remaining = dist - w.radius(wb.getTick());
            double ticksUntil = w.bulletSpeed > 0 ? remaining / w.bulletSpeed : 99;
            if (ticksUntil < minTicksUntil) {
                minTicksUntil = ticksUntil;
            }
        }
        // commit = ticksUntilImpact - 2 (leave 2 ticks for final adjustment)
        int commit = (int) (minTicksUntil - 2);
        return Math.max(MIN_COMMIT_TICKS, Math.min(MAX_COMMIT_TICKS, commit));
    }

    @Override
    public String getName() {
        return "wave-surf";
    }
}

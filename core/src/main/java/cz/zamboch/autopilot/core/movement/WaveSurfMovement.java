package cz.zamboch.autopilot.core.movement;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.WaveRecord;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.IMovementStrategy;
import cz.zamboch.autopilot.core.strategy.MovementCommand;
import cz.zamboch.autopilot.core.strategy.StrategyParams;
import cz.zamboch.autopilot.core.util.RoboMath;

import java.util.List;
import java.util.Random;

/**
 * Wave-surf movement strategy: uses {@link PathPlanner} to find the
 * lowest-danger reachable position, then computes the movement command
 * to head toward it.
 *
 * <p>When no waves are active but the fire timing model predicts an
 * imminent fire, pre-emptively starts lateral movement proportional
 * to P(fire), ramping from 0 at P=0.3 to full speed at P=0.8.
 * Direction reverses at random intervals (15-35 ticks) for
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
    static final int FLIP_MIN_TICKS = 15;
    /** Range added to FLIP_MIN_TICKS for random reversal interval (15..35). */
    static final int FLIP_RANGE_TICKS = 20;
    /** Minimum ticks to hold a dodge direction under imminent wave. */
    static final int MIN_COMMIT_TICKS = 2;
    /** Maximum ticks to hold a dodge direction under imminent wave. */
    static final int MAX_COMMIT_TICKS = 8;
    /** Wall distance threshold that triggers a direction reversal. */
    private static final double WALL_REVERSE_DIST = 100;
    /** Wall smoothing zone: within this distance, orbit angle is blended away from wall. */
    private static final double WALL_SMOOTH_ZONE = 180;
    /** Maximum angular deflection (radians) for wall smoothing (~25 degrees). */
    private static final double WALL_SMOOTH_MAX_DEFLECTION = Math.toRadians(25);
    /**
     * Hysteresis half-width (radians) for the forward/backward decision.
     * Prevents per-tick oscillation when turn angle hovers near PI/2.
     */
    static final double AHEAD_HYSTERESIS = 0.15;
    /** Minimum lateral speed for velocity oscillation. */
    private static final double MIN_SPEED_AHEAD = 80;
    /** Maximum lateral speed (full speed). */
    private static final double MAX_SPEED_AHEAD = 150;
    /** Minimum ticks between speed changes for velocity oscillation. */
    private static final int SPEED_CHANGE_MIN_TICKS = 8;
    /** Range added to SPEED_CHANGE_MIN_TICKS for random interval (8..15). */
    private static final int SPEED_CHANGE_RANGE_TICKS = 8;
    /** Minimum ticks between VCS-guided orbital direction re-evaluations. */
    static final int DIR_EVAL_INTERVAL = 8;
    /** Danger difference threshold to change orbital direction (hysteresis). */
    private static final double DIR_CHANGE_THRESHOLD = 0.03;
    /** Ticks-to-impact threshold for full wave surf activation. */
    private static final double IMMINENT_TICKS = 20;
    /** Ticks-to-impact threshold for semi-imminent positioning (partial commitment). */
    private static final double SEMI_IMMINENT_TICKS = 30;
    /** Max robot speed for position projection (px/tick). */
    private static final double MAX_SPEED = 8.0;
    /** Robot half-size for wall clamping in projections. */
    private static final int WALL_MARGIN = 18;

    private final PathPlanner planner;
    private final IWaveDanger waveDanger;
    private final Random rng = new Random();
    private int preemptiveDir = 1;
    private long nextFlipTick = 0;
    /** Per-round angular offset for anti-adaptation (radians, [-PI/6, +PI/6]). */
    private double roundOffset = 0;
    /** Pre-allocated candidates for VCS-guided direction evaluation. */
    private final CandidatePosition cwCandidate = new CandidatePosition();
    private final CandidatePosition ccwCandidate = new CandidatePosition();
    /** Tick of last VCS direction evaluation. */
    private long lastDirEvalTick = -100;
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
    /** Current speed for velocity oscillation (varies between MIN and MAX). */
    private double currentSpeed = MAX_SPEED_AHEAD;
    /** Tick at which the next speed change is allowed. */
    private long nextSpeedChangeTick = 0;

    public WaveSurfMovement(PathPlanner planner, IWaveDanger waveDanger) {
        this.planner = planner;
        this.waveDanger = waveDanger;
    }

    @Override
    public void onRoundStart(int round) {
        // Re-seed RNG per round so flip/speed patterns differ each round
        rng.setSeed(round * 31L + System.nanoTime());
        // Random orbit angle offset: [-PI/6, +PI/6] (~±30 degrees)
        roundOffset = (rng.nextDouble() * 2 - 1) * Math.PI / 6;
    }

    @Override
    public void getCommand(Whiteboard wb, StrategyParams params, MovementCommand out) {
        // Always maintain high-speed lateral movement.
        // Priority: imminent wave dodge > pre-emptive dodge > default orbit.

        boolean hasImminentWave = false;
        boolean hasSemiImminentWave = false;
        if (!wb.getOpponentWaves().isEmpty()) {
            for (int i = 0; i < wb.getOpponentWaves().size(); i++) {
                WaveRecord w = wb.getOpponentWaves().get(i);
                double dist = Math.hypot(wb.getOurX() - w.originX, wb.getOurY() - w.originY);
                double remaining = dist - w.radius(wb.getTick());
                double ticksUntil = w.bulletSpeed > 0 ? remaining / w.bulletSpeed : 99;
                if (ticksUntil < IMMINENT_TICKS) {
                    hasImminentWave = true;
                    break;
                } else if (ticksUntil < SEMI_IMMINENT_TICKS) {
                    hasSemiImminentWave = true;
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

        // Semi-imminent wave: start moving toward the safer side using PathPlanner
        // but with reduced commitment (blend with orbital movement).
        if (hasSemiImminentWave) {
            CandidatePosition target = planner.plan(wb, params);
            if (target != null) {
                double dx = target.x - wb.getOurX();
                double dy = target.y - wb.getOurY();
                double plannerAngle = Math.atan2(dx, dy);
                double bearing = wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS);
                double orbitAngle = bearing + preemptiveDir * Math.PI / 2 + roundOffset;
                // Blend 40% planner + 60% orbit — start positioning without full commitment
                double blendedAngle = blendAngles(orbitAngle, plannerAngle, 0.4);
                blendedAngle = applyWallSmoothing(blendedAngle, wb);
                double ourHeading = wb.getOurHeading();
                double turn = RoboMath.normalRelativeAngle(blendedAngle - ourHeading);
                double ahead = computeAhead(turn);
                turn = adjustTurnForReverse(turn, ahead);
                out.set(ahead, turn);
                return;
            }
        }

        // VCS-guided direction: when waves exist but not imminent, choose the
        // safer orbital direction (CW vs CCW) based on VCS wave danger.
        if (!wb.getOpponentWaves().isEmpty()) {
            updateOrbitDirection(wb);
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
     * Choose the safer orbital direction (CW vs CCW) based on VCS wave danger.
     * Projects robot positions for both directions to where the closest wave
     * will arrive, then scores each projected position against all active waves
     * using the VCS histograms.
     *
     * <p>Rate-limited to once per {@link #DIR_EVAL_INTERVAL} ticks to prevent
     * flutter. Requires the danger difference to exceed {@link #DIR_CHANGE_THRESHOLD}
     * before switching direction (hysteresis).</p>
     */
    private void updateOrbitDirection(Whiteboard wb) {
        if (wb.getTick() - lastDirEvalTick < DIR_EVAL_INTERVAL) {
            return;
        }
        if (!wb.hasFeature(Feature.BEARING_TO_OPPONENT_ABS)) {
            return;
        }

        List<WaveRecord> waves = wb.getOpponentWaves();
        if (waves.isEmpty()) {
            return;
        }

        lastDirEvalTick = wb.getTick();

        // Find closest wave's time-to-impact for projection distance
        double minTicksUntil = 99;
        for (int i = 0; i < waves.size(); i++) {
            WaveRecord w = waves.get(i);
            double dist = Math.hypot(wb.getOurX() - w.originX, wb.getOurY() - w.originY);
            double remaining = dist - w.radius(wb.getTick());
            double ticksUntil = w.bulletSpeed > 0 ? remaining / w.bulletSpeed : 99;
            if (ticksUntil > 0 && ticksUntil < minTicksUntil) {
                minTicksUntil = ticksUntil;
            }
        }

        double projTicks = Math.min(minTicksUntil, 30);
        double lateral = MAX_SPEED * projTicks;
        double bearing = wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS);
        int bfW = wb.getBattlefieldWidth();
        int bfH = wb.getBattlefieldHeight();

        // Project CW position
        double cwAngle = bearing + Math.PI / 2;
        double cwX = clamp(wb.getOurX() + lateral * Math.sin(cwAngle), WALL_MARGIN, bfW - WALL_MARGIN);
        double cwY = clamp(wb.getOurY() + lateral * Math.cos(cwAngle), WALL_MARGIN, bfH - WALL_MARGIN);
        cwCandidate.set(cwX, cwY, 0, (int) projTicks);

        // Project CCW position
        double ccwAngle = bearing - Math.PI / 2;
        double ccwX = clamp(wb.getOurX() + lateral * Math.sin(ccwAngle), WALL_MARGIN, bfW - WALL_MARGIN);
        double ccwY = clamp(wb.getOurY() + lateral * Math.cos(ccwAngle), WALL_MARGIN, bfH - WALL_MARGIN);
        ccwCandidate.set(ccwX, ccwY, 0, (int) projTicks);

        // Score both against all active waves
        double cwDanger = waveDanger.danger(cwCandidate, waves, wb, false);
        double ccwDanger = waveDanger.danger(ccwCandidate, waves, wb, false);

        // Hysteresis: only switch if the other direction is significantly safer
        if (preemptiveDir > 0) {
            if (ccwDanger < cwDanger - DIR_CHANGE_THRESHOLD) {
                preemptiveDir = -1;
                nextFlipTick = wb.getTick() + DIR_EVAL_INTERVAL;
            }
        } else {
            if (cwDanger < ccwDanger - DIR_CHANGE_THRESHOLD) {
                preemptiveDir = 1;
                nextFlipTick = wb.getTick() + DIR_EVAL_INTERVAL;
            }
        }
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }

    /**
     * Smoothly deflect the target angle away from nearby walls.
     * Within {@link #WALL_SMOOTH_ZONE}, computes a push-away angle
     * proportional to proximity and blends it into the target angle.
     * This replaces the hard direction reversal with a gradual curve.
     */
    private double applyWallSmoothing(double targetAngle, Whiteboard wb) {
        double x = wb.getOurX();
        double y = wb.getOurY();
        int bfW = wb.getBattlefieldWidth();
        int bfH = wb.getBattlefieldHeight();

        // Compute push vector from walls (sum of repulsive contributions)
        double pushX = 0;
        double pushY = 0;
        double distLeft = x - 18;
        double distRight = bfW - 18 - x;
        double distBottom = y - 18;
        double distTop = bfH - 18 - y;

        if (distLeft < WALL_SMOOTH_ZONE) {
            pushX += (WALL_SMOOTH_ZONE - distLeft) / WALL_SMOOTH_ZONE; // push right
        }
        if (distRight < WALL_SMOOTH_ZONE) {
            pushX -= (WALL_SMOOTH_ZONE - distRight) / WALL_SMOOTH_ZONE; // push left
        }
        if (distBottom < WALL_SMOOTH_ZONE) {
            pushY += (WALL_SMOOTH_ZONE - distBottom) / WALL_SMOOTH_ZONE; // push up
        }
        if (distTop < WALL_SMOOTH_ZONE) {
            pushY -= (WALL_SMOOTH_ZONE - distTop) / WALL_SMOOTH_ZONE; // push down
        }

        double pushMag = Math.hypot(pushX, pushY);
        if (pushMag < 0.01) {
            return targetAngle; // not near any wall
        }
        // Clamp magnitude to 1.0
        if (pushMag > 1.0) {
            pushX /= pushMag;
            pushY /= pushMag;
            pushMag = 1.0;
        }

        // Push angle in Robocode heading convention (0=north, CW positive)
        double pushAngle = Math.atan2(pushX, pushY);
        double deflection = pushMag * WALL_SMOOTH_MAX_DEFLECTION;
        // Blend: rotate targetAngle toward pushAngle by deflection amount
        double diff = RoboMath.normalRelativeAngle(pushAngle - targetAngle);
        double adjust = Math.signum(diff) * Math.min(Math.abs(diff), deflection);
        return targetAngle + adjust;
    }

    /**
     * Blend two angles by weight. Returns angle1*(1-w) + angle2*w,
     * handling wraparound correctly.
     */
    private static double blendAngles(double angle1, double angle2, double weight) {
        double diff = RoboMath.normalRelativeAngle(angle2 - angle1);
        return angle1 + diff * weight;
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

        // Move perpendicular to bearing, alternating direction, with per-round offset
        double perpAngle = bearing + preemptiveDir * Math.PI / 2 + roundOffset;
        double turn = RoboMath.normalRelativeAngle(perpAngle - ourHeading);

        maybeChangeSpeed(wb);
        double ahead = computeAhead(turn);
        ahead = Math.signum(ahead) * currentSpeed;
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

        // Move perpendicular to bearing (orbit the opponent) with per-round offset
        double perpAngle = bearing + preemptiveDir * Math.PI / 2 + roundOffset;
        // Apply wall smoothing — gradually deflect away from walls
        perpAngle = applyWallSmoothing(perpAngle, wb);
        double turn = RoboMath.normalRelativeAngle(perpAngle - ourHeading);

        maybeChangeSpeed(wb);
        double ahead = computeAhead(turn);
        ahead = Math.signum(ahead) * currentSpeed;
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

    /**
     * Change lateral speed at random intervals for velocity oscillation.
     * Makes our movement harder to profile by varying speed between 80-150.
     */
    private void maybeChangeSpeed(Whiteboard wb) {
        if (wb.getTick() >= nextSpeedChangeTick) {
            currentSpeed = MIN_SPEED_AHEAD + rng.nextDouble() * (MAX_SPEED_AHEAD - MIN_SPEED_AHEAD);
            nextSpeedChangeTick = wb.getTick() + SPEED_CHANGE_MIN_TICKS + rng.nextInt(SPEED_CHANGE_RANGE_TICKS);
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

package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import robocode.Rules;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Detects bullet hits and ram events from turn snapshots and
 * accumulates damage/energy features into the whiteboards.
 * <p>
 * Accumulator features (OUR_BULLET_DAMAGE_TO_OPPONENT,
 * OPPONENT_BULLET_ENERGY_GAIN,
 * RAM_DAMAGE_TO_OPPONENT, OPPONENT_RAM_ENERGY_GAIN, OPPONENT_WALL_HIT_DAMAGE)
 * are
 * held internally and flushed to the whiteboard each tick. They persist across
 * ring rotations until the scan tick resets them.
 */
final class DamageDetector {

    // Accumulator feature indices (per perspective)
    private static final int ACC_BULLET_DMG = 0;
    private static final int ACC_BULLET_GAIN = 1;
    private static final int ACC_RAM_DMG = 2;
    private static final int ACC_RAM_GAIN = 3;
    private static final int ACC_WALL_DMG = 4;
    private static final int ACC_COUNT = 5;

    // Internal accumulators [perspectiveIndex][accFeature]
    private final double[][] accums = new double[2][ACC_COUNT];

    // Track processed bullet IDs to avoid double-counting bullets that persist
    // in HIT_VICTIM state across multiple snapshots
    private final Set<Integer> processedBulletIds = new HashSet<>();
    // Track previous tick velocities (signed), headings, positions and states
    // for wall-hit and ram detection
    private final double[] prevVelocity = new double[2];
    private final double[] prevHeading = new double[2];
    private final double[] prevX = new double[2];
    private final double[] prevY = new double[2];
    private final RobotState[] prevState = { RobotState.ACTIVE, RobotState.ACTIVE };

    void reset() {
        processedBulletIds.clear();
        prevVelocity[0] = 0;
        prevVelocity[1] = 0;
        prevHeading[0] = 0;
        prevHeading[1] = 0;
        prevX[0] = 0;
        prevX[1] = 0;
        prevY[0] = 0;
        prevY[1] = 0;
        prevState[0] = RobotState.ACTIVE;
        prevState[1] = RobotState.ACTIVE;
        for (double[] a : accums)
            Arrays.fill(a, 0);
    }

    /**
     * Reset accumulators for perspectives where a scan just occurred.
     * Call AFTER wb.process() has consumed the values on scan ticks.
     */
    void resetAccumulatorsIfScan(Perspective[] perspectives) {
        for (Perspective us : perspectives) {
            Whiteboard wb = us.wb();
            double tick = wb.getFeature(Feature.TICK);
            double lastScan = wb.getFeature(Feature.LAST_SCAN_TICK);
            if (!Double.isNaN(tick) && tick == lastScan) {
                Arrays.fill(accums[us.robotIndex()], 0);
            }
        }
    }

    /**
     * Write internal accumulators to the whiteboard (call each tick after
     * detection).
     */
    void flushToWhiteboard(Perspective[] perspectives) {
        for (Perspective us : perspectives) {
            if (us.isDead())
                continue;
            Whiteboard wb = us.wb();
            int idx = us.robotIndex();
            wb.setFeature(Feature.OUR_BULLET_DAMAGE_TO_OPPONENT, accums[idx][ACC_BULLET_DMG]);
            wb.setFeature(Feature.OPPONENT_BULLET_ENERGY_GAIN, accums[idx][ACC_BULLET_GAIN]);
            wb.setFeature(Feature.RAM_DAMAGE_TO_OPPONENT, accums[idx][ACC_RAM_DMG]);
            wb.setFeature(Feature.OPPONENT_RAM_ENERGY_GAIN, accums[idx][ACC_RAM_GAIN]);
            wb.setFeature(Feature.OPPONENT_WALL_HIT_DAMAGE, accums[idx][ACC_WALL_DMG]);
        }
    }

    /**
     * Detect bullet hits from turn snapshot and apply damage immediately.
     * The engine delivers onBulletHit/onHitByBullet in the SAME turn that
     * HIT_VICTIM appears in the snapshot — no buffering needed.
     */
    void detectBulletHits(ITurnSnapshot turn, Perspective[] perspectives) {
        IBulletSnapshot[] bullets = turn.getBullets();
        if (bullets == null)
            return;

        for (IBulletSnapshot bullet : bullets) {
            if (bullet.getState() != BulletState.HIT_VICTIM)
                continue;

            int bulletId = bullet.getBulletId();
            if (!processedBulletIds.add(bulletId))
                continue; // already counted this bullet

            int owner = bullet.getOwnerIndex();
            int victim = bullet.getVictimIndex();
            double power = bullet.getPower();

            for (Perspective us : perspectives) {
                if (us.isDead())
                    continue;
                int idx = us.robotIndex();
                if (owner == us.robotIndex() && victim == us.peer().robotIndex()) {
                    accums[idx][ACC_BULLET_DMG] += Rules.getBulletDamage(power);
                } else if (owner == us.peer().robotIndex() && victim == us.robotIndex()) {
                    accums[idx][ACC_BULLET_GAIN] += Rules.getBulletHitBonus(power);
                }
            }
        }
    }

    /**
     * Detect rams from robot state transitions and accumulate damage.
     * Engine sets RobotState.HIT_ROBOT on collision participants; the state
     * may persist for multiple ticks so we only trigger on the transition.
     * In 1v1: both robots take ROBOT_HIT_DAMAGE (0.6), the "at fault" robot
     * gains ROBOT_HIT_BONUS (1.2).
     */
    void detectRams(IRobotSnapshot[] robots, Perspective[] perspectives) {
        // Detect ram damage on EVERY tick where either robot is in HIT_ROBOT.
        // Robocode deals 0.6 damage each tick while robots overlap (not just on
        // the initial collision), especially when one is pinned against a wall.
        boolean aHit = (robots[0].getState() == RobotState.HIT_ROBOT);
        boolean bHit = (robots[1].getState() == RobotState.HIT_ROBOT);

        if (!aHit && !bHit)
            return;

        // In 1v1 any collision = both take 0.6 damage per tick
        for (Perspective us : perspectives) {
            if (us.isDead())
                continue;
            int idx = us.robotIndex();
            accums[idx][ACC_RAM_DMG] += Rules.ROBOT_HIT_DAMAGE;

            // Determine if the OPPONENT was at fault (advancing toward us).
            // Use previous tick's velocity + heading to compute velocity component
            // toward the other robot. At-fault = moving toward the other.
            int peerIdx = us.peer().robotIndex();
            if (isAtFault(peerIdx, us.robotIndex())) {
                accums[idx][ACC_RAM_GAIN] += Rules.ROBOT_HIT_BONUS;
            }
        }
    }

    /**
     * Check if robot {@code robotIdx} was at fault in a collision with
     * {@code otherIdx}.
     * A robot is at fault if its velocity has a positive component toward the other
     * robot.
     * Uses previous tick's velocity (signed), heading, and positions because the
     * collision zeroes velocity in the current tick's snapshot.
     */
    private boolean isAtFault(int robotIdx, int otherIdx) {
        double v = prevVelocity[robotIdx];
        if (v == 0)
            return false;
        double heading = prevHeading[robotIdx];
        // Angle from this robot to the other
        double angleToOther = Math.atan2(prevX[otherIdx] - prevX[robotIdx],
                prevY[otherIdx] - prevY[robotIdx]);
        // Bearing = angle between heading and direction to other
        double bearing = robocode.util.Utils.normalRelativeAngle(angleToOther - heading);
        // velocity * cos(bearing) > 0 means moving toward the other robot
        return v * Math.cos(bearing) > 0;
    }

    /**
     * Detect opponent wall hits from robot state transition and accumulate wall
     * damage.
     * Wall hit damage = max(abs(velocity) * 0.5 - 1, 0), using the pre-impact
     * velocity.
     * <p>
     * The snapshot velocity is always 0 during HIT_WALL (post-impact), and the
     * HIT_WALL state persists for multiple ticks. We only trigger on the state
     * transition (ACTIVE → HIT_WALL) and use the previous tick's velocity.
     */
    void detectWallHits(IRobotSnapshot[] robots, Perspective[] perspectives) {
        for (Perspective us : perspectives) {
            if (us.isDead())
                continue;
            int peerIdx = us.peer().robotIndex();
            IRobotSnapshot opponent = robots[peerIdx];
            RobotState currentState = opponent.getState();

            // Only trigger on the transition into HIT_WALL
            if (currentState == RobotState.HIT_WALL && prevState[peerIdx] != RobotState.HIT_WALL) {
                double impactVelocity = Math.abs(prevVelocity[peerIdx]);
                double wallDmg = Math.max(impactVelocity * 0.5 - 1, 0);
                if (wallDmg > 0) {
                    accums[us.robotIndex()][ACC_WALL_DMG] += wallDmg;
                }
            }
        }
    }

    /**
     * Update state tracking for next tick. Call after all detect methods.
     */
    void updateState(IRobotSnapshot[] robots) {
        for (int i = 0; i < robots.length && i < prevVelocity.length; i++) {
            prevVelocity[i] = robots[i].getVelocity();
            prevHeading[i] = robots[i].getBodyHeading();
            prevX[i] = robots[i].getX();
            prevY[i] = robots[i].getY();
            prevState[i] = robots[i].getState();
        }
    }

}

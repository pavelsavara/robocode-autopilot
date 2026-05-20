package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import robocode.Rules;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;

import java.util.HashSet;
import java.util.Set;

/**
 * Detects bullet hits and ram events from turn snapshots and
 * accumulates damage/energy features into the whiteboards.
 */
final class DamageDetector {

    private final Whiteboard wbA;
    private final Whiteboard wbB;

    // Track processed bullet IDs to avoid double-counting bullets that persist
    // in HIT_VICTIM state across multiple snapshots
    private final Set<Integer> processedBulletIds = new HashSet<>();

    DamageDetector(Whiteboard wbA, Whiteboard wbB) {
        this.wbA = wbA;
        this.wbB = wbB;
    }

    void reset() {
        processedBulletIds.clear();
    }

    /**
     * Detect bullet hits from turn snapshot and apply damage immediately.
     * The engine delivers onBulletHit/onHitByBullet in the SAME turn that
     * HIT_VICTIM appears in the snapshot — no buffering needed.
     */
    void detectBulletHits(ITurnSnapshot turn, boolean deadA, boolean deadB) {
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

            // Perspective A: robotA(0) is us, robotB(1) is opponent
            if (owner == 0 && victim == 1 && !deadA) {
                accumulate(wbA, Feature.OUR_BULLET_DAMAGE_TO_OPPONENT, Rules.getBulletDamage(power));
            } else if (owner == 1 && victim == 0 && !deadA) {
                accumulate(wbA, Feature.OPPONENT_BULLET_ENERGY_GAIN, Rules.getBulletHitBonus(power));
            }

            // Perspective B: robotB(1) is us, robotA(0) is opponent
            if (owner == 1 && victim == 0 && !deadB) {
                accumulate(wbB, Feature.OUR_BULLET_DAMAGE_TO_OPPONENT, Rules.getBulletDamage(power));
            } else if (owner == 0 && victim == 1 && !deadB) {
                accumulate(wbB, Feature.OPPONENT_BULLET_ENERGY_GAIN, Rules.getBulletHitBonus(power));
            }
        }
    }

    /**
     * Detect rams from robot states and accumulate damage.
     * Engine sets RobotState.HIT_ROBOT on the "at fault" robot.
     */
    void detectRams(IRobotSnapshot robotA, IRobotSnapshot robotB,
            boolean deadA, boolean deadB) {
        boolean aHitRobot = (robotA.getState() == RobotState.HIT_ROBOT);
        boolean bHitRobot = (robotB.getState() == RobotState.HIT_ROBOT);

        if (!aHitRobot && !bHitRobot)
            return;

        double ramDmg = 0;
        if (aHitRobot)
            ramDmg += Rules.ROBOT_HIT_DAMAGE;
        if (bHitRobot)
            ramDmg += Rules.ROBOT_HIT_DAMAGE;

        if (!deadA)
            accumulate(wbA, Feature.RAM_DAMAGE_TO_OPPONENT, ramDmg);
        if (!deadB)
            accumulate(wbB, Feature.RAM_DAMAGE_TO_OPPONENT, ramDmg);
    }

    /** Accumulate a value into a whiteboard feature (treats NaN as 0). */
    private static void accumulate(Whiteboard wb, Feature feature, double value) {
        double current = wb.getFeature(feature);
        wb.setFeature(feature, (Double.isNaN(current) ? 0 : current) + value);
    }
}

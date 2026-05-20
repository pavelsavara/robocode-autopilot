package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import robocode.Rules;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;

/**
 * Detects bullet hits and ram events from turn snapshots and
 * accumulates damage/energy features into the whiteboards.
 */
final class DamageDetector {

    private final Whiteboard wbA;
    private final Whiteboard wbB;

    DamageDetector(Whiteboard wbA, Whiteboard wbB) {
        this.wbA = wbA;
        this.wbB = wbB;
    }

    /**
     * Detect bullet hits from turn snapshot and accumulate energy changes.
     * Mirrors robot's onBulletHit and onHitByBullet events.
     */
    void detectBulletHits(ITurnSnapshot turn, boolean deadA, boolean deadB) {
        IBulletSnapshot[] bullets = turn.getBullets();
        if (bullets == null)
            return;

        for (IBulletSnapshot bullet : bullets) {
            if (bullet.getState() != BulletState.HIT_VICTIM)
                continue;

            int owner = bullet.getOwnerIndex();
            int victim = bullet.getVictimIndex();
            double power = bullet.getPower();

            // Perspective A: robotA(0) is us, robotB(1) is opponent
            if (!deadA) {
                if (owner == 0 && victim == 1) {
                    accumulate(wbA, Feature.OUR_BULLET_DAMAGE_TO_OPPONENT, Rules.getBulletDamage(power));
                } else if (owner == 1 && victim == 0) {
                    accumulate(wbA, Feature.OPPONENT_BULLET_ENERGY_GAIN, Rules.getBulletHitBonus(power));
                }
            }

            // Perspective B: robotB(1) is us, robotA(0) is opponent
            if (!deadB) {
                if (owner == 1 && victim == 0) {
                    accumulate(wbB, Feature.OUR_BULLET_DAMAGE_TO_OPPONENT, Rules.getBulletDamage(power));
                } else if (owner == 0 && victim == 1) {
                    accumulate(wbB, Feature.OPPONENT_BULLET_ENERGY_GAIN, Rules.getBulletHitBonus(power));
                }
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

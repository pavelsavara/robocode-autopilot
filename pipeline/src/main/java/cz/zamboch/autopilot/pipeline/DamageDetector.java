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

    // Buffered damage from previous tick (robot receives events 1 tick after snapshot)
    private double pendingBulletDmgA;
    private double pendingBulletGainA;
    private double pendingBulletDmgB;
    private double pendingBulletGainB;

    DamageDetector(Whiteboard wbA, Whiteboard wbB) {
        this.wbA = wbA;
        this.wbB = wbB;
    }

    void reset() {
        pendingBulletDmgA = 0;
        pendingBulletGainA = 0;
        pendingBulletDmgB = 0;
        pendingBulletGainB = 0;
    }

    /**
     * Detect bullet hits from turn snapshot. Buffers damage and applies
     * the previous tick's buffer to match robot event delivery timing
     * (engine delivers onBulletHit one tick after the hit occurs in the snapshot).
     */
    void detectBulletHits(ITurnSnapshot turn, boolean deadA, boolean deadB) {
        // Apply previous tick's buffered damage
        if (!deadA) {
            if (pendingBulletDmgA != 0)
                accumulate(wbA, Feature.OUR_BULLET_DAMAGE_TO_OPPONENT, pendingBulletDmgA);
            if (pendingBulletGainA != 0)
                accumulate(wbA, Feature.OPPONENT_BULLET_ENERGY_GAIN, pendingBulletGainA);
        }
        if (!deadB) {
            if (pendingBulletDmgB != 0)
                accumulate(wbB, Feature.OUR_BULLET_DAMAGE_TO_OPPONENT, pendingBulletDmgB);
            if (pendingBulletGainB != 0)
                accumulate(wbB, Feature.OPPONENT_BULLET_ENERGY_GAIN, pendingBulletGainB);
        }

        // Buffer this tick's hits for next tick
        pendingBulletDmgA = 0;
        pendingBulletGainA = 0;
        pendingBulletDmgB = 0;
        pendingBulletGainB = 0;

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
            if (owner == 0 && victim == 1) {
                pendingBulletDmgA += Rules.getBulletDamage(power);
            } else if (owner == 1 && victim == 0) {
                pendingBulletGainA += Rules.getBulletHitBonus(power);
            }

            // Perspective B: robotB(1) is us, robotA(0) is opponent
            if (owner == 1 && victim == 0) {
                pendingBulletDmgB += Rules.getBulletDamage(power);
            } else if (owner == 0 && victim == 1) {
                pendingBulletGainB += Rules.getBulletHitBonus(power);
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

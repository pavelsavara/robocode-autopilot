package cz.zamboch;

import cz.zamboch.autopilot.core.Transformer;
import cz.zamboch.autopilot.core.Whiteboard;
import robocode.AdvancedRobot;
import robocode.BulletHitEvent;
import robocode.HitByBulletEvent;
import robocode.ScannedRobotEvent;
import robocode.StatusEvent;

/**
 * Competition robot skeleton. Receives events from the Robocode engine,
 * forwards to Whiteboard, computes features via Transformer.
 * ML decision module is a future addition.
 */
public final class Autopilot extends AdvancedRobot {
    private Whiteboard whiteboard;
    private Transformer transformer;

    @Override
    public void run() {
        whiteboard = new Whiteboard();
        transformer = new Transformer();
        transformer.resolveDependencies();

        whiteboard.onRoundStart(getRoundNum(), (int) getBattleFieldWidth(),
                (int) getBattleFieldHeight(), getGunCoolingRate(),
                getNumRounds());

        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true);

        while (true) {
            setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
            execute();
        }
    }

    @Override
    public void onStatus(StatusEvent e) {
        // TODO: forward to whiteboard
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        // TODO: forward to whiteboard, run transformer
    }

    @Override
    public void onHitByBullet(HitByBulletEvent e) {
        // TODO: forward to whiteboard
    }

    @Override
    public void onBulletHit(BulletHitEvent e) {
        // TODO: forward to whiteboard
    }
}

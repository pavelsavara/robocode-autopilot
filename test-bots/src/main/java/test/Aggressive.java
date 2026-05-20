package test;

import robocode.AdvancedRobot;
import robocode.ScannedRobotEvent;

/**
 * Aggressive bot that fires and charges toward the opponent.
 * Used for testing bullet hit and ram detection.
 */
public final class Aggressive extends AdvancedRobot {
    @Override
    public void run() {
        setTurnRadarRight(Double.POSITIVE_INFINITY);
        while (true) {
            setAhead(100);
            execute();
        }
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        // Turn toward opponent and fire
        double bearing = e.getBearingRadians();
        setTurnRightRadians(bearing);
        setAhead(e.getDistance());
        if (getGunHeat() == 0) {
            setFire(2.0);
        }
    }
}

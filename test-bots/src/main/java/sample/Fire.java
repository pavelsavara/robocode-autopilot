package sample;

import robocode.AdvancedRobot;
import robocode.ScannedRobotEvent;

/**
 * Classic Fire sample bot. Sits still, tracks opponent with gun, fires
 * proportional to distance. Good for testing gun aiming against a stationary
 * bot.
 */
public final class Fire extends AdvancedRobot {
    @Override
    public void run() {
        while (true) {
            turnGunRight(5);
        }
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        double dist = e.getDistance();
        if (dist < 50 && getEnergy() > 50) {
            fire(3);
        } else if (dist < 100 && getEnergy() > 30) {
            fire(2.5);
        } else if (dist < 200) {
            fire(2);
        } else {
            fire(1);
        }
        scan();
    }
}

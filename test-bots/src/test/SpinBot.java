package test;

import robocode.Robot;
import robocode.ScannedRobotEvent;

/**
 * SpinBot — spins in place and fires when scanning an opponent.
 */
public class SpinBot extends Robot {
    public void run() {
        while (true) {
            turnGunRight(5);
            ahead(50);
            turnRight(30);
        }
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        fire(1);
    }
}

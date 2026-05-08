package test;

import robocode.Robot;
import robocode.ScannedRobotEvent;

/**
 * WallBot — follows walls and fires at opponents.
 */
public class WallBot extends Robot {
    public void run() {
        turnLeft(getHeading() % 90);
        ahead(500);
        turnRight(90);
        while (true) {
            ahead(500);
            turnRight(90);
        }
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        fire(2);
    }
}

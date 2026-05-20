package sample;

import robocode.AdvancedRobot;
import robocode.HitRobotEvent;
import robocode.ScannedRobotEvent;

/**
 * Classic Walls sample bot. Moves along the battlefield walls and fires
 * at opponents when scanned. Good for testing movement prediction.
 */
public final class Walls extends AdvancedRobot {
    private boolean movingForward;

    @Override
    public void run() {
        movingForward = true;
        setAdjustGunForRobotTurn(true);
        turnLeft(getHeading() % 90);
        ahead(Double.MAX_VALUE);
        while (true) {
            if (getDistanceRemaining() == 0) {
                turnRight(90);
                ahead(Double.MAX_VALUE);
            }
            execute();
        }
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        double absoluteBearing = getHeadingRadians() + e.getBearingRadians();
        double gunTurn = absoluteBearing - getGunHeadingRadians();
        setTurnGunRightRadians(Math.atan2(Math.sin(gunTurn), Math.cos(gunTurn)));
        setFire(2);
    }

    @Override
    public void onHitRobot(HitRobotEvent e) {
        if (e.isMyFault()) {
            reverseDirection();
        }
    }

    private void reverseDirection() {
        if (movingForward) {
            setBack(Double.MAX_VALUE);
            movingForward = false;
        } else {
            setAhead(Double.MAX_VALUE);
            movingForward = true;
        }
    }
}

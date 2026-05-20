package sample;

import robocode.AdvancedRobot;
import robocode.HitWallEvent;
import robocode.ScannedRobotEvent;

/**
 * Classic Crazy sample bot. Moves erratically — random distances forward/back
 * with random turns. Good for testing adaptive targeting.
 */
public class Crazy extends AdvancedRobot {

    private boolean movingForward;

    @Override
    public void run() {
        movingForward = true;

        while (true) {
            setAhead(40000);
            movingForward = true;
            setTurnRight(90);
            waitFor(new robocode.TurnCompleteCondition(this));
            setTurnLeft(180);
            waitFor(new robocode.TurnCompleteCondition(this));
            setTurnRight(180);
            waitFor(new robocode.TurnCompleteCondition(this));
        }
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        fire(1);
    }

    @Override
    public void onHitWall(HitWallEvent e) {
        reverseDirection();
    }

    public void reverseDirection() {
        if (movingForward) {
            setBack(40000);
            movingForward = false;
        } else {
            setAhead(40000);
            movingForward = true;
        }
    }
}

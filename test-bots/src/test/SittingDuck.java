package test;

import robocode.Robot;

/**
 * SittingDuck — does absolutely nothing. Stationary target.
 */
public class SittingDuck extends Robot {
    public void run() {
        while (true) {
            doNothing();
        }
    }
}

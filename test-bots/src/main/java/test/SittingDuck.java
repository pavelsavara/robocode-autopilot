package test;

import robocode.Robot;

/**
 * Minimal target bot that does nothing. Perfect for validating our robot works.
 */
public final class SittingDuck extends Robot {
    @Override
    public void run() {
        while (true) {
            doNothing();
        }
    }
}

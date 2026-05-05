package cz.zamboch;

import robocode.control.BattlefieldSpecification;
import robocode.control.BattleSpecification;
import robocode.control.RobocodeEngine;
import robocode.control.events.BattleAdaptor;
import robocode.control.events.BattleErrorEvent;
import robocode.control.events.TurnEndedEvent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test: loads Autopilot into the Robocode engine, runs a 1-round
 * battle (self vs self), and verifies:
 * <ul>
 *   <li>The robot JAR loads (classloader finds all classes + EnvelopeData)</li>
 *   <li>The robot survives at least 50 ticks without crashing</li>
 *   <li>No runtime errors from the engine</li>
 * </ul>
 *
 * Uses Robocode's Control API directly (not RobotTestBed) to avoid
 * classpath/security-manager conflicts with Gradle's test executor.
 */
public class AutopilotSmokeTest {

    @Test
    void robotLoadsAndSurvives() {
        // Configure robot discovery path
        String robotPath = System.getProperty("robocode.robot.test.path");
        if (robotPath == null) {
            robotPath = "build/libs";
        }
        System.setProperty("ROBOTPATH", robotPath);
        System.setProperty("NOSECURITY", "true");

        RobocodeEngine engine = new RobocodeEngine();
        try {
            final int[] maxTurn = {0};
            final StringBuilder errors = new StringBuilder();

            engine.addBattleListener(new BattleAdaptor() {
                @Override
                public void onTurnEnded(TurnEndedEvent event) {
                    int turn = event.getTurnSnapshot().getTurn();
                    if (turn > maxTurn[0]) {
                        maxTurn[0] = turn;
                    }
                }

                @Override
                public void onBattleError(BattleErrorEvent event) {
                    errors.append(event.getError()).append("\n");
                }
            });

            // Run: our robot vs itself, 1 round
            robocode.control.RobotSpecification[] robots =
                    engine.getLocalRepository("cz.zamboch.Autopilot,cz.zamboch.Autopilot");

            assertNotNull(robots, "Robot specs should not be null");
            assertEquals(2, robots.length,
                    "Should find 2 robot specs (self vs self)");

            BattleSpecification battleSpec = new BattleSpecification(
                    1, // 1 round
                    new BattlefieldSpecification(800, 600),
                    robots);

            engine.runBattle(battleSpec, true); // true = wait for completion

            assertTrue(maxTurn[0] >= 50,
                    "Robot should survive at least 50 ticks, maxTurn=" + maxTurn[0]);
            assertEquals(0, errors.length(),
                    "Should have 0 engine errors, got: " + errors);
        } finally {
            engine.close();
        }
    }
}

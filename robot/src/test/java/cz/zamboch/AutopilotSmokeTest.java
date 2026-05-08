package cz.zamboch;

import robocode.control.BattlefieldSpecification;
import robocode.control.BattleSpecification;
import robocode.control.RobocodeEngine;
import robocode.control.events.BattleAdaptor;
import robocode.control.events.BattleErrorEvent;
import robocode.control.events.TurnEndedEvent;
import robocode.control.snapshot.IRobotSnapshot;

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
            // Track body color: GREEN (0x00FF00) = all ML models loaded,
            // RED (0xFF0000) = at least one model failed to load
            final int[] lastBodyColor = {0};

            engine.addBattleListener(new BattleAdaptor() {
                @Override
                public void onTurnEnded(TurnEndedEvent event) {
                    int turn = event.getTurnSnapshot().getTurn();
                    if (turn > maxTurn[0]) {
                        maxTurn[0] = turn;
                    }
                    // Capture body color from the first robot snapshot
                    IRobotSnapshot[] robots = event.getTurnSnapshot().getRobots();
                    if (robots != null && robots.length > 0 && turn > 5) {
                        lastBodyColor[0] = robots[0].getBodyColor();
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

            // Verify ML models loaded inside the Robocode sandbox.
            // Robot sets body color to GREEN (0xFF00FF00 with alpha) when all 3 models load,
            // RED (0xFFFF0000) if any failed. Color 0 = never set (no scan happened yet).
            // GREEN in Robocode ARGB: -16711936 (0xFF00FF00)
            int green = new java.awt.Color(0, 255, 0).getRGB();
            assertEquals(green, lastBodyColor[0],
                    "Robot body color should be GREEN (all models loaded). "
                            + "Got: 0x" + Integer.toHexString(lastBodyColor[0])
                            + " (RED=model load failure, 0=never set)");
        } finally {
            engine.close();
        }
    }
}

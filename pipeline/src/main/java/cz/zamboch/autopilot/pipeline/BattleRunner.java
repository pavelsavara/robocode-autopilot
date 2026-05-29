package cz.zamboch.autopilot.pipeline;

import net.sf.robocode.io.Logger;
import robocode.control.BattleSpecification;
import robocode.control.BattlefieldSpecification;
import robocode.control.RobocodeEngine;
import robocode.control.RobotSpecification;

import java.io.File;

/**
 * Headless battle runner with integrated CSV pipeline.
 * Streams turn snapshots directly into Whiteboard → Transformer → CsvWriter
 * without intermediate .br files.
 *
 * System properties:
 * -Drobot.jar=path/to/robot.jar
 * -Dbattle.stage=path/to/staged/jars
 * -Dbattle.rounds=10
 * -Dbattle.opponent=test.SittingDuck
 * -Dbattle.output=path/to/csv/output (enables CSV pipeline)
 */
public final class BattleRunner {

    /**
     * Run a battle with the given parameters. Reusable from main() and tests.
     *
     * @param opponent  fully-qualified opponent class name
     * @param rounds    number of rounds
     * @param outputDir CSV output directory (null for results-only mode)
     * @return the StreamingPipelineObserver (caller should call close())
     */
    public static StreamingPipelineObserver runBattle(String opponent, int rounds, String outputDir) {
        RobocodeEngine.setLogMessagesEnabled(false);
        RobocodeEngine engine = new RobocodeEngine();

        StreamingPipelineObserver observer = new StreamingPipelineObserver(outputDir, 800, 600);
        engine.addBattleListener(observer);

        try {
            String robotFilter = "cz.zamboch.Autopilot," + opponent;
            RobotSpecification[] robots = engine.getLocalRepository(robotFilter);

            RobotSpecification ourBot = null;
            RobotSpecification oppBot = null;
            for (RobotSpecification spec : robots) {
                String name = spec.getClassName();
                if ("cz.zamboch.Autopilot".equals(name)) {
                    ourBot = spec;
                }
                if (opponent.equals(name)) {
                    oppBot = spec;
                }
            }

            if (ourBot == null) {
                throw new IllegalStateException("Cannot find cz.zamboch.Autopilot in ROBOTPATH");
            }
            if (oppBot == null) {
                throw new IllegalStateException("Cannot find opponent: " + opponent);
            }

            BattlefieldSpecification battlefield = new BattlefieldSpecification(800, 600);
            BattleSpecification spec = new BattleSpecification(
                    rounds, battlefield, new RobotSpecification[] { ourBot, oppBot });

            engine.runBattle(spec, true);
        } catch (Exception e) {
            observer.close();
            engine.removeBattleListener(observer);
            Logger.initialized = true;
            RobocodeEngine.setLogErrorsEnabled(false);
            engine.close();
            throw e;
        }
        engine.removeBattleListener(observer);
        Logger.initialized = true;
        RobocodeEngine.setLogErrorsEnabled(false);
        engine.close();
        return observer;
    }

    public static void main(String[] args) {
        String robotJar = System.getProperty("robot.jar");
        int rounds = Integer.parseInt(System.getProperty("battle.rounds", "10"));
        String opponent = System.getProperty("battle.opponent", "sample.SittingDuck");
        String outputDir = System.getProperty("battle.output");

        // Find robocode home — check environment, then common paths
        String roboHome = System.getenv("ROBOCODE_HOME");
        if (roboHome == null) {
            String[] candidates = {
                    "C:\\robocode",
                    System.getProperty("user.home") + "\\robocode",
                    "D:\\robocode"
            };
            for (String c : candidates) {
                if (new File(c, "libs").isDirectory()) {
                    roboHome = c;
                    break;
                }
            }
        }
        if (roboHome == null) {
            System.err.println("ERROR: Cannot find Robocode installation.");
            System.err.println("Set ROBOCODE_HOME environment variable.");
            System.exit(1);
        }

        System.out.println("Robocode home: " + roboHome);
        System.out.println("Opponent: " + opponent);
        System.out.println("Rounds: " + rounds);
        if (outputDir != null) {
            System.out.println("CSV output: " + outputDir);
        }
        System.out.println();

        // Tell Robocode where to find robots
        String battleStage = System.getProperty("battle.stage");
        if (battleStage != null) {
            System.setProperty("ROBOTPATH", battleStage);
        } else if (robotJar != null) {
            System.setProperty("ROBOTPATH", new File(robotJar).getParent());
        }
        System.setProperty("NOSECURITY", "true");

        StreamingPipelineObserver observer = runBattle(opponent, rounds, outputDir);
        observer.close();
    }
}

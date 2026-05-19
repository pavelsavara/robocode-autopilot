package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Transformer;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.EnergyFeatures;
import cz.zamboch.autopilot.core.features.MovementFeatures;
import cz.zamboch.autopilot.core.features.SpatialFeatures;
import cz.zamboch.autopilot.core.features.TimingFeatures;
import robocode.control.BattleSpecification;
import robocode.control.BattlefieldSpecification;
import robocode.control.RobocodeEngine;
import robocode.control.RobotSpecification;
import robocode.control.events.BattleAdaptor;
import robocode.control.events.BattleCompletedEvent;
import robocode.control.events.BattleErrorEvent;
import robocode.control.events.RoundEndedEvent;
import robocode.control.events.RoundStartedEvent;
import robocode.control.events.TurnEndedEvent;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;

import java.io.File;
import java.io.IOException;

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

        RobocodeEngine.setLogMessagesEnabled(false);
        RobocodeEngine engine = new RobocodeEngine();

        // Create streaming pipeline observer
        StreamingPipelineObserver observer = new StreamingPipelineObserver(
                outputDir, 800, 600);
        engine.addBattleListener(observer);

        // Get robots by name
        String robotFilter = "cz.zamboch.Autopilot," + opponent;
        RobotSpecification[] robots = engine.getLocalRepository(robotFilter);

        // Find our robot and the opponent
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
            System.err.println("ERROR: Cannot find cz.zamboch.Autopilot in " + robotJar);
            engine.close();
            System.exit(1);
        }
        if (oppBot == null) {
            System.err.println("ERROR: Cannot find opponent: " + opponent);
            System.err.println("Make sure the opponent is installed in " + roboHome + "/robots/");
            engine.close();
            System.exit(1);
        }

        // Run battle
        BattlefieldSpecification battlefield = new BattlefieldSpecification(800, 600);
        BattleSpecification spec = new BattleSpecification(
                rounds, battlefield, new RobotSpecification[] { ourBot, oppBot });

        engine.runBattle(spec, true);
        engine.close();

        // Close CSV writers
        observer.close();
    }

    /**
     * Battle observer that streams turn snapshots directly into the
     * Whiteboard → Transformer → CsvWriter pipeline. No .br intermediate.
     */
    static final class StreamingPipelineObserver extends BattleAdaptor {
        private final double bfWidth;
        private final double bfHeight;

        // Pipeline state (null if outputDir not set = results-only mode)
        private Whiteboard wbA;
        private Whiteboard wbB;
        private Transformer tA;
        private Transformer tB;
        private Player player;
        private CsvWriter csvA;
        private CsvWriter csvB;
        private String battleId;
        private int currentRound = -1;
        private IRobotSnapshot lastRobotA;
        private IRobotSnapshot lastRobotB;

        StreamingPipelineObserver(String outputDir, double bfWidth, double bfHeight) {
            this.bfWidth = bfWidth;
            this.bfHeight = bfHeight;

            if (outputDir != null) {
                try {
                    wbA = new Whiteboard();
                    wbB = new Whiteboard();
                    tA = createTransformer();
                    tB = createTransformer();
                    player = new Player(wbA, wbB);

                    // Battle ID from timestamp
                    battleId = "battle-" + System.currentTimeMillis();

                    File dirA = new File(outputDir, battleId + "/Autopilot");
                    File dirB = new File(outputDir, battleId + "/Opponent");
                    csvA = new CsvWriter(dirA);
                    csvB = new CsvWriter(dirB);
                    csvA.writeHeaders(battleId);
                    csvB.writeHeaders(battleId);
                } catch (IOException e) {
                    System.err.println("ERROR: Cannot create CSV output: " + e.getMessage());
                    csvA = null;
                    csvB = null;
                }
            }
        }

        @Override
        public void onTurnEnded(TurnEndedEvent event) {
            ITurnSnapshot turn = event.getTurnSnapshot();

            if (csvA == null) {
                return; // Results-only mode
            }

            IRobotSnapshot[] robots = turn.getRobots();
            if (robots == null || robots.length < 2) {
                return;
            }

            int roundIndex = turn.getRound();

            // Detect new round — finalize previous
            if (roundIndex != currentRound && currentRound >= 0) {
                finalizeRound();
            }
            currentRound = roundIndex;

            // Advance tick (resets per-tick flags)
            wbA.advanceTick();
            wbB.advanceTick();

            // Feed snapshot into Player (populates whiteboards)
            player.processTurn(roundIndex, turn, bfWidth, bfHeight);

            // Compute features
            wbA.clearFeatures();
            wbB.clearFeatures();
            tA.process(wbA);
            tB.process(wbB);

            // Write tick rows
            try {
                csvA.writeTickRow(wbA, battleId, roundIndex);
                csvB.writeTickRow(wbB, battleId, roundIndex);

                // Write wave rows if opponent fired
                if (wbA.hasOpponentFired()) {
                    csvA.writeWaveRow(wbA, battleId, roundIndex);
                }
                if (wbB.hasOpponentFired()) {
                    csvB.writeWaveRow(wbB, battleId, roundIndex);
                }
            } catch (IOException e) {
                System.err.println("CSV write error: " + e.getMessage());
            }

            // Track last snapshots for round finalization
            lastRobotA = robots[0];
            lastRobotB = robots[1];
        }

        @Override
        public void onRoundEnded(RoundEndedEvent event) {
            if (csvA != null && currentRound >= 0) {
                finalizeRound();
            }
        }

        private void finalizeRound() {
            if (lastRobotA == null || lastRobotB == null) {
                return;
            }
            player.finalizeRound(wbA, wbB, lastRobotA, lastRobotB);
            try {
                csvA.writeScoreRow(wbA, battleId, currentRound);
                csvB.writeScoreRow(wbB, battleId, currentRound);
            } catch (IOException e) {
                System.err.println("CSV write error: " + e.getMessage());
            }
        }

        @Override
        public void onBattleCompleted(BattleCompletedEvent event) {
            System.out.println("=== BATTLE RESULTS ===");
            System.out.println(String.format("%-30s %8s %8s %8s %8s %8s",
                    "Robot", "Score", "Bullets", "Ram", "1sts", "Survival"));

            for (robocode.BattleResults r : event.getSortedResults()) {
                System.out.println(String.format("%-30s %8d %8d %8d %8d %8d",
                        r.getTeamLeaderName(),
                        r.getScore(),
                        r.getBulletDamage(),
                        r.getRamDamage(),
                        r.getFirsts(),
                        r.getSurvival()));
            }
            System.out.println();

            if (csvA != null) {
                System.out.println("CSV output: " + battleId);
            }
        }

        @Override
        public void onBattleError(BattleErrorEvent event) {
            System.err.println("Battle error: " + event.getError());
        }

        void close() {
            try {
                if (csvA != null) {
                    csvA.close();
                }
                if (csvB != null) {
                    csvB.close();
                }
            } catch (IOException e) {
                System.err.println("Error closing CSV: " + e.getMessage());
            }
        }

        private static Transformer createTransformer() {
            Transformer t = new Transformer();
            t.register(new SpatialFeatures());
            t.register(new MovementFeatures());
            t.register(new EnergyFeatures());
            t.register(new TimingFeatures());
            t.resolveDependencies();
            return t;
        }
    }
}

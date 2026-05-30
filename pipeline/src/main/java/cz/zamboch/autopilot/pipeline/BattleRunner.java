package cz.zamboch.autopilot.pipeline;

import net.sf.robocode.io.Logger;
import robocode.control.BattleSpecification;
import robocode.control.BattlefieldSpecification;
import robocode.control.RobocodeEngine;
import robocode.control.RobotSpecification;
import robocode.control.events.BattleAdaptor;
import robocode.control.events.BattleCompletedEvent;

import java.io.File;
import java.io.IOException;

/**
 * Headless battle runner using PipelineOrchestrator.
 * Runs Robocode battles and streams turn snapshots through the new pipeline.
 *
 * System properties:
 * -Drobot.jar=path/to/robot.jar
 * -Dbattle.stage=path/to/staged/jars
 * -Dbattle.rounds=10
 * -Dbattle.opponent=test.SittingDuck
 * -Dbattle.output=path/to/csv/output (enables CSV pipeline)
 */
public final class BattleRunner {

    /** Results from a completed battle. */
    public static final class BattleResult {
        private final PipelineOrchestrator orchestrator;
        private int ourScore;
        private int opponentScore;
        private int ourFirsts;
        private int totalRounds;

        BattleResult(PipelineOrchestrator orchestrator) {
            this.orchestrator = orchestrator;
        }

        public PipelineOrchestrator orchestrator() {
            return orchestrator;
        }

        public int getOurScore() {
            return ourScore;
        }

        public int getOpponentScore() {
            return opponentScore;
        }

        public int getOurFirsts() {
            return ourFirsts;
        }

        public int getTotalRounds() {
            return totalRounds;
        }

        public double getWinRate() {
            return totalRounds > 0 ? (double) ourFirsts / totalRounds : 0;
        }

        public double getScoreRatio() {
            return opponentScore > 0 ? (double) ourScore / opponentScore : ourScore;
        }
    }

    /**
     * Run a battle with given parameters.
     *
     * @param opponent  fully-qualified opponent class name
     * @param rounds    number of rounds
     * @param outputDir CSV output directory (null for results-only mode)
     * @return BattleResult with orchestrator and scores
     */
    public static BattleResult runBattle(String opponent, int rounds, String outputDir) {
        RobocodeEngine.setLogMessagesEnabled(false);
        RobocodeEngine engine = new RobocodeEngine();

        PipelineOrchestrator orchestrator = new PipelineOrchestrator(800, 600, 0.1);
        BattleResult result = new BattleResult(orchestrator);

        // Point observers at the staged read-only VCS data so they load the SAME
        // persisted model the live robot loads (keyed by OPPONENT_ID_HASH, once per
        // battle, into their own VcsStores). Observers never write here.
        String battleStage = System.getProperty("battle.stage");
        if (battleStage != null) {
            File observerDataDir = new File(battleStage, ".data/cz/zamboch/Autopilot.data");
            orchestrator.setObserverDataDir(observerDataDir);
        }

        // Attach validators: Layer 0 (debug-property fidelity) + god-view quality (1-4)
        Layer0DebugFidelityValidator layer0Validator = new Layer0DebugFidelityValidator();
        orchestrator.setLayer0Validator(layer0Validator);
        GodViewQualityValidator validator = new GodViewQualityValidator(800, 600);
        orchestrator.setValidator(validator);

        // Attach CSV writers if output requested
        if (outputDir != null) {
            try {
                String battleId = "battle-" + System.currentTimeMillis();
                File battleDir = new File(outputDir, battleId);
                File perspDir0 = new File(battleDir, "Autopilot");
                File perspDir1 = new File(battleDir, "Opponent");
                CsvWriter writer0 = new CsvWriter(perspDir0);
                CsvWriter writer1 = new CsvWriter(perspDir1);
                orchestrator.setCsvWriters(writer0, writer1);
                orchestrator.setBattleId(battleId);
                writer0.writeHeaders(battleId);
                writer1.writeHeaders(battleId);
            } catch (IOException e) {
                throw new RuntimeException("Failed to initialize CSV writers", e);
            }
        }

        // Score-tracking listener
        engine.addBattleListener(orchestrator);
        engine.addBattleListener(new BattleAdaptor() {
            @Override
            public void onBattleCompleted(BattleCompletedEvent event) {
                robocode.BattleResults[] results = event.getSortedResults();
                for (robocode.BattleResults r : results) {
                    String name = r.getTeamLeaderName();
                    if (name != null && name.contains("Autopilot")) {
                        result.ourScore = r.getScore();
                        result.ourFirsts = r.getFirsts();
                    } else {
                        result.opponentScore = r.getScore();
                    }
                }
                result.totalRounds = rounds;
            }
        });

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
            try {
                orchestrator.close();
            } catch (IOException ignored) {
            }
            engine.removeBattleListener(orchestrator);
            Logger.initialized = true;
            RobocodeEngine.setLogErrorsEnabled(false);
            engine.close();
            throw e;
        }

        engine.removeBattleListener(orchestrator);
        Logger.initialized = true;
        RobocodeEngine.setLogErrorsEnabled(false);
        engine.close();

        try {
            orchestrator.close();
        } catch (IOException ignored) {
        }

        return result;
    }

    public static void main(String[] args) {
        String robotJar = System.getProperty("robot.jar");
        int rounds = Integer.parseInt(System.getProperty("battle.rounds", "10"));
        String opponent = System.getProperty("battle.opponent", "sample.SittingDuck");
        String outputDir = System.getProperty("battle.output");

        // Find robocode home
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

        BattleResult result = runBattle(opponent, rounds, outputDir);
        System.out.printf("Win rate: %.1f%% (%d/%d)%n",
                result.getWinRate() * 100, result.getOurFirsts(), result.getTotalRounds());
        System.out.printf("Score ratio: %.2f (%d/%d)%n",
                result.getScoreRatio(), result.getOurScore(), result.getOpponentScore());

        if (result.orchestrator().validator() != null) {
            result.orchestrator().validator().printSummary();
        }
    }
}

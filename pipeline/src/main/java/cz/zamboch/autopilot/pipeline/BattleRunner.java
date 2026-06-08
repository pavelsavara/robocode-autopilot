package cz.zamboch.autopilot.pipeline;

import cz.zamboch.Autopilot;
import net.sf.robocode.io.Logger;
import robocode.control.BattleSpecification;
import robocode.control.BattlefieldSpecification;
import robocode.control.RobocodeEngine;
import robocode.control.RobotSpecification;
import robocode.control.events.BattleAdaptor;
import robocode.control.events.BattleCompletedEvent;
import robocode.control.events.TurnEndedEvent;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

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
        private int bulletsFired;
        private int bulletsHit;

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

        public int getBulletsFired() {
            return bulletsFired;
        }

        public int getBulletsHit() {
            return bulletsHit;
        }

        /**
         * Fraction of our fired bullets that hit the opponent, computed directly
         * from the Robocode control-API turn snapshots (independent of CSV output).
         * Returns {@code NaN} when no bullets were fired.
         */
        public double getHitRate() {
            return bulletsFired > 0 ? (double) bulletsHit / bulletsFired : Double.NaN;
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
        return runBattle("cz.zamboch.Autopilot", opponent, rounds, outputDir, true);
        }

        /**
         * Run a battle between arbitrary robot classes.
         *
         * @param robotA       fully-qualified first robot class name
         * @param robotB       fully-qualified second robot class name
         * @param rounds       number of rounds
         * @param outputDir    CSV output directory (null for results-only mode)
         * @param enableLayer0 attach Layer 0 debug-property fidelity validator
         * @return BattleResult with orchestrator and scores
         */
        public static BattleResult runBattle(String robotA, String robotB, int rounds,
            String outputDir, boolean enableLayer0) {
        Autopilot.resetLiveBattleState();
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
        if (enableLayer0) {
            Layer0DebugFidelityValidator layer0Validator = new Layer0DebugFidelityValidator();
            orchestrator.setLayer0Validator(layer0Validator);
        }
        GodViewQualityValidator validator = new GodViewQualityValidator();
        orchestrator.setValidator(validator);

        // Attach CSV writers if output requested
        if (outputDir != null) {
            try {
                String battleId = "battle-" + System.currentTimeMillis();
                File battleDir = new File(outputDir, battleId);
                File perspDir0 = new File(battleDir, "cz.zamboch.Autopilot".equals(robotA) ? "Autopilot" : "Perspective0");
                File perspDir1 = new File(battleDir, "cz.zamboch.Autopilot".equals(robotA) ? "Opponent" : "Perspective1");
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

        // Attach IDebugProperty fidelity dump (in-game.csv / observer.csv) when the
        // debug.csv.dir system property is set. Off by default; OUTSIDE the
        // outputDir/tempDir block so the files survive in a stable location for
        // offline diffing across all opponents/rounds (append mode).
        String debugCsvDir = System.getProperty("debug.csv.dir");
        if (debugCsvDir != null && !debugCsvDir.isBlank()) {
            try {
                orchestrator.setDebugCsv(new DebugPropertyCsvWriter(new File(debugCsvDir), robotB));
            } catch (IOException e) {
                throw new RuntimeException("Failed to initialize debug-property CSV writer", e);
            }
        }

        // Per-event their-fire diff trace (their-fires.csv). Tiny vs debugCsv; safe
        // to leave on whenever investigating L3-their detection drift.
        String theirFireDir = System.getProperty("their.fires.dir");
        if (theirFireDir != null && !theirFireDir.isBlank()) {
            try {
                orchestrator.setTheirFireTrace(new TheirFireTraceWriter(new File(theirFireDir), robotB));
            } catch (IOException e) {
                throw new RuntimeException("Failed to initialize their-fire trace writer", e);
            }
        }

        // Per-event Layer 2 damage-observation diff trace (damage-events.csv).
        String damageEventsDir = System.getProperty("damage.events.dir");
        if (damageEventsDir != null && !damageEventsDir.isBlank()) {
            try {
                orchestrator.setDamageEventsTrace(new DamageEventsTraceWriter(new File(damageEventsDir), robotB));
            } catch (IOException e) {
                throw new RuntimeException("Failed to initialize damage-events trace writer", e);
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
                if (!"cz.zamboch.Autopilot".equals(robotA) && results.length >= 2) {
                    result.ourScore = results[0].getScore();
                    result.ourFirsts = results[0].getFirsts();
                    result.opponentScore = results[1].getScore();
                }
                result.totalRounds = rounds;
            }
        });

        // Control-API hit-rate listener: counts our fired bullets and how many
        // reach HIT_VICTIM (rising edge), straight from the turn snapshots. Works
        // even when CSV output is disabled.
        final String hitRateToken = simpleName(robotA);
        engine.addBattleListener(new BattleAdaptor() {
            private final Map<Long, BulletState> prevStates = new HashMap<>();
            private int ourIndex = -1;
            private int currentRound = -1;

            @Override
            public void onTurnEnded(TurnEndedEvent event) {
                ITurnSnapshot snap = event.getTurnSnapshot();
                if (snap == null) {
                    return;
                }
                // Bullet ids reset each round; flush the prev-state map at round
                // boundaries so a fresh bullet is counted as fired.
                if (snap.getRound() != currentRound) {
                    currentRound = snap.getRound();
                    prevStates.clear();
                }
                if (ourIndex < 0) {
                    for (IRobotSnapshot r : snap.getRobots()) {
                        String n = r.getName();
                        if (n != null && n.contains(hitRateToken)) {
                            ourIndex = r.getRobotIndex();
                            break;
                        }
                    }
                }
                for (IBulletSnapshot b : snap.getBullets()) {
                    if (b.getOwnerIndex() != ourIndex) {
                        continue;
                    }
                    long key = bulletKey(b);
                    BulletState prev = prevStates.get(key);
                    if (prev == null) {
                        result.bulletsFired++;
                    }
                    if (b.getState() == BulletState.HIT_VICTIM && (prev == null || !isTerminalBulletState(prev))) {
                        result.bulletsHit++;
                    }
                }
                prevStates.clear();
                for (IBulletSnapshot b : snap.getBullets()) {
                    prevStates.put(bulletKey(b), b.getState());
                }
            }
        });

        try {
            String robotFilter = robotA + "," + robotB;
            RobotSpecification[] robots = engine.getLocalRepository(robotFilter);

            RobotSpecification ourBot = null;
            RobotSpecification oppBot = null;
            for (RobotSpecification spec : robots) {
                String name = spec.getClassName();
                if (robotA.equals(name)) {
                    ourBot = spec;
                }
                if (robotB.equals(name)) {
                    oppBot = spec;
                }
            }

            if (ourBot == null) {
                throw new IllegalStateException("Cannot find robot A: " + robotA);
            }
            if (oppBot == null) {
                throw new IllegalStateException("Cannot find robot B: " + robotB);
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

    /** Simple class name from a fully-qualified name (used to match snapshot robot names). */
    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    /** Collision-free key packing the owner index and bullet id. */
    private static long bulletKey(IBulletSnapshot bullet) {
        return (((long) bullet.getOwnerIndex()) << 32) | (bullet.getBulletId() & 0xFFFFFFFFL);
    }

    /** A bullet state from which a bullet no longer transitions (terminal). */
    private static boolean isTerminalBulletState(BulletState state) {
        return state == BulletState.HIT_VICTIM
                || state == BulletState.HIT_WALL
                || state == BulletState.HIT_BULLET;
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
        System.out.printf("Hit rate: %.1f%% (%d/%d)%n",
                result.getHitRate() * 100, result.getBulletsHit(), result.getBulletsFired());

        if (result.orchestrator().validator() != null) {
            result.orchestrator().validator().printSummary();
        }
    }
}

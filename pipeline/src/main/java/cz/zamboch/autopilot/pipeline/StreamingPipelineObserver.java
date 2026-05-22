package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.VcsFile;
import cz.zamboch.autopilot.core.VcsStore;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.FireFeatures;
import cz.zamboch.autopilot.core.features.MovementFeatures;
import cz.zamboch.autopilot.core.features.OurWaveFeatures;
import cz.zamboch.autopilot.core.features.SpatialFeatures;
import cz.zamboch.autopilot.core.features.TimingFeatures;
import cz.zamboch.autopilot.core.features.WaveTracker;
import robocode.control.events.BattleAdaptor;
import robocode.control.events.BattleCompletedEvent;
import robocode.control.events.BattleErrorEvent;
import robocode.control.events.RoundEndedEvent;
import robocode.control.events.TurnEndedEvent;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;

import java.io.File;
import java.io.IOException;

/**
 * Battle observer that streams turn snapshots directly into the
 * Whiteboard → Transformer → CsvWriter pipeline. No .br intermediate.
 */
final class StreamingPipelineObserver extends BattleAdaptor {
    private final double bfWidth;
    private final double bfHeight;

    // Pipeline state
    private Perspective[] perspectives;
    private Player player;
    private DebugValidator validator;
    private GodViewValidator godView;
    private WaveResolver waveResolver;
    private String battleId;
    private int currentRound = -1;
    private boolean ourDetected = false;

    // VCS persistence
    private File vcsDataFile;
    private int opponentHash;
    private boolean vcsLoaded = false;

    // Battle results (populated by onBattleCompleted)
    private int ourScore;
    private int opponentScore;
    private int ourFirsts;
    private int opponentFirsts;
    private int totalRounds;

    StreamingPipelineObserver(String outputDir, double bfWidth, double bfHeight) {
        this.bfWidth = bfWidth;
        this.bfHeight = bfHeight;

        Whiteboard wb0 = createWhiteboard();
        Whiteboard wb1 = createWhiteboard();
        perspectives = Perspective.createPair(wb0, wb1);
        player = new Player(perspectives);
        validator = new DebugValidator();
        godView = new GodViewValidator();
        waveResolver = new WaveResolver();

        if (outputDir != null) {
            try {
                battleId = "battle-" + System.currentTimeMillis();

                File dirA = new File(outputDir, battleId + "/Autopilot");
                File dirB = new File(outputDir, battleId + "/Opponent");
                perspectives[0].setCsv(new CsvWriter(dirA));
                perspectives[1].setCsv(new CsvWriter(dirB));
                perspectives[0].csv().writeHeaders(battleId);
                perspectives[1].csv().writeHeaders(battleId);
            } catch (IOException e) {
                System.err.println("ERROR: Cannot create CSV output: " + e.getMessage());
                perspectives[0].setCsv(null);
                perspectives[1].setCsv(null);
            }
        }
    }

    @Override
    public void onTurnEnded(TurnEndedEvent event) {
        ITurnSnapshot turn = event.getTurnSnapshot();
        IRobotSnapshot[] robots = turn.getRobots();
        if (robots == null || robots.length < 2)
            return;

        int roundIndex = turn.getRound();

        // Detect new round — finalize previous
        if (roundIndex != currentRound && currentRound >= 0) {
            finalizeRound();
        }
        if (roundIndex != currentRound) {
            godView.resetRound();
            waveResolver.resetRound();
        }
        currentRound = roundIndex;

        // Feed snapshot into Player (populates whiteboards with features)
        player.processTurn(roundIndex, turn, bfWidth, bfHeight);

        // Compute derived features
        for (Perspective us : perspectives) {
            us.wb().process();
        }

        // Resolve our-waves using god-view positions
        boolean[] waveResolved = waveResolver.processTick(perspectives, robots, turn);

        // Write CSV if enabled
        if (perspectives[0].csv() != null) {
            try {
                for (Perspective us : perspectives) {
                    us.csv().writeTickRow(us.wb(), battleId, roundIndex);
                    // Write their-waves at resolution time (peer's wave reached us)
                    if (waveResolved[us.peer().robotIndex()]) {
                        us.csv().writeTheirWaveRow(us.wb(), battleId, roundIndex);
                    }
                    if (waveResolved[us.robotIndex()]) {
                        us.csv().writeOurWaveRow(us.wb(), battleId, roundIndex);
                        writeVirtualBulletRows(us, battleId, roundIndex);
                    }
                }
            } catch (IOException e) {
                System.err.println("CSV write error: " + e.getMessage());
            }
        }

        // Validate only while both robots are alive and active
        boolean bothAlive = robots[0].getState() != RobotState.DEAD
                && robots[1].getState() != RobotState.DEAD
                && robots[0].getEnergy() > 0
                && robots[1].getEnergy() > 0;

        if (bothAlive) {
            // Detect our robot by name on first opportunity
            if (!ourDetected) {
                for (Perspective us : perspectives) {
                    if ("cz.zamboch.Autopilot".equals(robots[us.robotIndex()].getShortName())) {
                        us.setOurs(true);
                        ourDetected = true;

                        // Load VCS for our perspective
                        if (!vcsLoaded) {
                            String oppName = robots[us.peer().robotIndex()].getShortName();
                            int sp = oppName.indexOf(' ');
                            String botId = (sp < 0) ? oppName : oppName.substring(0, sp);
                            opponentHash = RoboMath.fnv1a32(botId);

                            String robotsPath = System.getProperty("ROBOTPATH");
                            if (robotsPath != null) {
                                vcsDataFile = new File(robotsPath,
                                        ".data/cz/zamboch/Autopilot.data/vcs.dat");
                                VcsStore store = VcsFile.loadForOpponent(vcsDataFile, opponentHash);
                                us.wb().setVcsStore(store);
                            }
                            vcsLoaded = true;
                        }
                        break;
                    }
                }
            }

            // Debug validator only runs for our robot (has debug properties)
            for (Perspective us : perspectives) {
                if (us.isOurs()) {
                    validator.validate(robots[us.robotIndex()], us.wb());
                }
            }

            godView.validate(perspectives, robots, turn);
        }

        // Track last snapshots for round finalization
        for (Perspective us : perspectives) {
            us.setLastRobot(robots[us.robotIndex()]);
        }
    }

    /**
     * Write K virtual bullet rows after the real wave row.
     * Temporarily overrides AIM_GF, IS_REAL, and BREAK_HIT on the whiteboard staging features,
     * writes the row, then restores the real values.
     */
    private void writeVirtualBulletRows(Perspective us, String battleId, int roundIndex) throws IOException {
        Whiteboard wb = us.wb();
        // Save real values
        double realAimGf = wb.getFeature(Feature.OUR_FIRE_AIM_GF);
        double realIsReal = wb.getFeature(Feature.OUR_FIRE_IS_REAL);
        double realBreakHit = wb.getFeature(Feature.OUR_BREAK_HIT);

        // Fire geometry for geometric hit computation
        double fireX = wb.getFeature(Feature.OUR_FIRE_X);
        double fireY = wb.getFeature(Feature.OUR_FIRE_Y);
        double fireBearing = wb.getFeature(Feature.OUR_FIRE_BEARING_ABSOLUTE);
        double mea = wb.getFeature(Feature.OUR_FIRE_MEA);
        double direction = wb.getFeature(Feature.OUR_FIRE_DIRECTION);
        double oppX = wb.getFeature(Feature.OUR_BREAK_OPPONENT_X);
        double oppY = wb.getFeature(Feature.OUR_BREAK_OPPONENT_Y);

        wb.setFeature(Feature.OUR_FIRE_IS_REAL, 0.0);

        for (int i = 0; i < WaveTracker.VIRTUAL_BULLET_COUNT; i++) {
            double virtualGf = -1.0 + 2.0 * i / (WaveTracker.VIRTUAL_BULLET_COUNT - 1);
            boolean wouldHit = WaveTracker.computeWouldHit(
                    fireX, fireY, fireBearing, virtualGf, mea, (int) direction,
                    oppX, oppY);

            wb.setFeature(Feature.OUR_FIRE_AIM_GF, virtualGf);
            wb.setFeature(Feature.OUR_BREAK_HIT, wouldHit ? 1.0 : 0.0);
            us.csv().writeOurWaveRow(wb, battleId, roundIndex);
        }

        // Restore real values
        wb.setFeature(Feature.OUR_FIRE_AIM_GF, realAimGf);
        wb.setFeature(Feature.OUR_FIRE_IS_REAL, realIsReal);
        wb.setFeature(Feature.OUR_BREAK_HIT, realBreakHit);
    }

    @Override
    public void onRoundEnded(RoundEndedEvent event) {
        if (currentRound >= 0) {
            finalizeRound();
        }
    }

    private void finalizeRound() {
        if (perspectives[0].lastRobot() == null || perspectives[1].lastRobot() == null)
            return;
        player.finalizeRound(perspectives);

        // Set per-round hit rate from wave resolver
        for (Perspective us : perspectives) {
            us.wb().setFeature(Feature.ROUND_HIT_RATE, waveResolver.getRoundHitRate(us.robotIndex()));
        }

        if (perspectives[0].csv() != null) {
            try {
                for (Perspective us : perspectives) {
                    us.csv().writeScoreRow(us.wb(), battleId, currentRound);
                }
            } catch (IOException e) {
                System.err.println("CSV write error: " + e.getMessage());
            }
        }
    }

    @Override
    public void onBattleCompleted(BattleCompletedEvent event) {
        System.out.println("=== BATTLE RESULTS ===");
        System.out.println(String.format("%-30s %8s %8s %8s %8s %8s",
                "Robot", "Score", "Bullets", "Ram", "1sts", "Survival"));

        robocode.BattleResults[] results = event.getSortedResults();
        for (robocode.BattleResults r : results) {
            System.out.println(String.format("%-30s %8d %8d %8d %8d %8d",
                    r.getTeamLeaderName(),
                    r.getScore(),
                    r.getBulletDamage(),
                    r.getRamDamage(),
                    r.getFirsts(),
                    r.getSurvival()));
        }
        System.out.println();

        // Store results — our robot is "cz.zamboch.Autopilot"
        totalRounds = Integer.parseInt(System.getProperty("battle.rounds", "10"));
        for (robocode.BattleResults r : results) {
            if (r.getTeamLeaderName().contains("Autopilot")) {
                ourScore = r.getScore();
                ourFirsts = r.getFirsts();
            } else {
                opponentScore = r.getScore();
                opponentFirsts = r.getFirsts();
            }
        }

        if (perspectives[0].csv() != null) {
            System.out.println("CSV output: " + battleId);
        }

        // Save VCS data
        if (vcsDataFile != null && vcsLoaded) {
            for (Perspective us : perspectives) {
                if (us.isOurs()) {
                    VcsStore store = us.wb().getVcsStore();
                    if (store != null) {
                        try {
                            vcsDataFile.getParentFile().mkdirs();
                            VcsFile.saveForOpponent(vcsDataFile, opponentHash, store);
                        } catch (IOException e) {
                            System.err.println("VCS save error: " + e.getMessage());
                        }
                    }
                    break;
                }
            }
        }

        validator.printSummary();
        godView.printSummary();
    }

    @Override
    public void onBattleError(BattleErrorEvent event) {
        System.err.println("Battle error: " + event.getError());
    }

    void close() {
        try {
            for (Perspective us : perspectives) {
                if (us.csv() != null)
                    us.csv().close();
            }
        } catch (IOException e) {
            System.err.println("Error closing CSV: " + e.getMessage());
        }
    }

    private static Whiteboard createWhiteboard() {
        Whiteboard wb = new Whiteboard();
        wb.registerFeatures(
                new SpatialFeatures(),
                new MovementFeatures(),
                new TimingFeatures(),
                new FireFeatures(),
                new OurWaveFeatures());
        return wb;
    }

    // --- Results accessors (for test assertions) ---

    int getOurScore() {
        return ourScore;
    }

    int getOpponentScore() {
        return opponentScore;
    }

    int getOurFirsts() {
        return ourFirsts;
    }

    int getOpponentFirsts() {
        return opponentFirsts;
    }

    int getTotalRounds() {
        return totalRounds;
    }

    double getWinRate() {
        return totalRounds > 0 ? (double) ourFirsts / totalRounds : 0;
    }

    double getScoreRatio() {
        return opponentScore > 0 ? (double) ourScore / opponentScore : (ourScore > 0 ? 999.0 : 1.0);
    }

    GodViewValidator getGodViewValidator() {
        return godView;
    }

    DebugValidator getDebugValidator() {
        return validator;
    }
}

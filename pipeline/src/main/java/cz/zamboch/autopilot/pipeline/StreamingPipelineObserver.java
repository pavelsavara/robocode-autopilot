package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.FireFeatures;
import cz.zamboch.autopilot.core.features.MovementFeatures;
import cz.zamboch.autopilot.core.features.SpatialFeatures;
import cz.zamboch.autopilot.core.features.TimingFeatures;
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
    private String battleId;
    private int currentRound = -1;
    private boolean ourDetected = false;

    StreamingPipelineObserver(String outputDir, double bfWidth, double bfHeight) {
        this.bfWidth = bfWidth;
        this.bfHeight = bfHeight;

        Whiteboard wb0 = createWhiteboard();
        Whiteboard wb1 = createWhiteboard();
        perspectives = Perspective.createPair(wb0, wb1);
        player = new Player(perspectives);
        validator = new DebugValidator();
        godView = new GodViewValidator();

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
        }
        currentRound = roundIndex;

        // Feed snapshot into Player (populates whiteboards with features)
        player.processTurn(roundIndex, turn, bfWidth, bfHeight);

        // Compute derived features
        for (Perspective us : perspectives) {
            us.wb().process();
        }

        // Write CSV if enabled
        if (perspectives[0].csv() != null) {
            try {
                for (Perspective us : perspectives) {
                    us.csv().writeTickRow(us.wb(), battleId, roundIndex);
                    if (!Double.isNaN(us.wb().getFeature(Feature.OPPONENT_FIRE_POWER))) {
                        us.csv().writeWaveRow(us.wb(), battleId, roundIndex);
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

        if (perspectives[0].csv() != null) {
            System.out.println("CSV output: " + battleId);
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
                new FireFeatures());
        return wb;
    }
}

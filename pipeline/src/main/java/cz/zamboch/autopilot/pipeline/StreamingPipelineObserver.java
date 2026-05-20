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

    // Pipeline state (null if outputDir not set = results-only mode)
    private Whiteboard wbA;
    private Whiteboard wbB;
    private Player player;
    private CsvWriter csvA;
    private CsvWriter csvB;
    private DebugValidator validator;
    private GodViewValidator godView;
    private String battleId;
    private int currentRound = -1;
    private IRobotSnapshot lastRobotA;
    private IRobotSnapshot lastRobotB;

    StreamingPipelineObserver(String outputDir, double bfWidth, double bfHeight) {
        this.bfWidth = bfWidth;
        this.bfHeight = bfHeight;

        wbA = createWhiteboard();
        wbB = createWhiteboard();
        player = new Player(wbA, wbB);
        validator = new DebugValidator();
        godView = new GodViewValidator();

        if (outputDir != null) {
            try {
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
        IRobotSnapshot[] robots = turn.getRobots();
        if (robots == null || robots.length < 2)
            return;

        int roundIndex = turn.getRound();

        // Detect new round — finalize previous
        if (roundIndex != currentRound && currentRound >= 0) {
            finalizeRound();
        }
        currentRound = roundIndex;

        // Feed snapshot into Player (populates whiteboards with features)
        player.processTurn(roundIndex, turn, bfWidth, bfHeight);

        // Compute derived features
        wbA.process();
        wbB.process();

        // Write CSV if enabled
        if (csvA != null) {
            try {
                csvA.writeTickRow(wbA, battleId, roundIndex);
                csvB.writeTickRow(wbB, battleId, roundIndex);

                if (!Double.isNaN(wbA.getFeature(Feature.OPPONENT_FIRE_POWER))) {
                    csvA.writeWaveRow(wbA, battleId, roundIndex);
                }
                if (!Double.isNaN(wbB.getFeature(Feature.OPPONENT_FIRE_POWER))) {
                    csvB.writeWaveRow(wbB, battleId, roundIndex);
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
            validator.validate(robots[0], wbA);
            godView.validate(wbA, robots[0], wbB, robots[1], turn);
        }

        // Track last snapshots for round finalization
        lastRobotA = robots[0];
        lastRobotB = robots[1];
    }

    @Override
    public void onRoundEnded(RoundEndedEvent event) {
        if (currentRound >= 0) {
            finalizeRound();
        }
    }

    private void finalizeRound() {
        if (lastRobotA == null || lastRobotB == null)
            return;
        player.finalizeRound(wbA, wbB, lastRobotA, lastRobotB);
        if (csvA != null) {
            try {
                csvA.writeScoreRow(wbA, battleId, currentRound);
                csvB.writeScoreRow(wbB, battleId, currentRound);
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

        if (csvA != null) {
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
            if (csvA != null)
                csvA.close();
            if (csvB != null)
                csvB.close();
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

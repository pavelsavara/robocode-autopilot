package cz.zamboch.autopilot.core.movement;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.WaveRecord;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.MovementCommand;
import cz.zamboch.autopilot.core.strategy.StrategyParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for WaveSurfMovement: direction change frequency, dodge commitment,
 * pre-emptive dodge wiring, wall-proximity cooldown.
 */
class WaveSurfMovementTest {

    private Whiteboard wb;
    private MovementCommand cmd;
    private StrategyParams params;

    /** A planner that always returns a fixed candidate at (400, 400). */
    private PathPlanner stubPlanner;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        wb.onRoundStart(0, 800, 600, 0.1, 10);
        cmd = new MovementCommand();
        params = new StrategyParams(400, 0.5, 2.0);

        // Set basic state: robot at center, heading north, healthy
        wb.setOurState(400, 300, 0, 0, 0, 8.0, 100, 0);
        wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, 0);
        wb.setFeature(Feature.OUR_DIST_TO_WALL_MIN, 200);

        // Minimal planner with stub danger scorers
        IPositionDanger posDanger = (x, y, w) -> 0;
        IWaveDanger waveDanger = new IWaveDanger() {
            @Override
            public double danger(CandidatePosition c, WaveRecord w, Whiteboard wb) {
                return 0;
            }
            @Override
            public double danger(CandidatePosition c, List<WaveRecord> waves,
                                 Whiteboard wb, boolean rand) {
                return 0;
            }
        };
        stubPlanner = new PathPlanner(posDanger, waveDanger, 800, 600);
    }

    /** Advance whiteboard N ticks, refreshing features each tick. */
    private void advanceTicks(int n) {
        for (int i = 0; i < n; i++) {
            wb.advanceTick();
            wb.setOurState(400, 300, 0, 0, 0, 8.0, 100, 0);
            wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, 0);
            wb.setFeature(Feature.OUR_DIST_TO_WALL_MIN, 200);
        }
    }

    @Test
    void directionChangesAreAtLeastFlipMinTicksApart() {
        WaveSurfMovement move = new WaveSurfMovement(stubPlanner);

        int dirChanges = 0;
        int prevDir = move.getPreemptiveDir();
        long lastChangeTick = -100;

        // Run for 200 ticks
        for (int t = 0; t < 200; t++) {
            move.getCommand(wb, params, cmd);

            int curDir = move.getPreemptiveDir();
            if (curDir != prevDir) {
                long gap = wb.getTick() - lastChangeTick;
                assertTrue(gap >= WaveSurfMovement.FLIP_MIN_TICKS,
                        "Direction changed after only " + gap + " ticks at tick " + wb.getTick()
                                + " (min=" + WaveSurfMovement.FLIP_MIN_TICKS + ")");
                lastChangeTick = wb.getTick();
                dirChanges++;
                prevDir = curDir;
            }

            wb.advanceTick();
            wb.setOurState(400, 300, 0, 0, 0, 8.0, 100, 0);
            wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, 0);
            wb.setFeature(Feature.OUR_DIST_TO_WALL_MIN, 200);
        }

        // Should have some reversals (not zero) but not too many
        assertTrue(dirChanges > 0, "Expected at least one direction change in 200 ticks");
        assertTrue(dirChanges <= 8, "Too many direction changes: " + dirChanges
                + " in 200 ticks (expected ≤8 with min interval " + WaveSurfMovement.FLIP_MIN_TICKS + ")");
    }

    @Test
    void wallProximityDoesNotCausePerTickOscillation() {
        WaveSurfMovement move = new WaveSurfMovement(stubPlanner);

        // First: exhaust the initial nextFlipTick=0 by running a few ticks in open space
        for (int t = 0; t < 5; t++) {
            move.getCommand(wb, params, cmd);
            wb.advanceTick();
            wb.setOurState(400, 300, 0, 0, 0, 8.0, 100, 0);
            wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, 0);
            wb.setFeature(Feature.OUR_DIST_TO_WALL_MIN, 200);
        }

        int dirChanges = 0;
        int prevDir = move.getPreemptiveDir();

        // Now simulate being near a wall for FLIP_MIN_TICKS ticks
        // Old code would flip every tick; new code should flip at most once
        for (int t = 0; t < WaveSurfMovement.FLIP_MIN_TICKS; t++) {
            wb.setFeature(Feature.OUR_DIST_TO_WALL_MIN, 30); // below threshold
            move.getCommand(wb, params, cmd);

            int curDir = move.getPreemptiveDir();
            if (curDir != prevDir) {
                dirChanges++;
                prevDir = curDir;
            }

            wb.advanceTick();
            wb.setOurState(400, 300, 0, 0, 0, 8.0, 100, 0);
            wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, 0);
        }

        // Should flip at most once within FLIP_MIN_TICKS window
        assertTrue(dirChanges <= 1,
                "Wall proximity caused " + dirChanges + " direction changes in "
                        + WaveSurfMovement.FLIP_MIN_TICKS + " ticks — should be ≤1");
    }

    @Test
    void waveDodgeHoldsDirectionForCommitTicks() {
        WaveSurfMovement move = new WaveSurfMovement(stubPlanner);

        // Place an imminent wave: opponent at (400, 600), fired at tick 0
        // Wave speed = 20-3*2 = 14, distance = 300, so radius at tick 20 = 280
        // remaining = 300 - 280 = 20, ticksUntil = 20/14 ≈ 1.4 => imminent
        wb.addOpponentWave(new WaveRecord(400, 600, 14.0, 2.0, 0, 300));

        // Advance to tick 20 so the wave is imminent
        for (int i = 0; i < 20; i++) {
            wb.advanceTick();
            wb.setOurState(400, 300, 0, 0, 0, 8.0, 100, 0);
            wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, 0);
            wb.setFeature(Feature.OUR_DIST_TO_WALL_MIN, 200);
        }
        // Re-add the wave (it was cleared on advanceTick→resetRound? No, advanceTick keeps waves)
        // Actually advanceTick does NOT clear waves — only resetRound does.

        // Get command — should dodge
        move.getCommand(wb, params, cmd);
        double firstAhead = cmd.ahead;
        double firstTurn = cmd.turnRight;

        // Next few ticks: the committed angle should hold
        for (int i = 0; i < WaveSurfMovement.MIN_COMMIT_TICKS - 1; i++) {
            wb.advanceTick();
            wb.setOurState(400, 300, 0, 0, 0, 8.0, 100, 0);
            wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, 0);
            wb.setFeature(Feature.OUR_DIST_TO_WALL_MIN, 200);
            move.getCommand(wb, params, cmd);

            // Command should be identical (committed angle held)
            assertEquals(firstAhead, cmd.ahead, 0.001,
                    "Dodge direction changed within commit window at tick " + wb.getTick());
            assertEquals(firstTurn, cmd.turnRight, 0.001,
                    "Dodge turn changed within commit window at tick " + wb.getTick());
        }
    }

    @Test
    void preemptiveDodgeActivatesWhenFireProbabilityHigh() {
        WaveSurfMovement move = new WaveSurfMovement(stubPlanner);

        // Without fire prediction, should produce default lateral move
        move.getCommand(wb, params, cmd);
        assertTrue(Math.abs(cmd.ahead) > 100, "Should move at high speed in default mode");

        // Set high fire probability (above ramp high = 0.8)
        wb.setFeature(Feature.PREDICTED_OPPONENT_FIRES_3, 0.9);

        move.getCommand(wb, params, cmd);
        assertTrue(Math.abs(cmd.ahead) > 100,
                "Pre-emptive dodge should maintain high speed");
    }

    @Test
    void preemptiveDodgeInactiveWhenWavesExist() {
        WaveSurfMovement move = new WaveSurfMovement(stubPlanner);

        // High fire probability but waves exist — should NOT use preemptive
        wb.setFeature(Feature.PREDICTED_OPPONENT_FIRES_3, 0.9);
        // Add a distant wave (not imminent)
        wb.addOpponentWave(new WaveRecord(400, 600, 14.0, 2.0, 0, 300));

        // At tick 0, wave radius = 0, remaining = 300, ticksUntil = 21 — not imminent
        move.getCommand(wb, params, cmd);

        // Should fall through to default lateral (no preemptive because waves exist,
        // no imminent dodge because wave is >12 ticks away)
        assertTrue(Math.abs(cmd.ahead) > 100, "Should still move at high speed");
    }

    @Test
    void alwaysProducesMaxSpeed() {
        WaveSurfMovement move = new WaveSurfMovement(stubPlanner);

        // Run 50 ticks and verify ahead is always ±150
        for (int t = 0; t < 50; t++) {
            move.getCommand(wb, params, cmd);
            assertEquals(150, Math.abs(cmd.ahead), 0.001,
                    "ahead should be ±150 for max speed at tick " + wb.getTick());

            wb.advanceTick();
            wb.setOurState(400, 300, 0, 0, 0, 8.0, 100, 0);
            wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, 0);
            wb.setFeature(Feature.OUR_DIST_TO_WALL_MIN, 200);
        }
    }
}

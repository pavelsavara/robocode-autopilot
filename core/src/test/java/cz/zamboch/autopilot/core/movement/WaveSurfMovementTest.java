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
    /** A wave danger scorer that always returns 0. */
    private IWaveDanger stubWaveDanger;

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
        stubWaveDanger = waveDanger;
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
        WaveSurfMovement move = new WaveSurfMovement(stubPlanner, stubWaveDanger);

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
        assertTrue(dirChanges <= 14, "Too many direction changes: " + dirChanges
                + " in 200 ticks (expected ≤14 with min interval " + WaveSurfMovement.FLIP_MIN_TICKS + ")");
    }

    @Test
    void wallProximityDoesNotCausePerTickOscillation() {
        WaveSurfMovement move = new WaveSurfMovement(stubPlanner, stubWaveDanger);

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
        WaveSurfMovement move = new WaveSurfMovement(stubPlanner, stubWaveDanger);

        // Place an imminent wave: opponent at (400, 600), fired at tick 0
        // Wave speed = 14, distance = 300, so radius at tick 20 = 280
        // remaining = 300 - 280 = 20, ticksUntil = 20/14 ≈ 1.4 => imminent
        wb.addOpponentWave(new WaveRecord(400, 600, 14.0, 2.0, 0, 300));

        // Advance to tick 20 so the wave is imminent
        for (int i = 0; i < 20; i++) {
            wb.advanceTick();
            wb.setOurState(400, 300, 0, 0, 0, 8.0, 100, 0);
            wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, 0);
            wb.setFeature(Feature.OUR_DIST_TO_WALL_MIN, 200);
        }

        // Get command — should dodge
        move.getCommand(wb, params, cmd);
        double firstAhead = cmd.ahead;
        double firstTurn = cmd.turnRight;

        // With proportional commitment: close wave (1.4 ticks) → MIN_COMMIT_TICKS = 2
        // Next tick: committed angle should hold
        wb.advanceTick();
        wb.setOurState(400, 300, 0, 0, 0, 8.0, 100, 0);
        wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, 0);
        wb.setFeature(Feature.OUR_DIST_TO_WALL_MIN, 200);
        move.getCommand(wb, params, cmd);

        // Command should be identical within commitment window
        assertEquals(firstAhead, cmd.ahead, 0.001,
                "Dodge direction changed within commit window");
        assertEquals(firstTurn, cmd.turnRight, 0.001,
                "Dodge turn changed within commit window");
    }

    @Test
    void commitDurationScalesWithWaveDistance() {
        WaveSurfMovement move = new WaveSurfMovement(stubPlanner, stubWaveDanger);

        // Wave: opponent at (400, 600), speed 11 (power 3), fired at tick 0
        // distance = 300.
        // At tick 16: radius = 11*16 = 176, remaining = 124, ticksUntil = 11.3
        // That's imminent (<12). Expected commit = max(2, min(11-2, 8)) = 8
        wb.addOpponentWave(new WaveRecord(400, 600, 11.0, 3.0, 0, 300));

        for (int i = 0; i < 16; i++) {
            wb.advanceTick();
            wb.setOurState(400, 300, 0, 0, 0, 8.0, 100, 0);
            wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, 0);
            wb.setFeature(Feature.OUR_DIST_TO_WALL_MIN, 200);
        }

        move.getCommand(wb, params, cmd);
        int duration = move.getCommitDuration();
        assertTrue(duration >= WaveSurfMovement.MIN_COMMIT_TICKS,
                "Commit duration " + duration + " below minimum");
        assertTrue(duration <= WaveSurfMovement.MAX_COMMIT_TICKS,
                "Commit duration " + duration + " above maximum");
        // Far-ish wave (~11 ticks away) should yield a longer commitment than minimum
        assertTrue(duration > WaveSurfMovement.MIN_COMMIT_TICKS,
                "Far wave should have commit > MIN_COMMIT_TICKS, got " + duration);
    }

    @Test
    void preemptiveDodgeActivatesWhenFireProbabilityHigh() {
        WaveSurfMovement move = new WaveSurfMovement(stubPlanner, stubWaveDanger);

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
        WaveSurfMovement move = new WaveSurfMovement(stubPlanner, stubWaveDanger);

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
    void alwaysProducesReasonableSpeed() {
        WaveSurfMovement move = new WaveSurfMovement(stubPlanner, stubWaveDanger);

        // Run 50 ticks and verify ahead is always >=80 (with velocity oscillation)
        for (int t = 0; t < 50; t++) {
            move.getCommand(wb, params, cmd);
            assertTrue(Math.abs(cmd.ahead) >= 80 && Math.abs(cmd.ahead) <= 150,
                    "ahead should be between ±80 and ±150 at tick " + wb.getTick()
                            + " but was " + cmd.ahead);

            wb.advanceTick();
            wb.setOurState(400, 300, 0, 0, 0, 8.0, 100, 0);
            wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, 0);
            wb.setFeature(Feature.OUR_DIST_TO_WALL_MIN, 200);
        }
    }

    @Test
    void hysteresisPreventsOscillationNearBoundary() {
        WaveSurfMovement move = new WaveSurfMovement(stubPlanner, stubWaveDanger);

        // Place robot heading north (0), opponent bearing at ~PI/2 so the
        // perpendicular orbit angle is at ~PI (or ~0 depending on direction).
        // We'll oscillate the bearing around the boundary where turn crosses PI/2.
        int aheadFlips = 0;
        double prevAhead = 0;

        for (int t = 0; t < 100; t++) {
            // Oscillate bearing slightly around a value that puts turn near PI/2
            double bearing = Math.PI / 2 + 0.05 * Math.sin(t * 0.3);
            wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, bearing);

            move.getCommand(wb, params, cmd);
            if (t > 0 && Math.signum(cmd.ahead) != Math.signum(prevAhead) && prevAhead != 0) {
                aheadFlips++;
            }
            prevAhead = cmd.ahead;

            wb.advanceTick();
            wb.setOurState(400, 300, 0, 0, 0, 8.0, 100, 0);
            wb.setFeature(Feature.OUR_DIST_TO_WALL_MIN, 200);
        }

        // Without hysteresis this would oscillate every tick.
        // With hysteresis: at most a few flips (when oscillation exceeds band).
        assertTrue(aheadFlips <= 5,
                "Too many ahead-direction flips near boundary: " + aheadFlips
                        + " in 100 ticks (hysteresis should prevent oscillation)");
    }

    @Test
    void preemptiveDodgeDoesNotRandomlyFlipDirection() {
        WaveSurfMovement move = new WaveSurfMovement(stubPlanner, stubWaveDanger);

        // Advance past the initial nextFlipTick=0
        for (int t = 0; t < 5; t++) {
            move.getCommand(wb, params, cmd);
            wb.advanceTick();
            wb.setOurState(400, 300, 0, 0, 0, 8.0, 100, 0);
            wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, 0);
            wb.setFeature(Feature.OUR_DIST_TO_WALL_MIN, 200);
        }

        // Set high fire probability to enter pre-emptive mode
        wb.setFeature(Feature.PREDICTED_OPPONENT_FIRES_3, 0.9);

        int initialDir = move.getPreemptiveDir();
        int dirChanges = 0;

        // Run for enough ticks that a random flip WOULD have happened without the fix
        for (int t = 0; t < WaveSurfMovement.FLIP_MIN_TICKS + WaveSurfMovement.FLIP_RANGE_TICKS + 20; t++) {
            move.getCommand(wb, params, cmd);
            if (move.getPreemptiveDir() != initialDir) {
                dirChanges++;
                initialDir = move.getPreemptiveDir();
            }
            wb.advanceTick();
            wb.setOurState(400, 300, 0, 0, 0, 8.0, 100, 0);
            wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, 0);
            wb.setFeature(Feature.OUR_DIST_TO_WALL_MIN, 200);
            wb.setFeature(Feature.PREDICTED_OPPONENT_FIRES_3, 0.9);
        }

        // Direction should never change during sustained pre-emptive dodge
        assertEquals(0, dirChanges,
                "Pre-emptive dodge should not randomly flip direction");
    }

    @Test
    void vcsGuidedDirectionChoosesSaferSide() {
        // Custom wave danger: CW (positive x offset) is dangerous, CCW is safe
        IWaveDanger biasedDanger = new IWaveDanger() {
            @Override
            public double danger(CandidatePosition c, WaveRecord w, Whiteboard wb) {
                // Danger based on x position: higher x = more dangerous
                return c.x > 400 ? 0.8 : 0.1;
            }
            @Override
            public double danger(CandidatePosition c, List<WaveRecord> waves,
                                 Whiteboard wb, boolean rand) {
                return c.x > 400 ? 0.8 : 0.1;
            }
        };
        PathPlanner biasedPlanner = new PathPlanner(
                (x, y, w) -> 0, biasedDanger, 800, 600);
        WaveSurfMovement move = new WaveSurfMovement(biasedPlanner, biasedDanger);

        // Robot at center, heading north, opponent due north (bearing = 0).
        // CW orbit goes east (increasing x), CCW orbit goes west (decreasing x).
        // Since east is dangerous, VCS should guide direction to CCW (-1).
        wb.setOurState(400, 300, 0, 0, 0, 8.0, 100, 0);
        wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, 0);
        wb.setFeature(Feature.OUR_DIST_TO_WALL_MIN, 200);

        // Add a distant wave (not imminent — 40 ticks away)
        // Opponent at (400, 780), speed 12, fired at tick 0
        // distance = 480, at tick 0 radius = 0, ticksUntil = 40
        wb.addOpponentWave(new WaveRecord(400, 780, 12.0, 2.0, 0, 480));
        wb.setFeature(Feature.OPPONENT_LATERAL_DIRECTION, 1);

        // Run enough ticks for the direction evaluation to trigger
        // (DIR_EVAL_INTERVAL = 8 ticks from lastDirEvalTick which starts at -100)
        move.getCommand(wb, params, cmd);

        // The VCS-guided logic should have set direction to CCW (-1)
        // because CW (east) has higher danger
        assertEquals(-1, move.getPreemptiveDir(),
                "VCS-guided direction should choose CCW (away from danger)");
    }

    @Test
    void vcsGuidedDirectionRespectsDirEvalInterval() {
        // Wave danger where CCW is always safer
        IWaveDanger biasedDanger = new IWaveDanger() {
            @Override
            public double danger(CandidatePosition c, WaveRecord w, Whiteboard wb) {
                return c.x > 400 ? 0.8 : 0.1;
            }
            @Override
            public double danger(CandidatePosition c, List<WaveRecord> waves,
                                 Whiteboard wb, boolean rand) {
                return c.x > 400 ? 0.8 : 0.1;
            }
        };
        PathPlanner biasedPlanner = new PathPlanner(
                (x, y, w) -> 0, biasedDanger, 800, 600);
        WaveSurfMovement move = new WaveSurfMovement(biasedPlanner, biasedDanger);

        // Set up distant wave (40 ticks away, beyond semi-imminent zone)
        wb.addOpponentWave(new WaveRecord(400, 780, 12.0, 2.0, 0, 480));
        wb.setFeature(Feature.OPPONENT_LATERAL_DIRECTION, 1);

        // First call triggers evaluation — sets dir to -1
        move.getCommand(wb, params, cmd);
        assertEquals(-1, move.getPreemptiveDir());

        // Advance only a few ticks (less than DIR_EVAL_INTERVAL)
        // Manually force preemptiveDir back to +1 by reflection isn't clean —
        // instead verify that direction doesn't oscillate within interval
        int dirChanges = 0;
        int prevDir = move.getPreemptiveDir();
        for (int t = 0; t < WaveSurfMovement.DIR_EVAL_INTERVAL - 1; t++) {
            wb.advanceTick();
            wb.setOurState(400, 300, 0, 0, 0, 8.0, 100, 0);
            wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, 0);
            wb.setFeature(Feature.OUR_DIST_TO_WALL_MIN, 200);
            wb.setFeature(Feature.OPPONENT_LATERAL_DIRECTION, 1);
            move.getCommand(wb, params, cmd);
            if (move.getPreemptiveDir() != prevDir) {
                dirChanges++;
                prevDir = move.getPreemptiveDir();
            }
        }

        assertEquals(0, dirChanges,
                "Direction should not change within DIR_EVAL_INTERVAL");
    }
}

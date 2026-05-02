package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Whiteboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;

import static cz.zamboch.autopilot.pipeline.TestSnapshots.robot;
import static cz.zamboch.autopilot.pipeline.TestSnapshots.turn;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Player.finalizeRound() — verifies the win_rate bug fix.
 *
 * Before the fix, roundsWon/roundsLost were never incremented in the pipeline
 * (only Autopilot.onWin/onDeath did so), causing win_rate in scores.csv to
 * always be 0.0. Now Player.finalizeRound() inspects the last-seen robot
 * states and credits the winner.
 */
class PlayerFinalizeRoundTest {

    private Whiteboard wbA;
    private Whiteboard wbB;
    private Player player;

    @BeforeEach
    void setUp() {
        wbA = new Whiteboard();
        wbB = new Whiteboard();
        player = new Player(wbA, wbB);
    }

    @Test
    void finalizeBeforeAnyTurnIsNoOp() {
        player.finalizeRound();
        assertEquals(0, wbA.getRoundsWon());
        assertEquals(0, wbA.getRoundsLost());
        assertEquals(0, wbB.getRoundsWon());
        assertEquals(0, wbB.getRoundsLost());
    }

    @Test
    void aliveBeatsDead() {
        IRobotSnapshot rA = robot(100, 200, 0, 0, 50, 0, 0, 0, 0, RobotState.ACTIVE, "A");
        IRobotSnapshot rB = robot(500, 400, 0, 0, 0, 0, 0, 0, 1, RobotState.DEAD, "B");
        ITurnSnapshot turn = turn(100, rA, rB);
        player.processTurn(0, turn, 800, 600, 0.1, 10);

        player.finalizeRound();

        assertEquals(1, wbA.getRoundsWon());
        assertEquals(0, wbA.getRoundsLost());
        assertEquals(0, wbB.getRoundsWon());
        assertEquals(1, wbB.getRoundsLost());
    }

    @Test
    void deadLosesToAlive() {
        IRobotSnapshot rA = robot(100, 200, 0, 0, 0, 0, 0, 0, 0, RobotState.DEAD, "A");
        IRobotSnapshot rB = robot(500, 400, 0, 0, 50, 0, 0, 0, 1, RobotState.ACTIVE, "B");
        ITurnSnapshot turn = turn(100, rA, rB);
        player.processTurn(0, turn, 800, 600, 0.1, 10);

        player.finalizeRound();

        assertEquals(0, wbA.getRoundsWon());
        assertEquals(1, wbA.getRoundsLost());
        assertEquals(1, wbB.getRoundsWon());
        assertEquals(0, wbB.getRoundsLost());
    }

    @Test
    void simultaneousDeathIsTie() {
        IRobotSnapshot rA = robot(100, 200, 0, 0, 0, 0, 0, 0, 0, RobotState.DEAD, "A");
        IRobotSnapshot rB = robot(500, 400, 0, 0, 0, 0, 0, 0, 1, RobotState.DEAD, "B");
        ITurnSnapshot turn = turn(100, rA, rB);
        player.processTurn(0, turn, 800, 600, 0.1, 10);

        player.finalizeRound();

        assertEquals(0, wbA.getRoundsWon());
        assertEquals(0, wbA.getRoundsLost());
        assertEquals(0, wbB.getRoundsWon());
        assertEquals(0, wbB.getRoundsLost());
    }

    @Test
    void timeoutHigherEnergyWins() {
        // Both alive at last tick → higher energy wins (round-time-limit case).
        IRobotSnapshot rA = robot(100, 200, 0, 0, 75, 0, 0, 0, 0, RobotState.ACTIVE, "A");
        IRobotSnapshot rB = robot(500, 400, 0, 0, 30, 0, 0, 0, 1, RobotState.ACTIVE, "B");
        ITurnSnapshot turn = turn(2000, rA, rB);
        player.processTurn(0, turn, 800, 600, 0.1, 10);

        player.finalizeRound();

        assertEquals(1, wbA.getRoundsWon());
        assertEquals(1, wbB.getRoundsLost());
    }

    @Test
    void timeoutEqualEnergyIsTie() {
        IRobotSnapshot rA = robot(100, 200, 0, 0, 50, 0, 0, 0, 0, RobotState.ACTIVE, "A");
        IRobotSnapshot rB = robot(500, 400, 0, 0, 50, 0, 0, 0, 1, RobotState.ACTIVE, "B");
        ITurnSnapshot turn = turn(2000, rA, rB);
        player.processTurn(0, turn, 800, 600, 0.1, 10);

        player.finalizeRound();

        assertEquals(0, wbA.getRoundsWon());
        assertEquals(0, wbA.getRoundsLost());
        assertEquals(0, wbB.getRoundsWon());
        assertEquals(0, wbB.getRoundsLost());
    }

    @Test
    void winsAccumulateAcrossRounds() {
        IRobotSnapshot rAalive = robot(100, 200, 0, 0, 50, 0, 0, 0, 0, RobotState.ACTIVE, "A");
        IRobotSnapshot rBdead = robot(500, 400, 0, 0, 0, 0, 0, 0, 1, RobotState.DEAD, "B");
        IRobotSnapshot rAdead = robot(100, 200, 0, 0, 0, 0, 0, 0, 0, RobotState.DEAD, "A");
        IRobotSnapshot rBalive = robot(500, 400, 0, 0, 50, 0, 0, 0, 1, RobotState.ACTIVE, "B");

        // Round 0 — A wins
        player.processTurn(0, turn(100, rAalive, rBdead), 800, 600, 0.1, 10);
        player.finalizeRound();
        // Round 1 — B wins
        player.processTurn(1, turn(0, rAdead, rBalive), 800, 600, 0.1, 10);
        player.finalizeRound();
        // Round 2 — A wins again
        player.processTurn(2, turn(0, rAalive, rBdead), 800, 600, 0.1, 10);
        player.finalizeRound();

        assertEquals(2, wbA.getRoundsWon());
        assertEquals(1, wbA.getRoundsLost());
        assertEquals(1, wbB.getRoundsWon());
        assertEquals(2, wbB.getRoundsLost());
    }
}

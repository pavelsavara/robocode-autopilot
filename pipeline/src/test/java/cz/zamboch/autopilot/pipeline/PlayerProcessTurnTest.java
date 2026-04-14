package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Whiteboard;
import robocode.Rules;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static cz.zamboch.autopilot.pipeline.TestSnapshots.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Player.processTurn() — no file I/O, no Loader, pure logic.
 */
class PlayerProcessTurnTest {

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
    void firstTurnReturnsNewRound() {
        ITurnSnapshot turn = turn(0,
                robot(100, 200, 0),
                robot(500, 400, 1));

        boolean result = player.processTurn(0, turn, 800, 600, 0.1, 10);

        assertTrue(result, "First turn should signal new round");
    }

    @Test
    void sameTurnDoesNotSignalNewRound() {
        ITurnSnapshot t0 = turn(0, robot(100, 200, 0), robot(500, 400, 1));
        ITurnSnapshot t1 = turn(1, robot(100, 200, 0), robot(500, 400, 1));

        player.processTurn(0, t0, 800, 600, 0.1, 10);
        boolean result = player.processTurn(0, t1, 800, 600, 0.1, 10);

        assertFalse(result, "Same round should not signal new round");
    }

    @Test
    void setsOwnStateForBothWhiteboards() {
        IRobotSnapshot rA = robot(100, 200, 0.5, 3.0, 95, 1.2, 0.3, 0.7, 0, RobotState.ACTIVE, "A");
        IRobotSnapshot rB = robot(500, 400, 1.0, -2.0, 80, 0.0, 1.5, 2.0, 1, RobotState.ACTIVE, "B");
        ITurnSnapshot turn = turn(5, rA, rB);

        player.processTurn(0, turn, 800, 600, 0.1, 10);

        assertEquals(100, wbA.getOurX(), 0.001);
        assertEquals(200, wbA.getOurY(), 0.001);
        assertEquals(0.5, wbA.getOurHeading(), 0.001);
        assertEquals(3.0, wbA.getOurVelocity(), 0.001);
        assertEquals(95, wbA.getOurEnergy(), 0.001);
        assertEquals(1.2, wbA.getOurGunHeat(), 0.001);

        assertEquals(500, wbB.getOurX(), 0.001);
        assertEquals(400, wbB.getOurY(), 0.001);
        assertEquals(-2.0, wbB.getOurVelocity(), 0.001);
    }

    @Test
    void firstTickAlwaysScans() {
        // On tick 0 (first tick), both robots scan each other regardless of radar
        IRobotSnapshot rA = robot(100, 200, 0, 0, 100, 0, 0, 0, 0, RobotState.ACTIVE, "A");
        IRobotSnapshot rB = robot(500, 400, 1.5, -3, 80, 0, 0, 0, 1, RobotState.ACTIVE, "B");
        ITurnSnapshot turn = turn(0, rA, rB);

        player.processTurn(0, turn, 800, 600, 0.1, 10);

        assertTrue(wbA.isScanAvailableThisTick(), "A should see B on first tick");
        assertTrue(wbB.isScanAvailableThisTick(), "B should see A on first tick");

        assertEquals(500, wbA.getOpponentX(), 0.001);
        assertEquals(400, wbA.getOpponentY(), 0.001);
        assertEquals(80, wbA.getOpponentEnergy(), 0.001);

        assertEquals(100, wbB.getOpponentX(), 0.001);
        assertEquals(200, wbB.getOpponentY(), 0.001);
    }

    @Test
    void deadRobotNotScanned() {
        IRobotSnapshot rA = robot(100, 200, 0, 0, 100, 0, 0, 0, 0, RobotState.ACTIVE, "A");
        IRobotSnapshot rB = robot(500, 400, 0, 0, 0, 0, 0, 0, 1, RobotState.DEAD, "B");
        ITurnSnapshot turn = turn(0, rA, rB);

        player.processTurn(0, turn, 800, 600, 0.1, 10);

        // A should NOT see dead B, but B should see A (they're alive)
        assertFalse(wbA.isScanAvailableThisTick(), "A should not scan dead B");
        assertTrue(wbB.isScanAvailableThisTick(), "B should still scan A on first tick");
    }

    @Test
    void insufficientRobotsReturnsFalse() {
        ITurnSnapshot turn = turn(0, new IRobotSnapshot[]{robot(100, 200, 0)});
        boolean result = player.processTurn(0, turn, 800, 600, 0.1, 10);
        assertFalse(result, "Should return false with < 2 robots");
    }

    @Test
    void tickIsSet() {
        ITurnSnapshot turn = turn(42, robot(100, 200, 0), robot(500, 400, 1));
        player.processTurn(0, turn, 800, 600, 0.1, 10);

        assertEquals(42, wbA.getTick());
        assertEquals(42, wbB.getTick());
    }

    @Test
    void roundStartInitializesWhiteboards() {
        ITurnSnapshot turn = turn(0, robot(100, 200, 0), robot(500, 400, 1));
        player.processTurn(3, turn, 800, 600, 0.15, 10);

        assertEquals(3, wbA.getRound());
        assertEquals(800, wbA.getBattlefieldWidth());
        assertEquals(600, wbA.getBattlefieldHeight());
        assertEquals(0.15, wbA.getGunCoolingRate(), 0.001);
        assertEquals(10, wbA.getNumRounds());
    }

    @Test
    void newBulletIncrementsOurShotsFired() {
        IRobotSnapshot rA = robot(100, 200, 0, 0, 97, 0, 0, 0, 0, RobotState.ACTIVE, "A");
        IRobotSnapshot rB = robot(500, 400, 0, 0, 100, 0, 0, 0, 1, RobotState.ACTIVE, "B");

        IBulletSnapshot b = bullet(1, 0, -1, 3.0, BulletState.MOVING);
        ITurnSnapshot turn = turn(0, new IRobotSnapshot[]{rA, rB}, b);

        player.processTurn(0, turn, 800, 600, 0.1, 10);

        assertEquals(1, wbA.getOurShotsFired(), "A's shot count");
        assertEquals(0, wbB.getOurShotsFired(), "B should not have fired");
    }

    @Test
    void bulletHitVictimUpdatesDamage() {
        IRobotSnapshot rA = robot(100, 200, 0, 0, 100, 0, 0, 0, 0, RobotState.ACTIVE, "A");
        IRobotSnapshot rB = robot(500, 400, 0, 0, 80, 0, 0, 0, 1, RobotState.ACTIVE, "B");

        // Tick 0: bullet appears (moving)
        IBulletSnapshot bMoving = bullet(1, 0, -1, 2.0, BulletState.MOVING);
        ITurnSnapshot t0 = turn(0, new IRobotSnapshot[]{rA, rB}, bMoving);
        player.processTurn(0, t0, 800, 600, 0.1, 10);
        wbA.advanceTick();
        wbB.advanceTick();

        // Tick 1: bullet hits victim
        IBulletSnapshot bHit = bullet(1, 0, 1, 2.0, BulletState.HIT_VICTIM);
        ITurnSnapshot t1 = turn(1, new IRobotSnapshot[]{rA, rB}, bHit);
        player.processTurn(0, t1, 800, 600, 0.1, 10);

        double expectedDamage = Rules.getBulletDamage(2.0);
        assertEquals(expectedDamage, wbA.getDamageDealt(), 0.001, "A dealt damage");
        assertEquals(expectedDamage, wbB.getDamageReceived(), 0.001, "B received damage");
        assertTrue(wbA.isWeHitOpponentThisTick(), "A hit opponent flag");
        assertEquals(1, wbA.getOurBulletHitCount(), "A's hit count");
        assertEquals(1, wbB.getOpponentBulletHitCount(), "B's opponent hit count");
    }

    @Test
    void inactiveBulletsIgnored() {
        IRobotSnapshot rA = robot(100, 200, 0);
        IRobotSnapshot rB = robot(500, 400, 1);

        IBulletSnapshot inactive = bullet(99, 0, -1, 1.0, BulletState.INACTIVE);
        IBulletSnapshot exploded = bullet(98, 1, -1, 1.0, BulletState.EXPLODED);
        ITurnSnapshot turn = turn(0, new IRobotSnapshot[]{rA, rB}, inactive, exploded);

        player.processTurn(0, turn, 800, 600, 0.1, 10);

        assertEquals(0, wbA.getOurShotsFired());
        assertEquals(0, wbB.getOurShotsFired());
    }

    @Test
    void nullBulletsHandledGracefully() {
        // StubTurn returns empty array, but test behavior with a turn that has null bullets
        // The processBulletEvents method guards against null
        IRobotSnapshot rA = robot(100, 200, 0);
        IRobotSnapshot rB = robot(500, 400, 1);
        ITurnSnapshot turn = turn(0, rA, rB);

        // Should not throw
        player.processTurn(0, turn, 800, 600, 0.1, 10);
    }

    @Test
    void roundTransitionResetsState() {
        IRobotSnapshot rA = robot(100, 200, 0);
        IRobotSnapshot rB = robot(500, 400, 1);

        // Round 0
        player.processTurn(0, turn(0, rA, rB), 800, 600, 0.1, 10);
        assertEquals(0, wbA.getRound());

        // Round 1
        boolean newRound = player.processTurn(1, turn(0, rA, rB), 800, 600, 0.1, 10);
        assertTrue(newRound);
        assertEquals(1, wbA.getRound());
    }

    @Test
    void radarSweepIntersectsStaticCheck() {
        // Direct test of the static helper
        // Straight north radar (heading 0) sweeping to heading PI/4 (45 deg CW)
        // should detect opponent at NE
        assertTrue(Player.radarSweepIntersects(
                400, 300, 0, Math.PI / 4,
                500, 400));

        // NaN prevRadar → no scan
        assertFalse(Player.radarSweepIntersects(
                400, 300, Double.NaN, Math.PI / 4,
                500, 400));

        // No sweep (same heading)
        assertFalse(Player.radarSweepIntersects(
                400, 300, 1.0, 1.0,
                500, 400));
    }

    @Test
    void opponentScanValuesOnFirstTick() {
        IRobotSnapshot rA = robot(100, 200, 0, 2.5, 90, 0.5, 0, 0.1, 0, RobotState.ACTIVE, "A");
        IRobotSnapshot rB = robot(500, 400, 1.2, -4.0, 75, 0.0, 0, 0.2, 1, RobotState.ACTIVE, "B");
        ITurnSnapshot turn = turn(0, rA, rB);

        player.processTurn(0, turn, 800, 600, 0.1, 10);

        // A's opponent scan = B's state
        assertEquals(500, wbA.getOpponentX(), 0.001);
        assertEquals(400, wbA.getOpponentY(), 0.001);
        assertEquals(1.2, wbA.getOpponentHeading(), 0.001);
        assertEquals(-4.0, wbA.getOpponentVelocity(), 0.001);
        assertEquals(75, wbA.getOpponentEnergy(), 0.001);

        // B's opponent scan = A's state
        assertEquals(100, wbB.getOpponentX(), 0.001);
        assertEquals(200, wbB.getOpponentY(), 0.001);
        assertEquals(0, wbB.getOpponentHeading(), 0.001);
        assertEquals(2.5, wbB.getOpponentVelocity(), 0.001);
        assertEquals(90, wbB.getOpponentEnergy(), 0.001);
    }

    @Test
    void bulletFromBHitsA() {
        IRobotSnapshot rA = robot(100, 200, 0, 0, 100, 0, 0, 0, 0, RobotState.ACTIVE, "A");
        IRobotSnapshot rB = robot(500, 400, 0, 0, 97, 0, 0, 0, 1, RobotState.ACTIVE, "B");

        // Tick 0: B fires
        IBulletSnapshot bMoving = bullet(2, 1, -1, 3.0, BulletState.MOVING);
        player.processTurn(0, turn(0, new IRobotSnapshot[]{rA, rB}, bMoving), 800, 600, 0.1, 10);
        wbA.advanceTick();
        wbB.advanceTick();

        // Tick 1: B's bullet hits A
        IBulletSnapshot bHit = bullet(2, 1, 0, 3.0, BulletState.HIT_VICTIM);
        player.processTurn(0, turn(1, new IRobotSnapshot[]{rA, rB}, bHit), 800, 600, 0.1, 10);

        double expectedDamage = Rules.getBulletDamage(3.0);
        assertEquals(expectedDamage, wbB.getDamageDealt(), 0.001, "B dealt damage");
        assertEquals(expectedDamage, wbA.getDamageReceived(), 0.001, "A received damage");
        assertTrue(wbB.isWeHitOpponentThisTick(), "B hit opponent flag");
    }
}

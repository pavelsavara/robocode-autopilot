package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import net.sf.robocode.security.HiddenAccess;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
final class PipelineOrchestratorTest {

    private PipelineOrchestrator orchestrator;

    @BeforeAll
    static void initRobocodeFramework() {
        HiddenAccess.init();
    }

    @BeforeEach
    void setUp() {
        orchestrator = new PipelineOrchestrator(800, 600, 0.1);
    }

    @Test
    void singleTickFeedsBothObservers() {
        ITurnSnapshot snap = TestSnapshots.turn(1,
                TestSnapshots.robot(400, 300, 0, 3.0, 100, 0, 0, 0, 0, RobotState.ACTIVE, "alpha.Bot"),
                TestSnapshots.robot(500, 400, 1.0, 2.0, 90, 0, 1.0, 1.0, 1, RobotState.ACTIVE, "beta.Bot"));

        orchestrator.processTurn(snap);

        ObserverContext[] obs = orchestrator.observers();
        Whiteboard wb0 = obs[0].wb();
        Whiteboard wb1 = obs[1].wb();

        // Both should have processed tick 1
        assertEquals(1.0, wb0.getFeature(Feature.TICK), 1e-9);
        assertEquals(1.0, wb1.getFeature(Feature.TICK), 1e-9);
    }

    @Test
    void perspectiveCorrectness() {
        ITurnSnapshot snap = TestSnapshots.turn(1,
                TestSnapshots.robot(100, 200, 0.5, 4.0, 95, 0, 0.5, 0.5, 0, RobotState.ACTIVE, "alpha.Bot"),
                TestSnapshots.robot(600, 500, 1.2, 2.0, 80, 0, 1.2, 1.2, 1, RobotState.ACTIVE, "beta.Bot"));

        orchestrator.processTurn(snap);

        ObserverContext[] obs = orchestrator.observers();
        Whiteboard wb0 = obs[0].wb();
        Whiteboard wb1 = obs[1].wb();

        // Observer 0 sees itself at (100, 200)
        assertEquals(100.0, wb0.getFeature(Feature.OUR_X), 1e-9);
        assertEquals(200.0, wb0.getFeature(Feature.OUR_Y), 1e-9);
        assertEquals(95.0, wb0.getFeature(Feature.OUR_ENERGY), 1e-9);

        // Observer 1 sees itself at (600, 500)
        assertEquals(600.0, wb1.getFeature(Feature.OUR_X), 1e-9);
        assertEquals(500.0, wb1.getFeature(Feature.OUR_Y), 1e-9);
        assertEquals(80.0, wb1.getFeature(Feature.OUR_ENERGY), 1e-9);
    }

    @Test
    void firstTickHandledGracefully() {
        // First call with no prevSnapshot should not throw
        ITurnSnapshot snap = TestSnapshots.turn(0,
                TestSnapshots.robot(400, 300, 0, "alpha.Bot"),
                TestSnapshots.robot(500, 400, 1, "beta.Bot"));

        assertDoesNotThrow(() -> orchestrator.processTurn(snap));

        ObserverContext[] obs = orchestrator.observers();
        assertEquals(0.0, obs[0].wb().getFeature(Feature.TICK), 1e-9);
    }

    @Test
    void deadRobotSkipped() {
        // Tick 1: both alive
        ITurnSnapshot snap1 = TestSnapshots.turn(1,
                TestSnapshots.robot(400, 300, 0, 0, 100, 0, 0, 0, 0, RobotState.ACTIVE, "alpha.Bot"),
                TestSnapshots.robot(500, 400, 0, 0, 90, 0, 0, 0, 1, RobotState.ACTIVE, "beta.Bot"));
        orchestrator.processTurn(snap1);

        // Tick 2: robot 1 (beta) is dead
        ITurnSnapshot snap2 = TestSnapshots.turn(2,
                TestSnapshots.robot(400, 300, 0, 0, 100, 0, 0, 0, 0, RobotState.ACTIVE, "alpha.Bot"),
                TestSnapshots.robot(500, 400, 0, 0, 0, 0, 0, 0, 1, RobotState.DEAD, "beta.Bot"));
        orchestrator.processTurn(snap2);

        ObserverContext[] obs = orchestrator.observers();
        // Observer 0 (alpha) should be alive and see tick 2
        assertFalse(obs[0].isDead());
        assertEquals(2.0, obs[0].wb().getFeature(Feature.TICK), 1e-9);

        // Observer 1 (beta) should be dead
        assertTrue(obs[1].isDead());
    }

    @Test
    void multipleTicksAccumulate() {
        ITurnSnapshot snap1 = TestSnapshots.turn(1,
                TestSnapshots.robot(100, 100, 0, 5.0, 100, 0, 0, 0, 0, RobotState.ACTIVE, "alpha.Bot"),
                TestSnapshots.robot(600, 400, 0, 3.0, 90, 0, 0, 0, 1, RobotState.ACTIVE, "beta.Bot"));
        orchestrator.processTurn(snap1);

        ITurnSnapshot snap2 = TestSnapshots.turn(2,
                TestSnapshots.robot(105, 100, 0, 5.0, 100, 0, 0, 0, 0, RobotState.ACTIVE, "alpha.Bot"),
                TestSnapshots.robot(603, 400, 0, 3.0, 90, 0, 0, 0, 1, RobotState.ACTIVE, "beta.Bot"));
        orchestrator.processTurn(snap2);

        ObserverContext[] obs = orchestrator.observers();
        assertEquals(2.0, obs[0].wb().getFeature(Feature.TICK), 1e-9);
        assertEquals(2.0, obs[1].wb().getFeature(Feature.TICK), 1e-9);
    }

    @Test
    void roundTransitionResetsObservers() {
        // Round 0, tick 1
        ITurnSnapshot r0t1 = TestSnapshots.turn(0, 1,
                TestSnapshots.robot(100, 100, 0, 0.5, 100, 0, 0, 0, 0, RobotState.ACTIVE, "alpha.Bot"),
                TestSnapshots.robot(600, 400, 0, 3.0, 90, 0, 0, 0, 1, RobotState.ACTIVE, "beta.Bot"));
        orchestrator.processTurn(r0t1);

        // Verify some state was set in round 0
        ObserverContext[] obs = orchestrator.observers();
        assertNotEquals(Double.NaN, obs[0].wb().getFeature(Feature.TICK));

        // Round 1, tick 1 — should trigger resetRound()
        ITurnSnapshot r1t1 = TestSnapshots.turn(1, 1,
                TestSnapshots.robot(200, 200, 0, 3.0, 100, 0, 0, 0, 0, RobotState.ACTIVE, "alpha.Bot"),
                TestSnapshots.robot(500, 300, 0, 3.0, 90, 0, 0, 0, 1, RobotState.ACTIVE, "beta.Bot"));
        orchestrator.processTurn(r1t1);

        // After round transition, gun heat should be fresh (3.0 initial - 0.1 cooling = 2.9 after first tick)
        assertEquals(2.9, obs[0].peer().getGunHeat(), 1e-9);
    }
}

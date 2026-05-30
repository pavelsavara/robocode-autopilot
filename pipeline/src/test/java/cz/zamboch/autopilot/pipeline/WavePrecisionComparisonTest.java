package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Whiteboard;
import net.sf.robocode.security.HiddenAccess;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test running both robot-side (ObserverContext) and god-view
 * (GodViewWaveResolver), comparing precision via WavePrecisionComparator.
 */
@Tag("integration")
final class WavePrecisionComparisonTest {

    private ObserverContext[] observers;
    private GodViewWaveResolver godView;
    private WavePrecisionComparator comparator;

    @BeforeAll
    static void initRobocodeFramework() {
        HiddenAccess.init();
    }

    @BeforeEach
    void setUp() {
        observers = ObserverContext.createPair(800, 600, 0.1);
        godView = new GodViewWaveResolver();
        comparator = new WavePrecisionComparator();
    }

    /**
     * Mirror the orchestrator's Phase 1.5: seed each god-view whiteboard from the
     * freshly-computed robot-side whiteboard so TICK/scan state is present when the
     * god-view wave resolver runs. Without this the god-view TICK stays unset and
     * waves never advance.
     */
    private void syncGodView(ITurnSnapshot curr) {
        for (ObserverContext ctx : observers) {
            ctx.seedGodView(curr);
        }
    }

    @Test
    void godViewDetectsFire() {
        // Tick 0: both robots alive, no bullets
        IRobotSnapshot r0 = TestSnapshots.robot(400, 200, 0, 0, 100, 3.0, 0, 0, 0, RobotState.ACTIVE, "alpha.Bot");
        IRobotSnapshot r1 = TestSnapshots.robot(400, 400, 0, 0, 100, 3.0, 0, 0, 1, RobotState.ACTIVE, "beta.Bot");
        ITurnSnapshot tick0 = TestSnapshots.turn(0, r0, r1);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick0);
        }

        // Tick 1: bullet appears (FIRED state) from robot 0
        IBulletSnapshot bullet = TestSnapshots.bullet(1, 0, -1, 3.0, BulletState.FIRED);
        IRobotSnapshot r0_t1 = TestSnapshots.robot(400, 200, 0, 0, 97, 1.4, 0, 0, 0, RobotState.ACTIVE, "alpha.Bot");
        IRobotSnapshot r1_t1 = TestSnapshots.robot(400, 400, 0, 0, 100, 2.9, 0, 0, 1, RobotState.ACTIVE, "beta.Bot");
        ITurnSnapshot tick1 = TestSnapshots.turn(1, r0_t1, r1_t1, bullet);

        for (ObserverContext ctx : observers) {
            ctx.processTick(tick1);
        }

        IRobotSnapshot[] robots = tick1.getRobots();
        boolean[] resolved = godView.processTick(observers, robots, tick1);

        // Should not resolve on fire tick — just detects the bullet
        assertFalse(resolved[0], "Wave should not resolve on fire tick");
        assertFalse(resolved[1], "No wave from robot 1");
    }

    @Test
    void godViewResolvesWaveAndComparatorRecords() {
        // Setup: robot 0 fires at robot 1
        IRobotSnapshot r0 = TestSnapshots.robot(400, 200, 0, 0, 100, 3.0, 0, 0, 0, RobotState.ACTIVE, "alpha.Bot");
        IRobotSnapshot r1 = TestSnapshots.robot(400, 400, 0, 0, 100, 3.0, 0, 0, 1, RobotState.ACTIVE, "beta.Bot");
        ITurnSnapshot tick0 = TestSnapshots.turn(0, r0, r1);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick0);
        }
        syncGodView(tick0);
        godView.processTick(observers, tick0.getRobots(), tick0);

        // Tick 1: bullet fired
        IBulletSnapshot bullet = TestSnapshots.bullet(1, 0, -1, 3.0, BulletState.FIRED);
        IRobotSnapshot r0_t1 = TestSnapshots.robot(400, 200, 0, 0, 97, 1.4, 0, 0, 0, RobotState.ACTIVE, "alpha.Bot");
        IRobotSnapshot r1_t1 = TestSnapshots.robot(400, 400, 0, 0, 100, 2.9, 0, 0, 1, RobotState.ACTIVE, "beta.Bot");
        ITurnSnapshot tick1 = TestSnapshots.turn(1, r0_t1, r1_t1, bullet);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick1);
        }
        syncGodView(tick1);
        godView.processTick(observers, tick1.getRobots(), tick1);
        comparator.recordGodViewFire(0);

        // Advance bullet until HIT or past target
        boolean hitRecorded = false;
        for (int t = 2; t < 30; t++) {
            // bullet speed = 20 - 3*3 = 11 px/tick. Distance 200px → resolves at tick 20
            // distanceTravelled = (currentTick - fireTick) * speed = (20-1)*11 = 209 >= 200
            BulletState bState = (t >= 20) ? BulletState.HIT_VICTIM : BulletState.MOVING;
            int victimIdx = (bState == BulletState.HIT_VICTIM) ? 1 : -1;
            IBulletSnapshot b = TestSnapshots.bullet(1, 0, victimIdx, 3.0, bState);

            IRobotSnapshot r0_n = TestSnapshots.robot(400, 200, 0, 0, 97, Math.max(0, 1.4 - t * 0.1), 0, 0, 0,
                    RobotState.ACTIVE, "alpha.Bot");
            double r1Energy = (bState == BulletState.HIT_VICTIM) ? 100 - 4 * 3.0 + 2 : 100;
            IRobotSnapshot r1_n = TestSnapshots.robot(400, 400, 0, 0, r1Energy, Math.max(0, 2.9 - t * 0.1), 0, 0, 1,
                    RobotState.ACTIVE, "beta.Bot");
            ITurnSnapshot tickN = TestSnapshots.turn(t, r0_n, r1_n, b);

            // Robot-side
            for (ObserverContext ctx : observers) {
                ctx.processTick(tickN);
            }

            // Capture robot-side break BEFORE god-view
            Whiteboard wb0 = observers[0].wb();
            double robotGf = comparator.captureRobotSideBreak(0, wb0);

            // God-view
            syncGodView(tickN);
            boolean[] resolved = godView.processTick(observers, tickN.getRobots(), tickN);

            // Compare
            comparator.compareTick(0, wb0, robotGf, resolved[0]);

            if (resolved[0]) {
                hitRecorded = true;
                break;
            }

            if (bState == BulletState.HIT_VICTIM) {
                break; // bullet hit but maybe wave already resolved
            }
        }

        assertTrue(hitRecorded, "God-view should have resolved the wave");
        assertEquals(1, comparator.getGodViewFires(0));
    }

    @Test
    void comparatorResetClearsAllCounters() {
        comparator.recordGodViewFire(0);
        comparator.recordGodViewFire(0);
        comparator.recordRobotSideFire(0);
        comparator.recordGfComparison(0, 0.5, 0.3);

        comparator.resetRound();

        assertEquals(0, comparator.getGodViewFires(0));
        assertEquals(0, comparator.getRobotSideFires(0));
        assertEquals(0, comparator.getGfComparisonCount(0));
        assertTrue(Double.isNaN(comparator.getFireDetectionRate(0)));
        assertTrue(Double.isNaN(comparator.getGfMeanAbsoluteError(0)));
    }

    @Test
    void fireDetectionRateComputed() {
        // Simulate: god-view saw 4 fires, robot-side detected 3
        for (int i = 0; i < 4; i++) {
            comparator.recordGodViewFire(0);
        }
        for (int i = 0; i < 3; i++) {
            comparator.recordRobotSideFire(0);
        }

        assertEquals(0.75, comparator.getFireDetectionRate(0), 0.01);
    }

    @Test
    void gfMaeComputed() {
        // Record some GF comparisons with known errors
        comparator.recordGfComparison(0, 0.5, 0.3); // error = 0.2
        comparator.recordGfComparison(0, -0.2, 0.0); // error = 0.2
        comparator.recordGfComparison(0, 0.8, 0.6); // error = 0.2

        assertEquals(0.2, comparator.getGfMeanAbsoluteError(0), 0.001);
        assertEquals(3, comparator.getGfComparisonCount(0));
    }
}

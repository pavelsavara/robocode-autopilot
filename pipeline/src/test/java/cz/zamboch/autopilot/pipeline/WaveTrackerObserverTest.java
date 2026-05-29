package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
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
 * Integration test: verifies OUR_FIRE/OUR_BREAK features that are set
 * by GodViewWaveResolver using exact bullet snapshot positions.
 */
@Tag("integration")
final class WaveTrackerObserverTest {

    private ObserverContext[] observers;
    private GodViewWaveResolver godView;

    @BeforeAll
    static void initRobocodeFramework() {
        HiddenAccess.init();
    }

    @BeforeEach
    void setUp() {
        observers = ObserverContext.createPair(800, 600, 0.1);
        godView = new GodViewWaveResolver();
    }

    @Test
    void firePowerIsSetByGodView() {
        // Robot 0 at (400,300) fires 2.0 power at robot 1 at (400,500)
        IRobotSnapshot r0_t0 = TestSnapshots.robot(400, 300, 0, 0, 100, 3.0, 0, 0, 0, RobotState.ACTIVE, "alpha.Bot");
        IRobotSnapshot r1_t0 = TestSnapshots.robot(400, 500, 0, 0, 100, 3.0, 0, 0, 1, RobotState.ACTIVE, "beta.Bot");

        ITurnSnapshot tick0 = TestSnapshots.turn(0, r0_t0, r1_t0);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick0);
        }
        godView.processTick(observers, tick0.getRobots(), tick0);

        // Tick 1: robot 0 fires (energy drops, bullet appears in snapshot)
        IRobotSnapshot r0_t1 = TestSnapshots.robot(400, 300, 0, 0, 98, 1.2, 0, 0, 0, RobotState.ACTIVE, "alpha.Bot");
        IRobotSnapshot r1_t1 = TestSnapshots.robot(400, 500, 0, 0, 100, 2.9, 0, 0, 1, RobotState.ACTIVE, "beta.Bot");
        IBulletSnapshot bullet = TestSnapshots.bullet(1, 0, -1, 2.0, BulletState.FIRED);

        ITurnSnapshot tick1 = TestSnapshots.turn(1, r0_t1, r1_t1, bullet);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick1);
        }
        godView.processTick(observers, tick1.getRobots(), tick1);

        // Robot 0's whiteboard should have OUR_FIRE_POWER = 2.0 (set by god-view)
        Whiteboard wb0 = observers[0].wb();
        double ourFirePower = wb0.getFeature(Feature.OUR_FIRE_POWER);
        assertEquals(2.0, ourFirePower, 0.1, "OUR_FIRE_POWER from god-view bullet detection");
    }

    @Test
    void ourFireFeaturesIncludeMea() {
        // Setup with a scan so the robot-side knows opponent position
        IRobotSnapshot r0 = TestSnapshots.robot(200, 300, 0, 6.0, 100, 3.0, 0, 0, 0, RobotState.ACTIVE, "alpha.Bot");
        IRobotSnapshot r1 = TestSnapshots.robot(200, 500, 0, 3.0, 100, 3.0, 0, 0, 1, RobotState.ACTIVE, "beta.Bot");

        ITurnSnapshot tick0 = TestSnapshots.turn(0, r0, r1);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick0);
        }
        godView.processTick(observers, tick0.getRobots(), tick0);

        // Fire at tick 1 (bullet in snapshot)
        IRobotSnapshot r0_t1 = TestSnapshots.robot(200, 300, 0, 6.0, 97, 1.4, 0, 0, 0, RobotState.ACTIVE, "alpha.Bot");
        IRobotSnapshot r1_t1 = TestSnapshots.robot(200, 500, 0, 3.0, 100, 2.9, 0, 0, 1, RobotState.ACTIVE, "beta.Bot");
        IBulletSnapshot bullet = TestSnapshots.bullet(1, 0, -1, 3.0, BulletState.FIRED);

        ITurnSnapshot tick1 = TestSnapshots.turn(1, r0_t1, r1_t1, bullet);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick1);
        }
        godView.processTick(observers, tick1.getRobots(), tick1);

        Whiteboard wb0 = observers[0].wb();
        double firePower = wb0.getFeature(Feature.OUR_FIRE_POWER);
        double fireMea = wb0.getFeature(Feature.OUR_FIRE_MEA);

        // God-view should have detected fire and set MEA
        assertFalse(Double.isNaN(firePower), "OUR_FIRE_POWER should be set by god-view");
        assertFalse(Double.isNaN(fireMea), "OUR_FIRE_MEA should be set when fire detected");
        assertTrue(fireMea > 0, "MEA should be positive");
    }

    @Test
    void waveBreaksWhenReachingOpponent() {
        // Place robots 200px apart. Fire power 3.0 → speed 11 → resolves tick 20
        IRobotSnapshot r0 = TestSnapshots.robot(400, 200, 0, 0, 100, 3.0, 0, 0, 0, RobotState.ACTIVE, "alpha.Bot");
        IRobotSnapshot r1 = TestSnapshots.robot(400, 400, 0, 0, 100, 3.0, 0, 0, 1, RobotState.ACTIVE, "beta.Bot");

        ITurnSnapshot tick0 = TestSnapshots.turn(0, r0, r1);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick0);
        }
        godView.processTick(observers, tick0.getRobots(), tick0);

        // Fire tick: bullet appears
        IRobotSnapshot r0_t1 = TestSnapshots.robot(400, 200, 0, 0, 97, 1.4, 0, 0, 0, RobotState.ACTIVE, "alpha.Bot");
        IRobotSnapshot r1_t1 = TestSnapshots.robot(400, 400, 0, 0, 100, 2.9, 0, 0, 1, RobotState.ACTIVE, "beta.Bot");
        IBulletSnapshot bullet = TestSnapshots.bullet(1, 0, -1, 3.0, BulletState.FIRED);
        ITurnSnapshot tick1 = TestSnapshots.turn(1, r0_t1, r1_t1, bullet);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick1);
        }
        godView.processTick(observers, tick1.getRobots(), tick1);

        // Advance ticks (opponent stays still)
        boolean waveResolved = false;
        for (int t = 2; t < 30; t++) {
            BulletState bState = (t >= 20) ? BulletState.HIT_VICTIM : BulletState.MOVING;
            int victimIdx = (bState == BulletState.HIT_VICTIM) ? 1 : -1;
            IBulletSnapshot b = TestSnapshots.bullet(1, 0, victimIdx, 3.0, bState);

            IRobotSnapshot r0_n = TestSnapshots.robot(400, 200, 0, 0, 97, Math.max(0, 1.4 - t * 0.1), 0, 0, 0, RobotState.ACTIVE, "alpha.Bot");
            IRobotSnapshot r1_n = TestSnapshots.robot(400, 400, 0, 0, 100, Math.max(0, 2.9 - t * 0.1), 0, 0, 1, RobotState.ACTIVE, "beta.Bot");
            ITurnSnapshot tickN = TestSnapshots.turn(t, r0_n, r1_n, b);
            for (ObserverContext ctx : observers) {
                ctx.processTick(tickN);
            }
            boolean[] resolved = godView.processTick(observers, tickN.getRobots(), tickN);

            if (resolved[0]) {
                waveResolved = true;
                Whiteboard wb0 = observers[0].wb();
                double breakGf = wb0.getFeature(Feature.OUR_BREAK_GF);
                // GF should be between -1 and 1
                assertTrue(breakGf >= -1.0 && breakGf <= 1.0,
                        "Break GF should be in [-1,1], got: " + breakGf);
                break;
            }

            if (bState == BulletState.HIT_VICTIM) {
                break;
            }
        }

        assertTrue(waveResolved, "God-view wave should have resolved within 30 ticks");
    }
}

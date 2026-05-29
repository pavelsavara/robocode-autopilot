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

@Tag("integration")
final class GodViewWaveResolverTest {

    private GodViewWaveResolver resolver;
    private ObserverContext[] observers;

    @BeforeAll
    static void initRobocodeFramework() {
        HiddenAccess.init();
    }

    @BeforeEach
    void setUp() {
        resolver = new GodViewWaveResolver();
        observers = ObserverContext.createPair(800, 600, 0.1);
    }

    @Test
    void detectsNewBulletAndSetsFireFeatures() {
        // Robot 0 at (400,300), Robot 1 at (500,400) - robot 0 fires
        IRobotSnapshot r0 = TestSnapshots.robot(400, 300, 0, 5.0, 95, 0, 0, 0, 0, RobotState.ACTIVE, "alpha.Bot");
        IRobotSnapshot r1 = TestSnapshots.robot(500, 400, 1.0, 3.0, 100, 0, 1.0, 1.0, 1, RobotState.ACTIVE, "beta.Bot");
        IBulletSnapshot bullet = TestSnapshots.bullet(42, 0, -1, 2.0, BulletState.FIRED);

        // Feed initial tick to set up observer state
        ITurnSnapshot tick0 = TestSnapshots.turn(0, r0, r1);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick0);
        }

        // Tick 1 with the bullet
        ITurnSnapshot tick1 = TestSnapshots.turn(1, r0, r1, bullet);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick1);
        }

        IRobotSnapshot[] robots = tick1.getRobots();
        boolean[] resolved = resolver.processTick(observers, robots, tick1);

        // Fire should be detected but wave not yet resolved (too close)
        Whiteboard wb0 = observers[0].wb();
        assertEquals(2.0, wb0.getFeature(Feature.OUR_FIRE_POWER), 1e-9);
        assertFalse(resolved[0], "Wave should not resolve on fire tick");
    }

    @Test
    void resolvesWaveWhenBulletReachesOpponent() {
        // Place robots far enough apart that the bullet needs many ticks to arrive
        IRobotSnapshot r0 = TestSnapshots.robot(100, 300, 0, 0, 95, 0, 0, 0, 0, RobotState.ACTIVE, "alpha.Bot");
        IRobotSnapshot r1 = TestSnapshots.robot(100, 500, 0, 0, 100, 0, 0, 0, 1, RobotState.ACTIVE, "beta.Bot");
        IBulletSnapshot bullet = TestSnapshots.bullet(99, 0, -1, 3.0, BulletState.FIRED);

        // Distance = 200px. bulletSpeed = 20 - 3*3 = 11 px/tick → ~18 ticks to resolve
        // Feed tick 0
        ITurnSnapshot tick0 = TestSnapshots.turn(0, r0, r1);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick0);
        }

        // Tick 1: bullet appears
        ITurnSnapshot tick1 = TestSnapshots.turn(1, r0, r1, bullet);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick1);
        }
        resolver.processTick(observers, tick1.getRobots(), tick1);

        // Fast-forward ticks until wave resolves
        boolean resolved = false;
        for (int t = 2; t < 30; t++) {
            // Bullet still active after fire tick
            IBulletSnapshot activeBullet = TestSnapshots.bullet(99, 0, -1, 3.0, BulletState.MOVING);
            ITurnSnapshot tickN = TestSnapshots.turn(t, r0, r1, activeBullet);
            for (ObserverContext ctx : observers) {
                ctx.processTick(tickN);
            }
            boolean[] result = resolver.processTick(observers, tickN.getRobots(), tickN);
            if (result[0]) {
                resolved = true;
                break;
            }
        }

        assertTrue(resolved, "Wave should have resolved within 30 ticks");

        Whiteboard wb0 = observers[0].wb();
        double breakGf = wb0.getFeature(Feature.OUR_BREAK_GF);
        assertFalse(Double.isNaN(breakGf), "Break GF should be set");
    }

    @Test
    void hitBulletSetsBreakHitFeature() {
        // Robot 0 fires, bullet hits robot 1
        IRobotSnapshot r0 = TestSnapshots.robot(100, 300, 0, 0, 95, 0, 0, 0, 0, RobotState.ACTIVE, "alpha.Bot");
        IRobotSnapshot r1 = TestSnapshots.robot(100, 500, 0, 0, 100, 0, 0, 0, 1, RobotState.ACTIVE, "beta.Bot");
        IBulletSnapshot firedBullet = TestSnapshots.bullet(77, 0, -1, 2.0, BulletState.FIRED);

        ITurnSnapshot tick0 = TestSnapshots.turn(0, r0, r1);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick0);
        }

        ITurnSnapshot tick1 = TestSnapshots.turn(1, r0, r1, firedBullet);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick1);
        }
        resolver.processTick(observers, tick1.getRobots(), tick1);

        // Advance until resolution. Mark bullet as HIT_VICTIM BEFORE resolution
        // so that hitBulletIds contains the ID when the wave resolves.
        // distance=200, power=2.0 → bulletSpeed=14, resolves at tick ~15.
        boolean resolved = false;
        for (int t = 2; t < 30; t++) {
            // Mark HIT_VICTIM a few ticks before expected resolution (engine reports hit)
            BulletState state = (t >= 12) ? BulletState.HIT_VICTIM : BulletState.MOVING;
            IBulletSnapshot b = TestSnapshots.bullet(77, 0, 1, 2.0, state);
            ITurnSnapshot tickN = TestSnapshots.turn(t, r0, r1, b);
            for (ObserverContext ctx : observers) {
                ctx.processTick(tickN);
            }
            boolean[] result = resolver.processTick(observers, tickN.getRobots(), tickN);
            if (result[0]) {
                resolved = true;
                break;
            }
        }

        assertTrue(resolved, "Wave should have resolved within 30 ticks");

        // OUR_BREAK_HIT must be 1.0 since the bullet was marked HIT_VICTIM before resolution
        Whiteboard wb0 = observers[0].wb();
        assertEquals(1.0, wb0.getFeature(Feature.OUR_BREAK_HIT), 1e-9);
    }

    @Test
    void resetRoundClearsState() {
        IRobotSnapshot r0 = TestSnapshots.robot(100, 300, 0, 0, 95, 0, 0, 0, 0, RobotState.ACTIVE, "alpha.Bot");
        IRobotSnapshot r1 = TestSnapshots.robot(500, 300, 0, 0, 100, 0, 0, 0, 1, RobotState.ACTIVE, "beta.Bot");
        IBulletSnapshot bullet = TestSnapshots.bullet(10, 0, -1, 1.0, BulletState.FIRED);

        ITurnSnapshot tick0 = TestSnapshots.turn(0, r0, r1);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick0);
        }
        ITurnSnapshot tick1 = TestSnapshots.turn(1, r0, r1, bullet);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick1);
        }
        resolver.processTick(observers, tick1.getRobots(), tick1);

        resolver.resetRound();

        // After reset, same bullet ID should be treated as new
        for (ObserverContext ctx : observers) {
            ctx.resetRound();
        }
        ITurnSnapshot tick2 = TestSnapshots.turn(0, r0, r1, bullet);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick2);
        }
        // Should not throw and should re-detect the bullet
        assertDoesNotThrow(() -> resolver.processTick(observers, tick2.getRobots(), tick2));
    }

    @Test
    void setsTheirWaveFeaturesOnPeer() {
        // When robot 0's wave resolves, THEIR_* features appear on robot 1's whiteboard
        IRobotSnapshot r0 = TestSnapshots.robot(100, 300, 0, 0, 95, 0, 0, 0, 0, RobotState.ACTIVE, "alpha.Bot");
        IRobotSnapshot r1 = TestSnapshots.robot(100, 500, 0, 0, 100, 0, 0, 0, 1, RobotState.ACTIVE, "beta.Bot");
        IBulletSnapshot bullet = TestSnapshots.bullet(55, 0, -1, 2.0, BulletState.FIRED);

        ITurnSnapshot tick0 = TestSnapshots.turn(0, r0, r1);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick0);
        }
        ITurnSnapshot tick1 = TestSnapshots.turn(1, r0, r1, bullet);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick1);
        }
        resolver.processTick(observers, tick1.getRobots(), tick1);

        // Advance until wave resolves
        for (int t = 2; t < 30; t++) {
            IBulletSnapshot movingBullet = TestSnapshots.bullet(55, 0, -1, 2.0, BulletState.MOVING);
            ITurnSnapshot tickN = TestSnapshots.turn(t, r0, r1, movingBullet);
            for (ObserverContext ctx : observers) {
                ctx.processTick(tickN);
            }
            boolean[] result = resolver.processTick(observers, tickN.getRobots(), tickN);
            if (result[0]) {
                // Check peer's (robot 1) whiteboard has THEIR_* features
                Whiteboard peerWb = observers[1].wb();
                assertEquals(2.0, peerWb.getFeature(Feature.THEIR_FIRE_POWER), 1e-9);
                double theirBreakGf = peerWb.getFeature(Feature.THEIR_BREAK_GF);
                assertFalse(Double.isNaN(theirBreakGf), "THEIR_BREAK_GF should be set on peer");
                return;
            }
        }
        fail("Wave should have resolved for THEIR_* feature test");
    }
}

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

import java.util.HashSet;
import java.util.Set;

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

        // Fire should be detected but wave not yet resolved (too close).
        // OUR_FIRE_* features are written to the god-view whiteboard.
        Whiteboard wb0 = observers[0].godWb();
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
        syncGodView(tick1);
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
            syncGodView(tickN);
            boolean[] result = resolver.processTick(observers, tickN.getRobots(), tickN);
            if (result[0]) {
                resolved = true;
                break;
            }
        }

        assertTrue(resolved, "Wave should have resolved within 30 ticks");

        Whiteboard wb0 = observers[0].godWb();
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
        syncGodView(tick1);
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
            syncGodView(tickN);
            boolean[] result = resolver.processTick(observers, tickN.getRobots(), tickN);
            if (result[0]) {
                resolved = true;
                break;
            }
        }

        assertTrue(resolved, "Wave should have resolved within 30 ticks");

        // OUR_BREAK_HIT must be 1.0 since the bullet was marked HIT_VICTIM before
        // resolution
        Whiteboard wb0 = observers[0].godWb();
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
        syncGodView(tick1);
        resolver.processTick(observers, tick1.getRobots(), tick1);

        // Advance until wave resolves
        for (int t = 2; t < 30; t++) {
            IBulletSnapshot movingBullet = TestSnapshots.bullet(55, 0, -1, 2.0, BulletState.MOVING);
            ITurnSnapshot tickN = TestSnapshots.turn(t, r0, r1, movingBullet);
            for (ObserverContext ctx : observers) {
                ctx.processTick(tickN);
            }
            syncGodView(tickN);
            boolean[] result = resolver.processTick(observers, tickN.getRobots(), tickN);
            if (result[0]) {
                // Check peer's (robot 1) god-view whiteboard has THEIR_* features
                Whiteboard peerWb = observers[1].godWb();
                assertEquals(2.0, peerWb.getFeature(Feature.THEIR_FIRE_POWER), 1e-9);
                double theirBreakGf = peerWb.getFeature(Feature.THEIR_BREAK_GF);
                assertFalse(Double.isNaN(theirBreakGf), "THEIR_BREAK_GF should be set on peer");
                return;
            }
        }
        fail("Wave should have resolved for THEIR_* feature test");
    }

    /**
     * Engine-grounded: replay a real recorded battle through the god-view resolver
     * and
     * assert that the number of fires it detects per side equals the number of
     * distinct
     * bullets the engine actually shows owned by that side. This anchors god-view
     * fire
     * detection against authoritative engine bullet state rather than a hand-built
     * stub.
     */
    @Test
    void recordedReplay_godViewFireDetectionMatchesEngineBullets() {
        RecordedBattle battle = RecordedBattle.load("/recorded/beepboop.fixture");
        int my = battle.autopilotIndex();
        int opp = 1 - my;
        int round = battle.spawn().getRound();
        for (ObserverContext ctx : observers) {
            ctx.resetRound(round);
            ctx.seedRoundStart(battle.spawn());
        }
        resolver.resetRound();

        // Engine truth: distinct bullet ids each side fired (first non-inactive
        // sighting).
        Set<Integer> engineFiresMy = new HashSet<>();
        Set<Integer> engineFiresOpp = new HashSet<>();
        int godFiresMy = 0;
        int godFiresOpp = 0;

        for (RecordedBattle.Tick tick : battle.ticks()) {
            for (IBulletSnapshot bs : tick.snapshot().getBullets()) {
                if (bs.getState() == BulletState.INACTIVE) {
                    continue;
                }
                if (bs.getOwnerIndex() == my) {
                    engineFiresMy.add(bs.getBulletId());
                } else {
                    engineFiresOpp.add(bs.getBulletId());
                }
            }
            for (ObserverContext ctx : observers) {
                ctx.processTick(tick.snapshot());
            }
            syncGodView(tick.snapshot());
            resolver.processTick(observers, tick.snapshot().getRobots(), tick.snapshot());
            if (resolver.firedThisTick(my)) {
                godFiresMy++;
            }
            if (resolver.firedThisTick(opp)) {
                godFiresOpp++;
            }
        }

        assertTrue(godFiresMy > 0, "expected god-view to detect at least one of our fires");
        assertEquals(engineFiresMy.size(), godFiresMy,
                "god-view should detect exactly the bullets engine shows we fired");
        assertEquals(engineFiresOpp.size(), godFiresOpp,
                "god-view should detect exactly the bullets engine shows opponent fired");
    }
}

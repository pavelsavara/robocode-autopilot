package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import net.sf.robocode.security.HiddenAccess;
import robocode.*;
import robocode.control.snapshot.RobotState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
final class ObserverContextTest {

    @BeforeAll
    static void initRobocodeFramework() {
        HiddenAccess.init();
    }

    @Test
    void createPairProducesLinkedContexts() {
        ObserverContext[] pair = ObserverContext.createPair(800, 600, 0.1);

        assertEquals(2, pair.length);
        assertEquals(0, pair[0].perspectiveIndex());
        assertEquals(1, pair[1].perspectiveIndex());
        assertSame(pair[1], pair[0].peerContext());
        assertSame(pair[0], pair[1].peerContext());
    }

    @Test
    void feedStatusSetsWhiteboardFeatures() {
        ObserverContext ctx = new ObserverContext(0, 800, 600, 0.1);

        // Feed a StatusEvent through TickEvents
        RobotStatus status = HiddenAccess.createStatus(
                95, 400, 300, 0.5, 0.3, 0.2, 4.0,
                0, 0, 0, 0,
                1.5, 1, 0, 0, 1, 5);
        TickEvents events = new TickEvents(List.of(new StatusEvent(status)));
        ctx.feedEvents(events);
        ctx.doTurn();

        Whiteboard wb = ctx.wb();
        assertEquals(400.0, wb.getFeature(Feature.OUR_X), 1e-9);
        assertEquals(300.0, wb.getFeature(Feature.OUR_Y), 1e-9);
        assertEquals(95.0, wb.getFeature(Feature.OUR_ENERGY), 1e-9);
        assertEquals(5.0, wb.getFeature(Feature.TICK), 1e-9);
    }

    @Test
    void feedEventsDeliversScannedRobot() {
        ObserverContext ctx = new ObserverContext(0, 800, 600, 0.1);

        // Status first (required for tick processing)
        RobotStatus status = HiddenAccess.createStatus(
                100, 400, 300, 0, 0, 0, 0,
                0, 0, 0, 0,
                0, 1, 0, 0, 1, 1);
        ScannedRobotEvent scan = new ScannedRobotEvent(
                "enemy.Bot", 80.0, Math.toRadians(30), 200.0, Math.toRadians(90), 5.0, false);

        TickEvents events = new TickEvents(List.of(new StatusEvent(status), scan));
        ctx.feedEvents(events);
        ctx.doTurn();

        Whiteboard wb = ctx.wb();
        assertEquals(200.0, wb.getFeature(Feature.DISTANCE), 1e-9);
        assertEquals(Math.toRadians(30), wb.getFeature(Feature.BEARING_RADIANS), 1e-9);
        assertEquals(80.0, wb.getFeature(Feature.OPPONENT_ENERGY), 1e-9);
    }

    @Test
    void doTurnComputesDerivedFeatures() {
        ObserverContext ctx = new ObserverContext(0, 800, 600, 0.1);

        RobotStatus status = HiddenAccess.createStatus(
                100, 400, 300, Math.toRadians(45), 0, 0, 6.0,
                0, 0, 0, 0,
                0, 1, 0, 0, 1, 1);
        TickEvents events = new TickEvents(List.of(new StatusEvent(status)));
        ctx.feedEvents(events);
        ctx.doTurn();

        Whiteboard wb = ctx.wb();
        // TICK should be set from status
        assertEquals(1.0, wb.getFeature(Feature.TICK), 1e-9);
        // Velocity set
        assertEquals(6.0, wb.getFeature(Feature.OUR_VELOCITY), 1e-9);
    }

    @Test
    void deadObserverSkipsFeedAndDoTurn() {
        ObserverContext ctx = new ObserverContext(0, 800, 600, 0.1);

        // Feed a death event
        RobotStatus status = HiddenAccess.createStatus(
                0, 400, 300, 0, 0, 0, 0,
                0, 0, 0, 0,
                0, 1, 0, 0, 1, 5);
        DeathEvent death = new DeathEvent();
        TickEvents events = new TickEvents(List.of(new StatusEvent(status), death));
        ctx.feedEvents(events);
        ctx.doTurn();

        assertTrue(ctx.isDead());

        // Subsequent feed should be no-op (no exception)
        RobotStatus status2 = HiddenAccess.createStatus(
                0, 400, 300, 0, 0, 0, 0,
                0, 0, 0, 0,
                0, 1, 0, 0, 1, 6);
        TickEvents events2 = new TickEvents(List.of(new StatusEvent(status2)));
        ctx.feedEvents(events2);
        ctx.doTurn();

        // TICK should NOT advance to 6 (stayed at 5 from before death)
        assertEquals(5.0, ctx.wb().getFeature(Feature.TICK), 1e-9);
    }

    @Test
    void resetRoundClearsDeadFlag() {
        ObserverContext ctx = new ObserverContext(0, 800, 600, 0.1);

        // Kill the observer
        RobotStatus status = HiddenAccess.createStatus(
                0, 400, 300, 0, 0, 0, 0,
                0, 0, 0, 0,
                0, 1, 0, 0, 1, 5);
        TickEvents events = new TickEvents(List.of(new StatusEvent(status), new DeathEvent()));
        ctx.feedEvents(events);
        assertTrue(ctx.isDead());

        // Reset round
        ctx.resetRound();
        assertFalse(ctx.isDead());
    }
}

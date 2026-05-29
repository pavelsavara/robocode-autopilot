package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.Autopilot;
import net.sf.robocode.security.HiddenAccess;
import robocode.RobotStatus;
import robocode.StatusEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
final class AutopilotObserverTest {

    private Autopilot autopilot;
    private ObserverRobotPeer peer;

    @BeforeAll
    static void initRobocodeFramework() {
        HiddenAccess.init();
    }

    @BeforeEach
    void setUp() {
        autopilot = new Autopilot();
        peer = new ObserverRobotPeer(800, 600, 0.1);
        autopilot.setPeer(peer);
        autopilot.initForObserver(null, 800, 600);
    }

    @Test
    void initSetsObserverBattlefieldDimensions() {
        Whiteboard wb = autopilot.getWhiteboard();
        assertEquals(800.0, wb.getFeature(Feature.BATTLEFIELD_WIDTH), 1e-9);
        assertEquals(600.0, wb.getFeature(Feature.BATTLEFIELD_HEIGHT), 1e-9);
    }

    @Test
    void doTurnProcessesWithoutThrowingInObserverMode() {
        // Feed a status event to set basic position
        RobotStatus status = HiddenAccess.createStatus(
                100, 400, 300,       // energy, x, y
                0, 0, 0,             // bodyHeading, gunHeading, radarHeading
                0,                   // velocity
                0, 0, 0, 0,          // remaining fields
                0,                   // gunHeat
                1, 0,                // others, sentries
                0, 1, 0              // roundNum, numRounds, turn
        );
        autopilot.onStatus(new StatusEvent(status));
        // Should not throw — observer mode skips setTurnRadar/setAhead/etc.
        assertDoesNotThrow(() -> autopilot.doTurn());
    }

    @Test
    void doTurnAdvancesTickInWhiteboard() {
        RobotStatus status = HiddenAccess.createStatus(
                100, 400, 300,
                0, 0, 0,
                0,
                0, 0, 0, 0,
                0,
                1, 0,
                0, 1, 5             // turn = 5
        );
        autopilot.onStatus(new StatusEvent(status));
        autopilot.doTurn();

        Whiteboard wb = autopilot.getWhiteboard();
        assertEquals(5.0, wb.getFeature(Feature.TICK), 1e-9);
    }
}

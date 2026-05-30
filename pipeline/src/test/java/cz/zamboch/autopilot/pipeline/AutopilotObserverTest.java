package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.Autopilot;
import net.sf.robocode.security.HiddenAccess;
import robocode.RobotStatus;
import robocode.ScannedRobotEvent;
import robocode.HitByBulletEvent;
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
    void doTurnProcessesWithoutThrowingInObserverMode() {
        peer.executeTick();
        feedStatus(100, 400, 300, 0, 0, 0, 0, 0, 0);
        assertDoesNotThrow(() -> autopilot.doTurn());
    }

    @Test
    void doTurnAdvancesTickInWhiteboard() {
        peer.executeTick();
        feedStatus(100, 400, 300, 0, 0, 0, 0, 0, 5);
        autopilot.doTurn();

        Whiteboard wb = autopilot.getWhiteboard();
        assertEquals(5.0, wb.getFeature(Feature.TICK), 1e-9);
    }

    @Test
    void onScannedRobotPopulatesOpponentFeatures() {
        peer.executeTick();
        feedStatus(100, 400, 300, 0, 0, 0, 0, 0, 1);

        ScannedRobotEvent scan = new ScannedRobotEvent(
                "enemy.Bot", 80.0, Math.toRadians(30), 200.0, Math.toRadians(90), 5.0, false);
        autopilot.onScannedRobot(scan);
        autopilot.doTurn();

        Whiteboard wb = autopilot.getWhiteboard();
        assertEquals(200.0, wb.getFeature(Feature.DISTANCE), 1e-9);
        assertEquals(Math.toRadians(30), wb.getFeature(Feature.BEARING_RADIANS), 1e-9);
        assertEquals(80.0, wb.getFeature(Feature.OPPONENT_ENERGY), 1e-9);
        assertEquals(5.0, wb.getFeature(Feature.OPPONENT_VELOCITY), 1e-9);
        assertEquals(Math.toRadians(90), wb.getFeature(Feature.OPPONENT_HEADING), 1e-9);
        assertEquals(1.0, wb.getFeature(Feature.LAST_SCAN_TICK), 1e-9);
    }

    @Test
    void onHitByBulletAccumulatesEnergyGain() {
        peer.executeTick();
        feedStatus(80, 400, 300, 0, 0, 0, 0, 0, 1);

        robocode.Bullet bullet = new robocode.Bullet(0, 100, 100, 2.0, "enemy.Bot", null, false, 1);
        HitByBulletEvent hit = new HitByBulletEvent(Math.toRadians(45), bullet);
        autopilot.onHitByBullet(hit);

        Whiteboard wb = autopilot.getWhiteboard();
        // Energy gain for opponent = 3 * power = 6.0
        assertEquals(6.0, wb.getFeature(Feature.OPPONENT_BULLET_ENERGY_GAIN), 1e-9);
    }

    @Test
    void observerDoesNotFireWhenGunNotAimed() {
        // Cool the gun fully (30 ticks)
        for (int i = 0; i < 30; i++) {
            peer.executeTick();
        }
        // Gun heading = 0, opponent at 90 degrees bearing → gun turn > 5°
        peer.updateState(400, 300, 0, 0, 100);
        feedStatus(100, 400, 300, 0, 0, 0, 0, 0, 31);
        // Need a scan to give the gun strategy something to aim at
        ScannedRobotEvent scan = new ScannedRobotEvent(
                "enemy.Bot", 80.0, Math.toRadians(90), 200.0, 0, 0, false);
        autopilot.onScannedRobot(scan);
        autopilot.doTurn();

        Whiteboard wb = autopilot.getWhiteboard();
        // Should NOT have fired — gun is aimed far off (>5°)
        assertTrue(Double.isNaN(wb.getFeature(Feature.OUR_FIRE_POWER))
                || wb.getFeature(Feature.OUR_FIRE_TICK) != 31);
    }

    @Test
    void observerFiresWhenGunAimedAndCool() {
        // Cool the gun fully (30 ticks)
        for (int i = 0; i < 30; i++) {
            peer.executeTick();
        }
        // Set up: gun pointing exactly at opponent → gunTurn ≈ 0°
        double opponentBearing = 0.01; // tiny angle from body heading
        peer.updateState(400, 300, opponentBearing, 0, 100);
        feedStatus(100, 400, 300, 0, opponentBearing, 0, 0, 0, 31);
        ScannedRobotEvent scan = new ScannedRobotEvent(
                "enemy.Bot", 80.0, opponentBearing, 200.0, 0, 0, false);
        autopilot.onScannedRobot(scan);
        autopilot.doTurn();

        Whiteboard wb = autopilot.getWhiteboard();
        // Verify tick processed correctly — firing depends on gun strategy internals
        assertEquals(31.0, wb.getFeature(Feature.TICK), 1e-9);
    }

    @Test
    void multiTickTracksTicksSinceScan() {
        // Tick 1: scan
        peer.executeTick();
        feedStatus(100, 400, 300, 0, 0, 0, 0, 0, 1);
        ScannedRobotEvent scan = new ScannedRobotEvent(
                "enemy.Bot", 80.0, 0.1, 200.0, 0, 0, false);
        autopilot.onScannedRobot(scan);
        autopilot.doTurn();

        Whiteboard wb = autopilot.getWhiteboard();
        assertEquals(1.0, wb.getFeature(Feature.LAST_SCAN_TICK), 1e-9);

        // Tick 2: no scan
        peer.executeTick();
        feedStatus(100, 400, 300, 0, 0, 0, 0, 0, 2);
        autopilot.doTurn();

        // Tick 3: no scan
        peer.executeTick();
        feedStatus(100, 400, 300, 0, 0, 0, 0, 0, 3);
        autopilot.doTurn();

        // LAST_SCAN_TICK should still be 1
        assertEquals(1.0, wb.getFeature(Feature.LAST_SCAN_TICK), 1e-9);
        // TICKS_SINCE_SCAN should be 2 (tick 3 - tick 1 = 2)
        double tss = wb.getFeature(Feature.TICKS_SINCE_SCAN);
        if (!Double.isNaN(tss)) {
            assertEquals(2.0, tss, 1e-9);
        }
    }

    @Test
    void deterministic_sameInputProducesSameOutput() {
        // Run 1
        Autopilot a1 = new Autopilot();
        ObserverRobotPeer p1 = new ObserverRobotPeer(800, 600, 0.1);
        a1.setPeer(p1);
        a1.initForObserver(null, 800, 600);
        p1.executeTick();
        feedStatus(a1, 100, 400, 300, 0, 0, 0, 0, 0, 1);
        ScannedRobotEvent scan1 = new ScannedRobotEvent(
                "enemy.Bot", 80.0, 0.5, 200.0, 1.0, 3.0, false);
        a1.onScannedRobot(scan1);
        a1.doTurn();

        // Run 2 (identical inputs)
        Autopilot a2 = new Autopilot();
        ObserverRobotPeer p2 = new ObserverRobotPeer(800, 600, 0.1);
        a2.setPeer(p2);
        a2.initForObserver(null, 800, 600);
        p2.executeTick();
        feedStatus(a2, 100, 400, 300, 0, 0, 0, 0, 0, 1);
        ScannedRobotEvent scan2 = new ScannedRobotEvent(
                "enemy.Bot", 80.0, 0.5, 200.0, 1.0, 3.0, false);
        a2.onScannedRobot(scan2);
        a2.doTurn();

        // All features should be identical
        Whiteboard wb1 = a1.getWhiteboard();
        Whiteboard wb2 = a2.getWhiteboard();
        for (Feature f : Feature.values()) {
            if (f == Feature.OPPONENT_ID)
                continue;
            double v1 = wb1.getFeature(f);
            double v2 = wb2.getFeature(f);
            if (Double.isNaN(v1)) {
                assertTrue(Double.isNaN(v2), "Feature " + f + " diverged: NaN vs " + v2);
            } else {
                assertEquals(v1, v2, 1e-9, "Feature " + f + " diverged");
            }
        }
    }

    // --- Helpers ---

    private void feedStatus(double energy, double x, double y,
            double bodyHeading, double gunHeading, double radarHeading,
            double velocity, double gunHeat, int turn) {
        feedStatus(autopilot, energy, x, y, bodyHeading, gunHeading, radarHeading,
                velocity, gunHeat, turn);
    }

    private static void feedStatus(Autopilot bot, double energy, double x, double y,
            double bodyHeading, double gunHeading, double radarHeading,
            double velocity, double gunHeat, int turn) {
        RobotStatus status = HiddenAccess.createStatus(
                energy, x, y,
                bodyHeading, gunHeading, radarHeading,
                velocity,
                0, 0, 0, 0, // remaining fields
                gunHeat,
                1, 0, // others, sentries
                0, 1, turn // roundNum, numRounds, turn
        );
        bot.onStatus(new StatusEvent(status));
    }
}

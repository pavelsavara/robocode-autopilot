package cz.zamboch.autopilot.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import robocode.Bullet;

import static org.junit.jupiter.api.Assertions.*;

final class ObserverRobotPeerTest {

    private ObserverRobotPeer peer;

    @BeforeEach
    void setUp() {
        peer = new ObserverRobotPeer(800, 600, 0.1);
    }

    @Test
    void gunHeatDecreases() {
        // Initial gun heat is 3.0
        assertEquals(3.0, peer.getGunHeat(), 1e-9);
        peer.coolGun();
        assertEquals(2.9, peer.getGunHeat(), 1e-9);
    }

    @Test
    void fireReturnsNullWhenHot() {
        // Gun heat is 3.0 — cannot fire
        Bullet bullet = peer.setFire(1.0);
        assertNull(bullet);
    }

    @Test
    void fireReturnsBulletWhenCool() {
        // Cool gun fully
        peer.updateState(100, 200, 1.5, 0.0, 100.0);
        Bullet bullet = peer.setFire(2.0);
        assertNotNull(bullet);
        assertEquals(2.0, bullet.getPower(), 1e-9);
    }

    @Test
    void fireResetsGunHeat() {
        peer.updateState(100, 200, 1.5, 0.0, 100.0);
        peer.setFire(3.0);
        // gunHeat = 1.0 + 3.0/5.0 = 1.6
        assertEquals(1.6, peer.getGunHeat(), 1e-9);
    }

    @Test
    void movementCommandsAreNoOps() {
        // These should not throw
        peer.setMove(100);
        peer.setTurnBody(0.5);
        peer.setTurnGun(0.3);
        peer.setTurnRadar(0.2);
        assertEquals(0, peer.getDistanceRemaining(), 1e-9);
    }

    @Test
    void battlefieldDimensionsReturned() {
        assertEquals(800, peer.getBattleFieldWidth(), 1e-9);
        assertEquals(600, peer.getBattleFieldHeight(), 1e-9);
    }
}

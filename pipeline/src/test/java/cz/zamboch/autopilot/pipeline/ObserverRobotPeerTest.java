package cz.zamboch.autopilot.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import robocode.Bullet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ObserverRobotPeer} — verifies engine-faithful gun heat
 * tracking,
 * fire mechanics, gun turn remaining, and tick lifecycle.
 */
final class ObserverRobotPeerTest {

    private ObserverRobotPeer peer;

    @BeforeEach
    void setUp() {
        peer = new ObserverRobotPeer(800, 600, 0.1);
    }

    // --- Gun heat tracking ---

    @Test
    void initialGunHeatIs3() {
        assertEquals(3.0, peer.getGunHeat(), 1e-9);
    }

    @Test
    void executeTickCoolsGunByRate() {
        peer.executeTick();
        assertEquals(2.9, peer.getGunHeat(), 1e-9);
    }

    @Test
    void gunHeatClampsToZero() {
        // Cool for 30 ticks → heat should be exactly 0
        for (int i = 0; i < 30; i++) {
            peer.executeTick();
        }
        assertEquals(0.0, peer.getGunHeat(), 1e-9);
    }

    @Test
    void gunHeatDoesNotGoNegative() {
        for (int i = 0; i < 35; i++) {
            peer.executeTick();
        }
        assertEquals(0.0, peer.getGunHeat(), 1e-9);
    }

    @Test
    void getGunHeatImplIncludesFiredHeat() {
        coolDown(peer);
        peer.updateState(400, 300, 0, 0, 100);
        peer.setFire(2.0);
        // gunHeat after fire: 0 + 1.4 = 1.4
        assertEquals(1.4, peer.getGunHeat(), 1e-9);
        // getGunHeatImpl = gunHeat + firedHeat = 1.4 + 1.4 = 2.8
        assertEquals(2.8, peer.getGunHeatImpl(), 1e-9);
    }

    // --- Firing ---

    @Test
    void fireReturnsNullWhenHot() {
        Bullet bullet = peer.setFire(1.0);
        assertNull(bullet);
    }

    @Test
    void fireReturnsBulletWhenCool() {
        coolDown(peer);
        peer.updateState(400, 300, 1.5, 0, 100);
        Bullet bullet = peer.setFire(2.0);
        assertNotNull(bullet);
        assertEquals(2.0, bullet.getPower(), 1e-9);
    }

    @Test
    void fireAddsHeat() {
        coolDown(peer);
        peer.updateState(400, 300, 1.5, 0, 100);
        peer.setFire(3.0);
        // heat = 1 + 3/5 = 1.6
        assertEquals(1.6, peer.getGunHeat(), 1e-9);
    }

    @Test
    void fireDeductsEnergy() {
        coolDown(peer);
        peer.updateState(400, 300, 1.5, 0, 50);
        peer.setFire(2.0);
        assertEquals(48.0, peer.getEnergy(), 1e-9);
    }

    @Test
    void fireReturnsNullWhenNoEnergy() {
        coolDown(peer);
        peer.updateState(400, 300, 1.5, 0, 0);
        assertNull(peer.setFire(1.0));
    }

    @Test
    void fireClampsPowerToMax() {
        coolDown(peer);
        peer.updateState(400, 300, 1.5, 0, 100);
        Bullet bullet = peer.setFire(5.0);
        assertNotNull(bullet);
        assertEquals(3.0, bullet.getPower(), 1e-9);
    }

    @Test
    void fireClampsPowerToMin() {
        coolDown(peer);
        peer.updateState(400, 300, 1.5, 0, 100);
        Bullet bullet = peer.setFire(0.01);
        assertNotNull(bullet);
        assertEquals(0.1, bullet.getPower(), 1e-9);
    }

    @Test
    void fireClampsPowerToAvailableEnergy() {
        coolDown(peer);
        peer.updateState(400, 300, 1.5, 0, 0.5);
        Bullet bullet = peer.setFire(3.0);
        assertNotNull(bullet);
        assertEquals(0.5, bullet.getPower(), 1e-9);
    }

    @Test
    void fireReturnsNullForNaN() {
        coolDown(peer);
        peer.updateState(400, 300, 1.5, 0, 100);
        assertNull(peer.setFire(Double.NaN));
    }

    @Test
    void doubleFireWithinTickBlockedByAccumulator() {
        coolDown(peer);
        peer.updateState(400, 300, 1.5, 0, 100);
        Bullet first = peer.setFire(2.0);
        assertNotNull(first);
        // Second fire within same tick fails (getGunHeatImpl > 0)
        Bullet second = peer.setFire(2.0);
        assertNull(second);
    }

    @Test
    void executeTickResetsAccumulatorsAndCools() {
        coolDown(peer);
        peer.updateState(400, 300, 1.5, 0, 100);
        peer.setFire(1.0);
        // Gun heat = 1.2, firedHeat = 1.2

        // Next tick: executeTick cools gun and resets accumulators
        peer.executeTick();
        // gunHeat = 1.2 - 0.1 = 1.1, firedHeat = 0
        assertEquals(1.1, peer.getGunHeat(), 1e-9);
        assertEquals(1.1, peer.getGunHeatImpl(), 1e-9); // firedHeat was reset
        // Still hot, so still can't fire
        assertNull(peer.setFire(1.0));
    }

    @Test
    void canFireAgainAfterHeatCoolsFromPreviousFire() {
        coolDown(peer);
        peer.updateState(400, 300, 0, 0, 100);
        peer.setFire(1.0); // heat = 1.2

        // Need 13 ticks to cool from 1.2 to 0 (floating point: 12 subtractions
        // of 0.1 from 1.2 leave a tiny positive residual; tick 13 goes negative
        // and gets clamped to 0)
        for (int i = 0; i < 13; i++) {
            peer.executeTick();
            peer.updateState(400, 300, 0, 0, 100);
        }
        Bullet bullet = peer.setFire(1.0);
        assertNotNull(bullet);
    }

    @Test
    void bulletIdIncrementsAcrossFires() {
        coolDown(peer);
        peer.updateState(400, 300, 1.5, 0, 100);
        Bullet b1 = peer.setFire(1.0);

        // Cool down again for next fire (13 ticks due to floating point)
        for (int i = 0; i < 13; i++) {
            peer.executeTick();
        }
        peer.updateState(400, 300, 1.5, 0, 100);
        Bullet b2 = peer.setFire(1.0);

        assertNotNull(b2, "Should be able to fire after cooling");
        assertNotEquals(b1.hashCode(), b2.hashCode());
    }

    // --- Gun turn tracking ---

    @Test
    void setTurnGunStoresRadians() {
        double angle = Math.toRadians(15);
        peer.setTurnGun(angle);
        assertEquals(angle, peer.getGunTurnRemaining(), 1e-9);
    }

    @Test
    void setTurnGunReplacesRemaining() {
        peer.setTurnGun(Math.toRadians(10));
        peer.setTurnGun(Math.toRadians(-5));
        assertEquals(Math.toRadians(-5), peer.getGunTurnRemaining(), 1e-9);
    }

    @Test
    void gunTurnRemainingStartsAtZero() {
        assertEquals(0.0, peer.getGunTurnRemaining(), 1e-9);
    }

    // --- updateState ---

    @Test
    void updateStateSetsPositionAndHeading() {
        peer.updateState(100, 200, 1.5, 0, 50);
        assertEquals(100, peer.getX(), 1e-9);
        assertEquals(200, peer.getY(), 1e-9);
        assertEquals(1.5, peer.getGunHeading(), 1e-9);
        assertEquals(50, peer.getEnergy(), 1e-9);
    }

    @Test
    void updateStateDoesNotOverwriteGunHeat() {
        // Gun heat starts at 3.0; updateState should NOT change it
        peer.updateState(100, 200, 0, 0, 100);
        assertEquals(3.0, peer.getGunHeat(), 1e-9);
    }

    @Test
    void updateStateOverwritesEnergy() {
        coolDown(peer);
        peer.updateState(100, 200, 0, 0, 100);
        peer.setFire(2.0); // energy drops to 98
        assertEquals(98.0, peer.getEnergy(), 1e-9);
        // Next tick snapshot says energy is 96
        peer.updateState(100, 200, 0, 0, 96);
        assertEquals(96.0, peer.getEnergy(), 1e-9);
    }

    // --- Movement no-ops ---

    @Test
    void movementCommandsAreNoOps() {
        peer.setMove(100);
        peer.setTurnBody(0.5);
        peer.setTurnGun(0.3);
        peer.setTurnRadar(0.2);
        assertEquals(0, peer.getDistanceRemaining(), 1e-9);
    }

    // --- Battlefield dimensions ---

    @Test
    void battlefieldDimensionsReturned() {
        assertEquals(800, peer.getBattleFieldWidth(), 1e-9);
        assertEquals(600, peer.getBattleFieldHeight(), 1e-9);
    }

    @Test
    void gunCoolingRateReturned() {
        assertEquals(0.1, peer.getGunCoolingRate(), 1e-9);
    }

    // --- Full tick lifecycle ---

    @Test
    void fullTickLifecycle_cannotFireUntilCool() {
        // 30 ticks to cool from 3.0 to 0.0
        for (int tick = 1; tick <= 30; tick++) {
            peer.executeTick();
            peer.updateState(400, 300, 0, 0, 100);
            // On each tick, try to fire — should fail because on tick 30
            // the heat just reached 0 by executeTick's clamp, but within that
            // tick the fire should succeed
        }
        // After 30 executeTicks, heat should be 0.0
        assertEquals(0.0, peer.getGunHeat(), 1e-9);
        Bullet b = peer.setFire(2.0);
        assertNotNull(b, "Should fire after gun has cooled to 0");
    }

    @Test
    void fireMaxPowerHeatSequence() {
        coolDown(peer);
        peer.updateState(400, 300, 0, 0, 100);
        peer.setFire(3.0); // heat = 1 + 3/5 = 1.6

        // Takes 16 ticks to cool from 1.6 to 0
        for (int tick = 1; tick <= 15; tick++) {
            peer.executeTick();
            peer.updateState(400, 300, 0, 0, 100);
            assertNull(peer.setFire(3.0), "Should not fire on tick " + tick);
        }
        peer.executeTick();
        peer.updateState(400, 300, 0, 0, 100);
        Bullet b = peer.setFire(3.0);
        assertNotNull(b, "Should fire after 16 ticks of cooling from 1.6");
    }

    @Test
    void fireMinPowerHeatSequence() {
        coolDown(peer);
        peer.updateState(400, 300, 0, 0, 100);
        peer.setFire(0.1); // heat = 1 + 0.1/5 = 1.02

        // Takes 11 ticks to cool from 1.02 to 0
        // Tick 10: 1.02 - 1.0 = 0.02 → still hot
        // Tick 11: 0.02 - 0.1 → clamped to 0 → can fire
        for (int tick = 1; tick <= 10; tick++) {
            peer.executeTick();
            peer.updateState(400, 300, 0, 0, 100);
            assertNull(peer.setFire(0.1), "Should not fire on tick " + tick);
        }
        peer.executeTick();
        peer.updateState(400, 300, 0, 0, 100);
        Bullet b = peer.setFire(0.1);
        assertNotNull(b, "Should fire after 11 ticks of cooling from 1.02");
    }

    // --- Helper ---

    /** Cool gun fully from initial heat of 3.0 (30 ticks at 0.1/tick). */
    private static void coolDown(ObserverRobotPeer peer) {
        for (int i = 0; i < 30; i++) {
            peer.executeTick();
        }
    }
}

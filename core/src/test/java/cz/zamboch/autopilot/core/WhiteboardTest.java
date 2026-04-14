package cz.zamboch.autopilot.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Whiteboard: lifecycle, feature storage, per-round counters,
 * state transitions, and edge cases.
 */
class WhiteboardTest {

    private Whiteboard wb;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        wb.onRoundStart(0, 800, 600, 0.1, 10);
    }

    // === Feature array ===

    @Test
    void featureDefaultsToNaN() {
        assertFalse(wb.hasFeature(Feature.DISTANCE));
        assertTrue(Double.isNaN(wb.getFeature(Feature.DISTANCE)));
    }

    @Test
    void setAndGetFeature() {
        wb.setFeature(Feature.DISTANCE, 42.5);
        assertTrue(wb.hasFeature(Feature.DISTANCE));
        assertEquals(42.5, wb.getFeature(Feature.DISTANCE), 0.001);
    }

    @Test
    void advanceTickClearsFeatures() {
        wb.setFeature(Feature.DISTANCE, 100.0);
        wb.advanceTick();
        assertFalse(wb.hasFeature(Feature.DISTANCE));
        assertTrue(Double.isNaN(wb.getFeature(Feature.DISTANCE)));
    }

    // === Own state ===

    @Test
    void setOurStateStoresAllFields() {
        wb.setOurState(100, 200, 1.0, 1.5, 2.0, 4.5, 80, 1.2);
        assertEquals(100, wb.getOurX(), 0.001);
        assertEquals(200, wb.getOurY(), 0.001);
        assertEquals(1.0, wb.getOurHeading(), 0.001);
        assertEquals(1.5, wb.getOurGunHeading(), 0.001);
        assertEquals(2.0, wb.getOurRadarHeading(), 0.001);
        assertEquals(4.5, wb.getOurVelocity(), 0.001);
        assertEquals(80, wb.getOurEnergy(), 0.001);
        assertEquals(1.2, wb.getOurGunHeat(), 0.001);
    }

    // === Opponent scan ===

    @Test
    void setOpponentScanUpdatesPrevValues() {
        wb.setTick(0);
        wb.setOpponentScan(100, 200, 1.0, 3.0, 90);
        wb.advanceTick();
        wb.setTick(1);
        wb.setOpponentScan(110, 210, 1.1, 4.0, 85);

        assertEquals(90, wb.getPrevOpponentEnergy(), 0.001);
        assertEquals(1.0, wb.getPrevOpponentHeading(), 0.001);
        assertEquals(3.0, wb.getPrevOpponentVelocity(), 0.001);
        assertEquals(0, wb.getPrevScanTick());
        assertEquals(1, wb.getLastScanTick());
        assertTrue(wb.isScanAvailableThisTick());
    }

    @Test
    void scanAvailableResetsByAdvanceTick() {
        wb.setTick(0);
        wb.setOpponentScan(100, 200, 0, 0, 100);
        assertTrue(wb.isScanAvailableThisTick());
        wb.advanceTick();
        assertFalse(wb.isScanAvailableThisTick());
    }

    // === Tick and round lifecycle ===

    @Test
    void advanceTickIncrementsTick() {
        wb.setTick(5);
        wb.advanceTick();
        assertEquals(6, wb.getTick());
    }

    @Test
    void onRoundStartResetsTickAndCounters() {
        wb.setTick(50);
        wb.incrementOurShotsFired();
        wb.addDamageDealt(10);
        wb.incrementOurBulletHitCount();

        wb.onRoundStart(1, 800, 600, 0.1, 10);
        assertEquals(0, wb.getTick());
        assertEquals(1, wb.getRound());
        // Per-round counters reset
        assertEquals(0, wb.getOurShotsFiredThisRound());
        assertEquals(0, wb.getDamageDealtThisRound());
        assertEquals(0, wb.getOurBulletHitCountThisRound());
        // Battle-level counters preserved
        assertEquals(1, wb.getOurShotsFired());
        assertEquals(10, wb.getDamageDealt(), 0.001);
        assertEquals(1, wb.getOurBulletHitCount());
    }

    @Test
    void resetBattleClearsEverything() {
        wb.incrementOurShotsFired();
        wb.incrementRoundsWon();
        wb.addDamageDealt(50);
        wb.resetBattle();

        assertEquals(0, wb.getOurShotsFired());
        assertEquals(0, wb.getRoundsWon());
        assertEquals(0, wb.getDamageDealt(), 0.001);
        assertEquals(0, wb.getRound());
    }

    // === Battle constants ===

    @Test
    void battleConstantsStoredFromRoundStart() {
        wb.onRoundStart(3, 1000, 800, 0.2, 15);
        assertEquals(3, wb.getRound());
        assertEquals(1000, wb.getBattlefieldWidth());
        assertEquals(800, wb.getBattlefieldHeight());
        assertEquals(0.2, wb.getGunCoolingRate(), 0.001);
        assertEquals(15, wb.getNumRounds());
    }

    // === Cumulative counters ===

    @Test
    void shotsFiredIncrementsCorrectly() {
        wb.incrementOurShotsFired();
        wb.incrementOurShotsFired();
        assertEquals(2, wb.getOurShotsFired());
        assertEquals(2, wb.getOurShotsFiredThisRound());
    }

    @Test
    void opponentShotsDetectedIncrementsCorrectly() {
        wb.incrementOpponentShotsDetected();
        wb.incrementOpponentShotsDetected();
        wb.incrementOpponentShotsDetected();
        assertEquals(3, wb.getOpponentShotsDetected());
        assertEquals(3, wb.getOpponentShotsDetectedThisRound());
    }

    @Test
    void damageTracking() {
        wb.addDamageDealt(10.5);
        wb.addDamageDealt(5.5);
        assertEquals(16.0, wb.getDamageDealt(), 0.001);
        assertEquals(16.0, wb.getDamageDealtThisRound(), 0.001);

        wb.addDamageReceived(8.0);
        assertEquals(8.0, wb.getDamageReceived(), 0.001);
        assertEquals(8.0, wb.getDamageReceivedThisRound(), 0.001);
    }

    @Test
    void bulletHitCounters() {
        wb.incrementOurBulletHitCount();
        wb.incrementOpponentBulletHitCount();
        wb.incrementOpponentBulletHitCount();
        assertEquals(1, wb.getOurBulletHitCount());
        assertEquals(2, wb.getOpponentBulletHitCount());
        assertEquals(1, wb.getOurBulletHitCountThisRound());
        assertEquals(2, wb.getOpponentBulletHitCountThisRound());
    }

    @Test
    void roundsWonLost() {
        wb.incrementRoundsWon();
        wb.incrementRoundsWon();
        wb.incrementRoundsLost();
        assertEquals(2, wb.getRoundsWon());
        assertEquals(1, wb.getRoundsLost());
    }

    // === Per-round counter isolation ===

    @Test
    void perRoundCountersResetOnNewRound() {
        wb.incrementOurShotsFired();
        wb.addDamageDealt(20);
        wb.incrementOurBulletHitCount();
        wb.incrementOpponentShotsDetected();
        wb.addDamageReceived(10);
        wb.incrementOpponentBulletHitCount();

        wb.onRoundStart(1, 800, 600, 0.1, 10);

        assertEquals(0, wb.getOurShotsFiredThisRound());
        assertEquals(0, wb.getDamageDealtThisRound(), 0.001);
        assertEquals(0, wb.getOurBulletHitCountThisRound());
        assertEquals(0, wb.getOpponentShotsDetectedThisRound());
        assertEquals(0, wb.getDamageReceivedThisRound(), 0.001);
        assertEquals(0, wb.getOpponentBulletHitCountThisRound());

        // Battle-level counters preserved
        assertEquals(1, wb.getOurShotsFired());
        assertEquals(20, wb.getDamageDealt(), 0.001);
    }

    // === Event flags ===

    @Test
    void eventFlagsResetByAdvanceTick() {
        wb.setWeHitOpponentThisTick(true);
        wb.setOpponentHitWallThisTick(true);
        assertTrue(wb.isWeHitOpponentThisTick());
        assertTrue(wb.isOpponentHitWallThisTick());

        wb.advanceTick();
        assertFalse(wb.isWeHitOpponentThisTick());
        assertFalse(wb.isOpponentHitWallThisTick());
    }

    // === Movement segmentation state ===

    @Test
    void movementSegmentationState() {
        wb.setPrevLateralDirection(1);
        wb.setTicksSinceDirectionChange(5);
        assertEquals(1, wb.getPrevLateralDirection());
        assertEquals(5, wb.getTicksSinceDirectionChange());
    }

    // === Opponent fire tracking ===

    @Test
    void opponentFireTracking() {
        wb.setLastOpponentFire(10, 2.5);
        assertEquals(10, wb.getLastOpponentFireTick());
        assertEquals(2.5, wb.getLastOpponentFirePower(), 0.001);
    }

    @Test
    void fireTrackingResetsOnNewRound() {
        wb.setLastOpponentFire(10, 2.5);
        wb.onRoundStart(1, 800, 600, 0.1, 10);
        assertEquals(-1, wb.getLastOpponentFireTick());
        assertEquals(0, wb.getLastOpponentFirePower(), 0.001);
    }

    // === Battle ID ===

    @Test
    void battleIdSetAndGet() {
        wb.setBattleId("abc123");
        assertEquals("abc123", wb.getBattleId());
    }

    // === Initial opponent state ===

    @Test
    void prevOpponentValuesStartAsNaN() {
        assertTrue(Double.isNaN(wb.getPrevOpponentHeading()));
        assertTrue(Double.isNaN(wb.getPrevOpponentVelocity()));
    }

    @Test
    void prevOpponentResetOnRoundStart() {
        wb.setTick(0);
        wb.setOpponentScan(100, 200, 1.0, 3.0, 90);
        wb.onRoundStart(1, 800, 600, 0.1, 10);
        assertTrue(Double.isNaN(wb.getPrevOpponentHeading()));
        assertTrue(Double.isNaN(wb.getPrevOpponentVelocity()));
        assertEquals(-1, wb.getLastScanTick());
        assertEquals(-1, wb.getPrevScanTick());
    }
}

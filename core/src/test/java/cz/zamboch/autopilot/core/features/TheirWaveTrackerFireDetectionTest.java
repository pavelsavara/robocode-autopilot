package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class TheirWaveTrackerFireDetectionTest {

    private Whiteboard wb;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        wb.registerFeatures(
                new ScanFeatures(),
                new TheirWaveTracker());
    }

    @Test
    void detectsOpponentFireFromEnergyDrop() {
        // First scan: establish baseline energy
        wb.setFeature(Feature.TICK, 5);
        wb.setFeature(Feature.SCAN_TICK, 5);
        wb.setFeature(Feature.OUR_HEADING, 0);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.setFeature(Feature.OPPONENT_HEADING, 0);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
        wb.setFeature(Feature.OPPONENT_ENERGY, 100.0);
        wb.process();

        // First scan establishes the latest energy; there is no previous scan yet.
        assertEquals(100.0, wb.getLatestScanFeature(Feature.OPPONENT_ENERGY), 1e-9);
        assertTrue(Double.isNaN(wb.getPreviousTickFeature(Feature.OPPONENT_ENERGY)));
        // No fire power yet (first scan, no previous energy)
        assertTrue(Double.isNaN(wb.getFeature(Feature.THEIR_FIRE_POWER)));

        // Second scan: energy dropped by 2.0 (opponent fired power 2.0)
        wb.setFeature(Feature.TICK, 8);
        wb.setFeature(Feature.SCAN_TICK, 8);
        wb.setFeature(Feature.OPPONENT_ENERGY, 98.0);
        wb.process();

        assertEquals(2.0, wb.getFeature(Feature.THEIR_FIRE_POWER), 1e-9);
        assertEquals(100.0, wb.getFeature(Feature.PREV_SCAN_OPPONENT_ENERGY), 1e-9);
        assertEquals(100.0, wb.getPreviousTickFeature(Feature.OPPONENT_ENERGY), 1e-9);
        assertEquals(98.0, wb.getLatestScanFeature(Feature.OPPONENT_ENERGY), 1e-9);
    }

    @Test
    void correctsForOurBulletDamage() {
        // First scan: energy 100
        wb.setFeature(Feature.TICK, 1);
        wb.setFeature(Feature.SCAN_TICK, 1);
        wb.setFeature(Feature.OUR_HEADING, 0);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.setFeature(Feature.OPPONENT_HEADING, 0);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
        wb.setFeature(Feature.OPPONENT_ENERGY, 100.0);
        wb.process();

        // Between scans: we hit opponent for 12.0 damage (power 3.0 → 4*3=12)
        // Opponent also fired power 1.5
        // Observed: 100 - 12(our hit) - 1.5(their fire) = 86.5
        wb.setFeature(Feature.TICK, 4);
        wb.setFeature(Feature.SCAN_TICK, 4);
        wb.setFeature(Feature.OPPONENT_ENERGY, 86.5);
        wb.setFeature(Feature.OUR_BULLET_DAMAGE_TO_OPPONENT, 12.0);
        wb.process();

        // adjustedDrop = (100 - 86.5) - 12.0 - 0 + 0 = 1.5
        assertEquals(1.5, wb.getFeature(Feature.THEIR_FIRE_POWER), 1e-9);
    }

    @Test
    void correctsForRamDamage() {
        // First scan
        wb.setFeature(Feature.TICK, 1);
        wb.setFeature(Feature.SCAN_TICK, 1);
        wb.setFeature(Feature.OUR_HEADING, 0);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.setFeature(Feature.OPPONENT_HEADING, 0);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
        wb.setFeature(Feature.OPPONENT_ENERGY, 100.0);
        wb.process();

        // Between scans: ram dealt 0.6 damage, opponent fired power 1.0
        // Observed: 100 - 0.6(ram) - 1.0(fire) = 98.4
        wb.setFeature(Feature.TICK, 3);
        wb.setFeature(Feature.SCAN_TICK, 3);
        wb.setFeature(Feature.OPPONENT_ENERGY, 98.4);
        wb.setFeature(Feature.RAM_DAMAGE_TO_OPPONENT, 0.6);
        wb.process();

        // adjustedDrop = (100 - 98.4) - 0 - 0.6 + 0 = 1.0
        assertEquals(1.0, wb.getFeature(Feature.THEIR_FIRE_POWER), 1e-9);
    }

    @Test
    void correctsForOpponentBulletEnergyGain() {
        // First scan
        wb.setFeature(Feature.TICK, 1);
        wb.setFeature(Feature.SCAN_TICK, 1);
        wb.setFeature(Feature.OUR_HEADING, 0);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.setFeature(Feature.OPPONENT_HEADING, 0);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
        wb.setFeature(Feature.OPPONENT_ENERGY, 100.0);
        wb.process();

        // Between scans: opponent hit us with power 2.0 → gains 3*2=6 energy back
        // Opponent also fired power 3.0
        // Observed: 100 + 6(gain) - 3.0(fire) = 103.0
        wb.setFeature(Feature.TICK, 5);
        wb.setFeature(Feature.SCAN_TICK, 5);
        wb.setFeature(Feature.OPPONENT_ENERGY, 103.0);
        wb.setFeature(Feature.OPPONENT_BULLET_ENERGY_GAIN, 6.0);
        wb.process();

        // adjustedDrop = (100 - 103) - 0 - 0 + 6 = 3.0
        assertEquals(3.0, wb.getFeature(Feature.THEIR_FIRE_POWER), 1e-9);
    }

    @Test
    void noFireDetectedWhenDropOutOfRange() {
        // First scan
        wb.setFeature(Feature.TICK, 1);
        wb.setFeature(Feature.SCAN_TICK, 1);
        wb.setFeature(Feature.OUR_HEADING, 0);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.setFeature(Feature.OPPONENT_HEADING, 0);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
        wb.setFeature(Feature.OPPONENT_ENERGY, 100.0);
        wb.process();

        // Energy increased (e.g. opponent hit us, no fire)
        wb.setFeature(Feature.TICK, 3);
        wb.setFeature(Feature.SCAN_TICK, 3);
        wb.setFeature(Feature.OPPONENT_ENERGY, 103.0);
        wb.process();

        // adjustedDrop = (100 - 103) = -3.0, not in [0.1, 3.0]
        assertTrue(Double.isNaN(wb.getFeature(Feature.THEIR_FIRE_POWER)));
    }

    @Test
    void noFireDetectedWhenDropTooLarge() {
        // First scan
        wb.setFeature(Feature.TICK, 1);
        wb.setFeature(Feature.SCAN_TICK, 1);
        wb.setFeature(Feature.OUR_HEADING, 0);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.setFeature(Feature.OPPONENT_HEADING, 0);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
        wb.setFeature(Feature.OPPONENT_ENERGY, 100.0);
        wb.process();

        // Drop of 5.0 (> 3.0 max fire power) — wall hit or something else
        wb.setFeature(Feature.TICK, 3);
        wb.setFeature(Feature.SCAN_TICK, 3);
        wb.setFeature(Feature.OPPONENT_ENERGY, 95.0);
        wb.process();

        assertTrue(Double.isNaN(wb.getFeature(Feature.THEIR_FIRE_POWER)));
    }

    @Test
    void doesNotComputeOnNonScanTick() {
        // First scan
        wb.setFeature(Feature.TICK, 1);
        wb.setFeature(Feature.SCAN_TICK, 1);
        wb.setFeature(Feature.OUR_HEADING, 0);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.setFeature(Feature.OPPONENT_HEADING, 0);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
        wb.setFeature(Feature.OPPONENT_ENERGY, 100.0);
        wb.process();

        // Non-scan tick: no current scan row
        wb.setFeature(Feature.TICK, 2);
        wb.process();

        // Should not have computed fire power
        assertTrue(Double.isNaN(wb.getFeature(Feature.THEIR_FIRE_POWER)));
    }

    @Test
    void accumulatorsSubtractedFromEnergyDrop() {
        // First scan: establish baseline
        wb.setFeature(Feature.TICK, 1);
        wb.setFeature(Feature.SCAN_TICK, 1);
        wb.setFeature(Feature.OUR_HEADING, 0);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.setFeature(Feature.OPPONENT_HEADING, 0);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
        wb.setFeature(Feature.OPPONENT_ENERGY, 100.0);
        wb.process();

        // Second scan: drop 7.6 total, but 5.0 from our bullet + 0.6 ram + 3.0 gain
        // adjustedDrop = (100 - 96.4) - 5.0 - 0.6 + 3.0 = 1.0
        wb.setFeature(Feature.TICK, 4);
        wb.setFeature(Feature.SCAN_TICK, 4);
        wb.setFeature(Feature.OPPONENT_ENERGY, 96.4);
        wb.setFeature(Feature.OUR_BULLET_DAMAGE_TO_OPPONENT, 5.0);
        wb.setFeature(Feature.OPPONENT_BULLET_ENERGY_GAIN, 3.0);
        wb.setFeature(Feature.RAM_DAMAGE_TO_OPPONENT, 0.6);
        wb.process();

        // Fire power correctly accounts for all accumulators
        assertEquals(1.0, wb.getFeature(Feature.THEIR_FIRE_POWER), 1e-9);
    }

    @Test
    void minimumFirePowerDetected() {
        // First scan
        wb.setFeature(Feature.TICK, 1);
        wb.setFeature(Feature.SCAN_TICK, 1);
        wb.setFeature(Feature.OUR_HEADING, 0);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.setFeature(Feature.OPPONENT_HEADING, 0);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
        wb.setFeature(Feature.OPPONENT_ENERGY, 100.0);
        wb.process();

        // Minimum fire power = 0.1, represented just below the threshold after
        // binary floating-point subtraction.
        wb.setFeature(Feature.TICK, 3);
        wb.setFeature(Feature.SCAN_TICK, 3);
        wb.setFeature(Feature.OPPONENT_ENERGY, 99.9);
        wb.process();

        assertEquals(0.1, wb.getFeature(Feature.THEIR_FIRE_POWER), 1e-9);
    }

    @Test
    void detectsSlightlyAboveMinimumFirePower() {
        // First scan
        wb.setFeature(Feature.TICK, 1);
        wb.setFeature(Feature.SCAN_TICK, 1);
        wb.setFeature(Feature.OUR_HEADING, 0);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.setFeature(Feature.OPPONENT_HEADING, 0);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
        wb.setFeature(Feature.OPPONENT_ENERGY, 100.0);
        wb.process();

        // Drop of 0.2 — clearly above 0.1
        wb.setFeature(Feature.TICK, 3);
        wb.setFeature(Feature.SCAN_TICK, 3);
        wb.setFeature(Feature.OPPONENT_ENERGY, 99.8);
        wb.process();

        assertEquals(0.2, wb.getFeature(Feature.THEIR_FIRE_POWER), 1e-9);
    }

    @Test
    void rejectsInactivityZapWhileGunHot() {
        wb.setFeature(Feature.TICK, 1);
        wb.setFeature(Feature.SCAN_TICK, 1);
        wb.setFeature(Feature.OUR_HEADING, 0);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.setFeature(Feature.OPPONENT_HEADING, 0);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
        wb.setFeature(Feature.OPPONENT_ENERGY, 100.0);
        wb.process();

        // Consecutive ticks each losing exactly one 0.1 quantum: the inactivity
        // zap, not a fire (a hot gun cannot fire).
        for (int t = 2; t <= 6; t++) {
            wb.setFeature(Feature.TICK, t);
            wb.setFeature(Feature.SCAN_TICK, t);
            wb.setFeature(Feature.OPPONENT_ENERGY, 100.0 - 0.1 * (t - 1));
            wb.process();
            assertTrue(Double.isNaN(wb.getFeature(Feature.THEIR_FIRE_POWER)),
                    "tick " + t + " should be inactivity zap, not a fire");
        }
    }

    @Test
    void realFireDuringDrainReadsUninflatedPower() {
        wb.setFeature(Feature.TICK, 1);
        wb.setFeature(Feature.SCAN_TICK, 1);
        wb.setFeature(Feature.OUR_HEADING, 0);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.setFeature(Feature.OPPONENT_HEADING, 0);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
        wb.setFeature(Feature.OPPONENT_ENERGY, 100.0);
        wb.process();

        // Two zap ticks establish an active drain.
        wb.setFeature(Feature.TICK, 2);
        wb.setFeature(Feature.SCAN_TICK, 2);
        wb.setFeature(Feature.OPPONENT_ENERGY, 99.9);
        wb.process();
        wb.setFeature(Feature.TICK, 3);
        wb.setFeature(Feature.SCAN_TICK, 3);
        wb.setFeature(Feature.OPPONENT_ENERGY, 99.8);
        wb.process();

        // Tick 4 drops 0.2: a real 0.1 fire stacked on the 0.1 drain. The drain
        // must be removed so the fire reads 0.1, not the inflated 0.2.
        wb.setFeature(Feature.TICK, 4);
        wb.setFeature(Feature.SCAN_TICK, 4);
        wb.setFeature(Feature.OPPONENT_ENERGY, 99.6);
        wb.process();

        assertEquals(0.1, wb.getFeature(Feature.THEIR_FIRE_POWER), 1e-9);
    }

    @Test
    void rejectsMinPowerDropImmediatelyAfterFire() {
        wb.setFeature(Feature.TICK, 1);
        wb.setFeature(Feature.SCAN_TICK, 1);
        wb.setFeature(Feature.OUR_HEADING, 0);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.setFeature(Feature.OPPONENT_HEADING, 0);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
        wb.setFeature(Feature.OPPONENT_ENERGY, 100.0);
        wb.process();

        // A clear fire (power 2.0) leaves the gun hot.
        wb.setFeature(Feature.TICK, 2);
        wb.setFeature(Feature.SCAN_TICK, 2);
        wb.setFeature(Feature.OPPONENT_ENERGY, 98.0);
        wb.process();
        assertEquals(2.0, wb.getFeature(Feature.THEIR_FIRE_POWER), 1e-9);

        // Next tick loses one quantum: impossible as a fire with a hot gun.
        wb.setFeature(Feature.TICK, 3);
        wb.setFeature(Feature.SCAN_TICK, 3);
        wb.setFeature(Feature.OPPONENT_ENERGY, 97.9);
        wb.process();
        assertTrue(Double.isNaN(wb.getFeature(Feature.THEIR_FIRE_POWER)));
    }

    @Test
    void combatActivityEndsDrainAndDetectionResumes() {
        wb.setFeature(Feature.TICK, 1);
        wb.setFeature(Feature.SCAN_TICK, 1);
        wb.setFeature(Feature.OUR_HEADING, 0);
        wb.setFeature(Feature.BEARING_RADIANS, 0);
        wb.setFeature(Feature.OPPONENT_HEADING, 0);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
        wb.setFeature(Feature.OPPONENT_ENERGY, 100.0);
        wb.process();

        // Establish a drain.
        wb.setFeature(Feature.TICK, 2);
        wb.setFeature(Feature.SCAN_TICK, 2);
        wb.setFeature(Feature.OPPONENT_ENERGY, 99.9);
        wb.process();
        wb.setFeature(Feature.TICK, 3);
        wb.setFeature(Feature.SCAN_TICK, 3);
        wb.setFeature(Feature.OPPONENT_ENERGY, 99.8);
        wb.process();
        assertTrue(Double.isNaN(wb.getFeature(Feature.THEIR_FIRE_POWER)));

        // Opponent hits us (gains 6) and fires 1.0: combat resets inactivity, so
        // the drain stops and the fire is detected at full power.
        wb.setFeature(Feature.TICK, 4);
        wb.setFeature(Feature.SCAN_TICK, 4);
        wb.setFeature(Feature.OPPONENT_ENERGY, 104.8);
        wb.setFeature(Feature.OPPONENT_BULLET_ENERGY_GAIN, 6.0);
        wb.process();

        assertEquals(1.0, wb.getFeature(Feature.THEIR_FIRE_POWER), 1e-9);
    }
}

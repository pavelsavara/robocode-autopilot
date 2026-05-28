package cz.zamboch.autopilot.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class GuessFactorTest {

    @Test
    void bulletSpeed() {
        assertEquals(19.7, GuessFactor.bulletSpeed(0.1), 1e-9);
        assertEquals(17.0, GuessFactor.bulletSpeed(1.0), 1e-9);
        assertEquals(14.0, GuessFactor.bulletSpeed(2.0), 1e-9);
        assertEquals(11.0, GuessFactor.bulletSpeed(3.0), 1e-9);
    }

    @Test
    void maxEscapeAngle() {
        double speed = GuessFactor.bulletSpeed(3.0); // 11.0
        double mea = GuessFactor.maxEscapeAngle(speed);
        assertEquals(Math.asin(8.0 / 11.0), mea, 1e-9);
    }

    @Test
    void gfToBinAndBack() {
        // GF=0 → center bin
        assertEquals(15, GuessFactor.gfToBinIndex(0.0, 31));
        // GF=1 → last bin
        assertEquals(30, GuessFactor.gfToBinIndex(1.0, 31));
        // GF=-1 → first bin
        assertEquals(0, GuessFactor.gfToBinIndex(-1.0, 31));

        // Round trip
        for (int i = 0; i < 31; i++) {
            double gf = GuessFactor.binIndexToGf(i, 31);
            int bin = GuessFactor.gfToBinIndex(gf, 31);
            assertEquals(i, bin, "Bin " + i + " round trip failed");
        }
    }

    @Test
    void gfClampsToBounds() {
        // Excessively large offset → clamped to 1
        double gf = GuessFactor.guessFactor(2.0, 1.0, 1);
        assertEquals(1.0, gf, 1e-9);

        // Negative → clamped to -1
        gf = GuessFactor.guessFactor(-2.0, 1.0, 1);
        assertEquals(-1.0, gf, 1e-9);
    }

    @Test
    void guessFactorDirection() {
        double mea = 0.5;
        // direction=1: offset/mea
        assertEquals(0.5, GuessFactor.guessFactor(0.25, mea, 1), 1e-9);
        // direction=-1: flips sign
        assertEquals(-0.5, GuessFactor.guessFactor(0.25, mea, -1), 1e-9);
    }

    @Test
    void distanceSegments() {
        assertEquals(0, GuessFactor.distanceSegment(100));
        assertEquals(1, GuessFactor.distanceSegment(300));
        assertEquals(2, GuessFactor.distanceSegment(500));
        assertEquals(3, GuessFactor.distanceSegment(700));
        assertEquals(4, GuessFactor.distanceSegment(900));
        // Boundaries
        assertEquals(0, GuessFactor.distanceSegment(0));
        assertEquals(1, GuessFactor.distanceSegment(200));
        assertEquals(4, GuessFactor.distanceSegment(800));
    }

    @Test
    void lateralVelocitySegments() {
        assertEquals(0, GuessFactor.lateralVelocitySegment(0));
        assertEquals(0, GuessFactor.lateralVelocitySegment(1.0));
        assertEquals(1, GuessFactor.lateralVelocitySegment(2.0));
        assertEquals(2, GuessFactor.lateralVelocitySegment(5.0));
        assertEquals(3, GuessFactor.lateralVelocitySegment(7.0));
        assertEquals(4, GuessFactor.lateralVelocitySegment(8.0));
        // Negative values use absolute
        assertEquals(2, GuessFactor.lateralVelocitySegment(-5.0));
    }

    @Test
    void direction() {
        assertEquals(1, GuessFactor.direction(3.0));
        assertEquals(1, GuessFactor.direction(0.0));
        assertEquals(-1, GuessFactor.direction(-3.0));
    }

    @Test
    void fnv1a32KnownVectors() {
        // Empty string
        assertEquals(0x811c9dc5, RoboMath.fnv1a32(""));
        // Known FNV-1a test vectors
        int hash = RoboMath.fnv1a32("a");
        assertNotEquals(0, hash);
        // Different strings produce different hashes
        assertNotEquals(RoboMath.fnv1a32("SittingDuck"), RoboMath.fnv1a32("Crazy"));
    }
}

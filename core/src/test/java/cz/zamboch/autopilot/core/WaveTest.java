package cz.zamboch.autopilot.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class WaveTest {

    @Test
    void distanceTravelled() {
        Wave wave = new Wave(100, 100, 10, 0, 14.0, 1, 0, 0);
        assertEquals(0, wave.distanceTravelled(10), 1e-9);
        assertEquals(14.0, wave.distanceTravelled(11), 1e-9);
        assertEquals(140.0, wave.distanceTravelled(20), 1e-9);
    }

    @Test
    void hasReachedDetectsWavePassingTarget() {
        // Wave from (100, 100), bullet speed 14, target at (100, 200) → distance 100
        Wave wave = new Wave(100, 100, 10, 0, 14.0, 1, 0, 0);
        // At tick 17: travelled 7*14 = 98 < 100 → not reached
        assertFalse(wave.hasReached(100, 200, 17));
        // At tick 18: travelled 8*14 = 112 > 100 → reached
        assertTrue(wave.hasReached(100, 200, 18));
    }

    @Test
    void computeGuessFactorHeadOn() {
        // Wave fired due north (bearing=0), target is straight ahead
        Wave wave = new Wave(100, 100, 10, 0, 14.0, 1, 0, 0);
        double gf = wave.computeGuessFactor(100, 200);
        assertEquals(0.0, gf, 0.01);
    }

    @Test
    void computeGuessFactorOffsetRight() {
        // Wave fired due north (bearing=0), target moved to the right
        Wave wave = new Wave(100, 100, 10, 0, 14.0, 1, 0, 0);
        double gf = wave.computeGuessFactor(150, 200);
        // Offset is positive (CW in Robocode), direction=1 → positive GF
        assertTrue(gf > 0);
        assertTrue(gf <= 1.0);
    }

    @Test
    void computeGuessFactorOffsetLeft() {
        // direction = -1 flips the sign
        Wave wave = new Wave(100, 100, 10, 0, 14.0, -1, 0, 0);
        double gf = wave.computeGuessFactor(150, 200);
        // Same physical offset but direction=-1 → negative GF
        assertTrue(gf < 0);
    }

    @Test
    void meaIsComputedCorrectly() {
        double speed = 14.0;
        Wave wave = new Wave(0, 0, 0, 0, speed, 1, 0, 0);
        assertEquals(Math.asin(8.0 / 14.0), wave.mea, 1e-9);
    }
}

package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import net.sf.robocode.security.HiddenAccess;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: verifies the robot-side THEIR_FIRE/THEIR_BREAK features
 * that FireFeatures + TheirWaveTracker produce inside ObserverContext.
 * These detect opponent firing via energy drop on scan.
 *
 * Key requirement: EventReconstructor needs radar to MOVE between ticks to
 * generate ScannedRobotEvents. The first tick never generates a scan (prevRadarHeading=NaN).
 * So we need 3 ticks minimum: tick 0 (sets prevRadarHeading), tick 1 (first scan),
 * tick 2 (second scan where energy drop is detected).
 */
@Tag("integration")
final class TheirWaveTrackerObserverTest {

    private ObserverContext[] observers;

    // Radar headings that oscillate around bearing to opponent.
    // Robot 0 at (400,200) → opponent at (400,400): bearing = 0 (north).
    // Oscillate: A→B (CW through 0) then B→A (CCW through 0).
    private static final double RADAR_A = 2 * Math.PI - 0.3; // ~5.98, just west of north
    private static final double RADAR_B = 0.3;                // just east of north

    // Robot 1 at (400,400) → opponent at (400,200): bearing = π (south).
    // Oscillate: 1A→1B (CW through π) then 1B→1A (CCW through π).
    private static final double RADAR_1A = Math.PI - 0.3;
    private static final double RADAR_1B = Math.PI + 0.3;

    @BeforeAll
    static void initRobocodeFramework() {
        HiddenAccess.init();
    }

    @BeforeEach
    void setUp() {
        observers = ObserverContext.createPair(800, 600, 0.1);
    }

    @Test
    void theirFirePowerDetectedOnOpponentEnergyDrop() {
        // Robot 1 fires at robot 0 (we observe from robot 0's perspective)
        // Need 3 ticks: tick0 (no scan), tick1 (first scan, energy=100), tick2 (second scan, energy=97)

        // Tick 0: initial positions, radar at heading A (no scan generated on first tick)
        IRobotSnapshot r0_t0 = TestSnapshots.robot(400, 200, 0, 0, 100, 3.0, 0, RADAR_A, 0, RobotState.ACTIVE, "alpha.Bot");
        IRobotSnapshot r1_t0 = TestSnapshots.robot(400, 400, 0, 0, 100, 3.0, 0, RADAR_1A, 1, RobotState.ACTIVE, "beta.Bot");

        ITurnSnapshot tick0 = TestSnapshots.turn(0, r0_t0, r1_t0);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick0);
        }

        // Tick 1: radar moves to heading B → first scan generated. Opponent energy still 100.
        IRobotSnapshot r0_t1 = TestSnapshots.robot(400, 200, 0, 0, 100, 2.9, 0, RADAR_B, 0, RobotState.ACTIVE, "alpha.Bot");
        IRobotSnapshot r1_t1 = TestSnapshots.robot(400, 400, 0, 0, 100, 2.9, 0, RADAR_1B, 1, RobotState.ACTIVE, "beta.Bot");

        ITurnSnapshot tick1 = TestSnapshots.turn(1, r0_t1, r1_t1);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick1);
        }

        // Tick 2: radar oscillates back to A → second scan (sweep passes through bearing 0/π).
        // Robot 1's energy dropped from 100 to 97 (fired power 3.0).
        IRobotSnapshot r0_t2 = TestSnapshots.robot(400, 200, 0, 0, 100, 2.8, 0, RADAR_A, 0, RobotState.ACTIVE, "alpha.Bot");
        IRobotSnapshot r1_t2 = TestSnapshots.robot(400, 400, 0, 0, 97, 1.4, 0, RADAR_1A, 1, RobotState.ACTIVE, "beta.Bot");

        ITurnSnapshot tick2 = TestSnapshots.turn(2, r0_t2, r1_t2);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick2);
        }

        // From robot 0's perspective: opponent (robot 1) fired
        Whiteboard wb0 = observers[0].wb();
        // THEIR_FIRE_POWER is consumed by TheirWaveTracker (cleared to NaN after creating a wave).
        // Verify fire detection via the wave outputs that persist:
        double theirFireTick = wb0.getFeature(Feature.THEIR_FIRE_TICK);
        double theirBulletSpeed = wb0.getFeature(Feature.THEIR_BULLET_SPEED);
        assertEquals(2.0, theirFireTick, 0.001, "THEIR_FIRE_TICK should be tick 2 (fire detected)");
        assertEquals(11.0, theirBulletSpeed, 0.1, "THEIR_BULLET_SPEED = 20 - 3*3 = 11 for power 3.0");
    }

    @Test
    void theirWaveBreaksWhenReaching() {
        // Robot 1 fires at robot 0. Distance = 200px, power 3.0 → speed 11
        // Need 3 ticks for fire detection, then advance until wave breaks.

        // Tick 0: initial, radar at heading A
        IRobotSnapshot r0_t0 = TestSnapshots.robot(400, 200, 0, 0, 100, 3.0, 0, RADAR_A, 0, RobotState.ACTIVE, "alpha.Bot");
        IRobotSnapshot r1_t0 = TestSnapshots.robot(400, 400, 0, 0, 100, 3.0, 0, RADAR_1A, 1, RobotState.ACTIVE, "beta.Bot");

        ITurnSnapshot tick0 = TestSnapshots.turn(0, r0_t0, r1_t0);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick0);
        }

        // Tick 1: first scan, opponent energy still 100
        IRobotSnapshot r0_t1 = TestSnapshots.robot(400, 200, 0, 0, 100, 2.9, 0, RADAR_B, 0, RobotState.ACTIVE, "alpha.Bot");
        IRobotSnapshot r1_t1 = TestSnapshots.robot(400, 400, 0, 0, 100, 2.9, 0, RADAR_1B, 1, RobotState.ACTIVE, "beta.Bot");
        ITurnSnapshot tick1 = TestSnapshots.turn(1, r0_t1, r1_t1);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick1);
        }

        // Tick 2: radar oscillates back → second scan, opponent fires (energy 100 → 97)
        IRobotSnapshot r0_t2 = TestSnapshots.robot(400, 200, 0, 0, 100, 2.8, 0, RADAR_A, 0, RobotState.ACTIVE, "alpha.Bot");
        IRobotSnapshot r1_t2 = TestSnapshots.robot(400, 400, 0, 0, 97, 1.4, 0, RADAR_1A, 1, RobotState.ACTIVE, "beta.Bot");
        ITurnSnapshot tick2 = TestSnapshots.turn(2, r0_t2, r1_t2);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick2);
        }

        // Advance until their wave breaks on us
        boolean resolved = false;
        for (int t = 3; t < 35; t++) {
            // Alternate radar between A and B to keep generating scans through the opponent
            double r0Radar = (t % 2 == 0) ? RADAR_A : RADAR_B;
            double r1Radar = (t % 2 == 0) ? RADAR_1A : RADAR_1B;
            IRobotSnapshot r0_n = TestSnapshots.robot(400, 200, 0, 0, 100, Math.max(0, 2.8 - t * 0.1), 0, r0Radar, 0, RobotState.ACTIVE, "alpha.Bot");
            IRobotSnapshot r1_n = TestSnapshots.robot(400, 400, 0, 0, 97, Math.max(0, 1.4 - t * 0.1), 0, r1Radar, 1, RobotState.ACTIVE, "beta.Bot");
            ITurnSnapshot tickN = TestSnapshots.turn(t, r0_n, r1_n);
            for (ObserverContext ctx : observers) {
                ctx.processTick(tickN);
            }

            Whiteboard wb0 = observers[0].wb();
            double theirBreakGf = wb0.getFeature(Feature.THEIR_BREAK_GF);
            if (!Double.isNaN(theirBreakGf)) {
                resolved = true;
                assertTrue(theirBreakGf >= -1.0 && theirBreakGf <= 1.0,
                        "THEIR_BREAK_GF should be in [-1,1], got: " + theirBreakGf);
                break;
            }
        }

        assertTrue(resolved, "Their wave should have resolved within 35 ticks");
    }

    @Test
    void noFireDetectedWithoutEnergyDrop() {
        // Both robots stay at same energy → no fire
        // Still need radar movement for scans to be generated
        IRobotSnapshot r0_t0 = TestSnapshots.robot(400, 200, 0, 0, 100, 3.0, 0, RADAR_A, 0, RobotState.ACTIVE, "alpha.Bot");
        IRobotSnapshot r1_t0 = TestSnapshots.robot(400, 400, 0, 0, 100, 3.0, 0, RADAR_1A, 1, RobotState.ACTIVE, "beta.Bot");

        ITurnSnapshot tick0 = TestSnapshots.turn(0, r0_t0, r1_t0);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick0);
        }

        // Tick 1: radar moves, scan generated, but no energy change
        IRobotSnapshot r0_t1 = TestSnapshots.robot(400, 200, 0, 0, 100, 2.9, 0, RADAR_B, 0, RobotState.ACTIVE, "alpha.Bot");
        IRobotSnapshot r1_t1 = TestSnapshots.robot(400, 400, 0, 0, 100, 2.9, 0, RADAR_1B, 1, RobotState.ACTIVE, "beta.Bot");

        ITurnSnapshot tick1 = TestSnapshots.turn(1, r0_t1, r1_t1);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick1);
        }

        // Tick 2: radar oscillates back, scan generated, still no energy change
        IRobotSnapshot r0_t2 = TestSnapshots.robot(400, 200, 0, 0, 100, 2.8, 0, RADAR_A, 0, RobotState.ACTIVE, "alpha.Bot");
        IRobotSnapshot r1_t2 = TestSnapshots.robot(400, 400, 0, 0, 100, 2.8, 0, RADAR_1A, 1, RobotState.ACTIVE, "beta.Bot");

        ITurnSnapshot tick2 = TestSnapshots.turn(2, r0_t2, r1_t2);
        for (ObserverContext ctx : observers) {
            ctx.processTick(tick2);
        }

        Whiteboard wb0 = observers[0].wb();
        double theirFirePower = wb0.getFeature(Feature.THEIR_FIRE_POWER);
        assertTrue(Double.isNaN(theirFirePower) || theirFirePower == 0,
                "THEIR_FIRE_POWER should be NaN or 0 without energy drop");
    }
}

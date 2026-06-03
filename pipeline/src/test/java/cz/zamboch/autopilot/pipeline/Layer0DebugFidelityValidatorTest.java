package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import robocode.control.snapshot.IRobotSnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Layer0DebugFidelityValidator}.
 * <p>
 * Layer 0 compares ALL features the live robot publishes against the observer's
 * robot-side whiteboard, with no exclusions (waves, breaks, decisions, scores
 * all included).
 */
class Layer0DebugFidelityValidatorTest {

    private Layer0DebugFidelityValidator validator;

    @BeforeEach
    void setUp() {
        validator = new Layer0DebugFidelityValidator();
    }

    @Test
    void matchingValues_noMismatches() {
        Whiteboard wb = new Whiteboard();
        wb.setFeature(Feature.TICK, 1.0);
        wb.setFeature(Feature.SCAN_TICK, 1.0);
        wb.setFeature(Feature.OUR_X, 150.0);
        wb.setFeature(Feature.OUR_Y, 250.0);

        IRobotSnapshot robot = TestSnapshots.robotWithDebug(150, 250, 0, "Autopilot",
                new robocode.control.snapshot.IDebugProperty[] {
                        TestSnapshots.debugProperty("TICK", "1.0"),
                        TestSnapshots.debugProperty("OUR_X", "150.0"),
                        TestSnapshots.debugProperty("OUR_Y", "250.0")
                });

        validator.validate(robot, wb);

        assertEquals(0, validator.getMismatches());
        assertTrue(validator.getChecks() >= 2);
    }

    @Test
    void mismatchDetected() {
        Whiteboard wb = new Whiteboard();
        wb.setFeature(Feature.TICK, 1.0);
        wb.setFeature(Feature.SCAN_TICK, 1.0);
        wb.setFeature(Feature.OUR_X, 150.0);

        IRobotSnapshot robot = TestSnapshots.robotWithDebug(150, 250, 0, "Autopilot",
                new robocode.control.snapshot.IDebugProperty[] {
                        TestSnapshots.debugProperty("TICK", "1.0"),
                        TestSnapshots.debugProperty("OUR_X", "999.0")
                });

        validator.validate(robot, wb);

        assertEquals(1, validator.getMismatches());
    }

    @Test
    void breakFeaturesAreIncluded() {
        Whiteboard wb = new Whiteboard();
        wb.setFeature(Feature.TICK, 1.0);
        wb.setFeature(Feature.SCAN_TICK, 1.0);
        wb.setFeature(Feature.OUR_X, 100.0);
        wb.setFeature(Feature.OUR_BREAK_TICK, 50.0);

        IRobotSnapshot robot = TestSnapshots.robotWithDebug(100, 200, 0, "Autopilot",
                new robocode.control.snapshot.IDebugProperty[] {
                        TestSnapshots.debugProperty("TICK", "1.0"),
                        TestSnapshots.debugProperty("OUR_X", "999.0"),
                        TestSnapshots.debugProperty("OUR_BREAK_TICK", "999.0")
                });

        validator.validate(robot, wb);

        // Layer 0 has no exclusions — both OUR_X and OUR_BREAK_TICK mismatch.
        assertEquals(2, validator.getMismatches());
    }

    @Test
    void scanTimingDerivedMismatchesAreWaivedOnlyOnSameScanTimingTick() {
        Whiteboard wb = new Whiteboard();
        wb.setFeature(Feature.TICK, 68.0);
        wb.setFeature(Feature.TICKS_SINCE_SCAN, 2.0);
        wb.setFeature(Feature.THEIR_AIM_OUR_X, 300.0);
        wb.setFeature(Feature.THEIR_AIM_DISTANCE, 400.0);
        wb.setFeature(Feature.THEIR_BREAK_TICK, 50.0);
        wb.setFeature(Feature.THEIR_BREAK_GF, 0.25);

        IRobotSnapshot robot = TestSnapshots.robotWithDebug(100, 200, 0, "Autopilot",
                new robocode.control.snapshot.IDebugProperty[] {
                        TestSnapshots.debugProperty("TICK", "68.0"),
                        TestSnapshots.debugProperty("TICKS_SINCE_SCAN", "3.0"),
                        TestSnapshots.debugProperty("THEIR_AIM_OUR_X", "310.0"),
                        TestSnapshots.debugProperty("THEIR_AIM_DISTANCE", "410.0"),
                        TestSnapshots.debugProperty("THEIR_BREAK_TICK", "51.0"),
                        TestSnapshots.debugProperty("THEIR_BREAK_GF", "0.5")
                });

        validator.validate(robot, wb);

        assertEquals(5, validator.getMismatches());
        assertEquals(5, validator.getWaivedMismatches());
        assertEquals(0, validator.getUnexpectedMismatches());
    }

    @Test
    void breakMismatchWithoutScanAgeMismatchIsUnexpected() {
        Whiteboard wb = new Whiteboard();
        wb.setFeature(Feature.TICK, 68.0);
        wb.setFeature(Feature.THEIR_BREAK_TICK, 50.0);

        IRobotSnapshot robot = TestSnapshots.robotWithDebug(100, 200, 0, "Autopilot",
                new robocode.control.snapshot.IDebugProperty[] {
                        TestSnapshots.debugProperty("TICK", "68.0"),
                        TestSnapshots.debugProperty("THEIR_BREAK_TICK", "51.0")
                });

        validator.validate(robot, wb);

        assertEquals(1, validator.getMismatches());
        assertEquals(0, validator.getWaivedMismatches());
        assertEquals(1, validator.getUnexpectedMismatches());
    }

    @Test
    void derivedMismatchOnDifferentTickFromScanAgeMismatchIsUnexpected() {
        Whiteboard scanTickWb = new Whiteboard();
        scanTickWb.setFeature(Feature.TICK, 68.0);
        scanTickWb.setFeature(Feature.TICKS_SINCE_SCAN, 2.0);
        IRobotSnapshot scanTickRobot = TestSnapshots.robotWithDebug(100, 200, 0, "Autopilot",
                new robocode.control.snapshot.IDebugProperty[] {
                        TestSnapshots.debugProperty("TICK", "68.0"),
                        TestSnapshots.debugProperty("TICKS_SINCE_SCAN", "3.0")
                });
        validator.validate(scanTickRobot, scanTickWb);

        Whiteboard breakTickWb = new Whiteboard();
        breakTickWb.setFeature(Feature.TICK, 69.0);
        breakTickWb.setFeature(Feature.THEIR_BREAK_TICK, 50.0);
        IRobotSnapshot breakTickRobot = TestSnapshots.robotWithDebug(100, 200, 0, "Autopilot",
                new robocode.control.snapshot.IDebugProperty[] {
                        TestSnapshots.debugProperty("TICK", "69.0"),
                        TestSnapshots.debugProperty("THEIR_BREAK_TICK", "51.0")
                });
        validator.validate(breakTickRobot, breakTickWb);

        assertEquals(2, validator.getMismatches());
        assertEquals(1, validator.getWaivedMismatches());
        assertEquals(1, validator.getUnexpectedMismatches());
    }
}

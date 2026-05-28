package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import robocode.control.snapshot.IDebugProperty;
import robocode.control.snapshot.IRobotSnapshot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DebugValidator — verifies it correctly compares
 * robot debug properties against pipeline whiteboard values.
 */
final class DebugValidatorTest {

    @Test
    void matchingValuesProduceNoMismatches() {
        DebugValidator v = new DebugValidator();
        Whiteboard wb = new Whiteboard();
        wb.setFeature(Feature.TICK, 42);
        wb.setFeature(Feature.OUR_X, 200.5);
        wb.setFeature(Feature.DISTANCE, 350.0);

        IRobotSnapshot robot = TestSnapshots.robotWithDebug(200, 300, 0, "cz.zamboch.Autopilot",
                new IDebugProperty[] {
                        TestSnapshots.debugProperty("TICK", "42.0"),
                        TestSnapshots.debugProperty("OUR_X", "200.5"),
                        TestSnapshots.debugProperty("DISTANCE", "350.0"),
                });

        v.validate(robot, wb);

        assertEquals(0, v.getMismatchCount());
        assertEquals(0, v.getNonBreakMismatchCount());
    }

    @Test
    void mismatchDetectedWhenValuesDiffer() {
        DebugValidator v = new DebugValidator();
        Whiteboard wb = new Whiteboard();
        wb.setFeature(Feature.TICK, 42);
        wb.setFeature(Feature.OUR_X, 200.5);

        IRobotSnapshot robot = TestSnapshots.robotWithDebug(200, 300, 0, "cz.zamboch.Autopilot",
                new IDebugProperty[] {
                        TestSnapshots.debugProperty("TICK", "42.0"),
                        TestSnapshots.debugProperty("OUR_X", "999.0"), // mismatch
                });

        v.validate(robot, wb);

        assertEquals(1, v.getMismatchCount());
        assertEquals(1, v.getNonBreakMismatchCount());
    }

    @Test
    void nullDebugPropertiesProducesNoMismatches() {
        // Simulates non-Autopilot robot (opponent) with no debug properties
        DebugValidator v = new DebugValidator();
        Whiteboard wb = new Whiteboard();
        wb.setFeature(Feature.TICK, 42);

        IRobotSnapshot robot = TestSnapshots.robotWithDebug(200, 300, 1, "sample.Crazy", null);

        v.validate(robot, wb);

        assertEquals(0, v.getMismatchCount());
    }

    @Test
    void emptyDebugPropertiesProducesNoMismatches() {
        DebugValidator v = new DebugValidator();
        Whiteboard wb = new Whiteboard();
        wb.setFeature(Feature.TICK, 42);

        IRobotSnapshot robot = TestSnapshots.robotWithDebug(200, 300, 1, "sample.Crazy",
                new IDebugProperty[0]);

        v.validate(robot, wb);

        assertEquals(0, v.getMismatchCount());
    }

    @Test
    void oneSideNaNIsMismatch() {
        DebugValidator v = new DebugValidator();
        Whiteboard wb = new Whiteboard();
        wb.setFeature(Feature.DISTANCE, 350.0);
        // OUR_X not set → NaN in whiteboard

        IRobotSnapshot robot = TestSnapshots.robotWithDebug(200, 300, 0, "cz.zamboch.Autopilot",
                new IDebugProperty[] {
                        TestSnapshots.debugProperty("DISTANCE", "350.0"),
                        TestSnapshots.debugProperty("OUR_X", "200.0"), // robot has value, pipeline NaN
                });

        v.validate(robot, wb);

        assertEquals(1, v.getMismatchCount(), "robot has value but pipeline is NaN → mismatch");
    }

    @Test
    void robotNaNPipelineHasValueIsMismatch() {
        DebugValidator v = new DebugValidator();
        Whiteboard wb = new Whiteboard();
        wb.setFeature(Feature.DISTANCE, 350.0);

        IRobotSnapshot robot = TestSnapshots.robotWithDebug(200, 300, 0, "cz.zamboch.Autopilot",
                new IDebugProperty[] {
                        TestSnapshots.debugProperty("DISTANCE", "NaN"), // robot NaN, pipeline has value
                });

        v.validate(robot, wb);

        assertEquals(1, v.getMismatchCount(), "robot is NaN but pipeline has value → mismatch");
    }

    @Test
    void bothNaNIsNotACounted() {
        DebugValidator v = new DebugValidator();
        Whiteboard wb = new Whiteboard();
        // OUR_X not set → NaN

        IRobotSnapshot robot = TestSnapshots.robotWithDebug(200, 300, 0, "cz.zamboch.Autopilot",
                new IDebugProperty[] {
                        TestSnapshots.debugProperty("OUR_X", "NaN"),
                });

        v.validate(robot, wb);

        assertEquals(0, v.getMismatchCount(), "both NaN → skip, not a mismatch");
    }

    @Test
    void breakFeaturesExcludedFromNonBreakCount() {
        DebugValidator v = new DebugValidator();
        Whiteboard wb = new Whiteboard();
        wb.setFeature(Feature.OUR_BREAK_GF, 0.5); // pipeline god-view
        wb.setFeature(Feature.TICK, 42);

        IRobotSnapshot robot = TestSnapshots.robotWithDebug(200, 300, 0, "cz.zamboch.Autopilot",
                new IDebugProperty[] {
                        TestSnapshots.debugProperty("TICK", "42.0"),
                        TestSnapshots.debugProperty("OUR_BREAK_GF", "0.3"), // robot stale → differs
                });

        v.validate(robot, wb);

        assertEquals(1, v.getMismatchCount(), "total includes break mismatch");
        assertEquals(0, v.getNonBreakMismatchCount(), "non-break excludes OUR_BREAK_*");
    }

    @Test
    void gfErrorTrackedForBreakGF() {
        DebugValidator v = new DebugValidator();
        Whiteboard wb = new Whiteboard();
        wb.setFeature(Feature.OUR_BREAK_GF, 0.5);

        IRobotSnapshot robot = TestSnapshots.robotWithDebug(200, 300, 0, "cz.zamboch.Autopilot",
                new IDebugProperty[] {
                        TestSnapshots.debugProperty("OUR_BREAK_GF", "0.3"),
                });

        v.validate(robot, wb);

        assertEquals(1, v.getGFErrorCount());
        assertEquals(0.2, v.getMeanAbsoluteGFError(), 1e-6);
    }

    @Test
    void unknownFeatureNameIsIgnored() {
        DebugValidator v = new DebugValidator();
        Whiteboard wb = new Whiteboard();

        IRobotSnapshot robot = TestSnapshots.robotWithDebug(200, 300, 0, "cz.zamboch.Autopilot",
                new IDebugProperty[] {
                        TestSnapshots.debugProperty("NOT_A_REAL_FEATURE", "123.0"),
                });

        v.validate(robot, wb);

        assertEquals(0, v.getMismatchCount());
    }

    @Test
    void opponentIdFeatureIsSkipped() {
        DebugValidator v = new DebugValidator();
        Whiteboard wb = new Whiteboard();

        IRobotSnapshot robot = TestSnapshots.robotWithDebug(200, 300, 0, "cz.zamboch.Autopilot",
                new IDebugProperty[] {
                        TestSnapshots.debugProperty("OPPONENT_ID", "sample.Crazy"),
                });

        v.validate(robot, wb);

        assertEquals(0, v.getMismatchCount(), "OPPONENT_ID is string, should be skipped");
    }
}

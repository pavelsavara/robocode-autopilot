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
        wb.setFeature(Feature.LAST_SCAN_TICK, 1.0);
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
        wb.setFeature(Feature.LAST_SCAN_TICK, 1.0);
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
        wb.setFeature(Feature.LAST_SCAN_TICK, 1.0);
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
}

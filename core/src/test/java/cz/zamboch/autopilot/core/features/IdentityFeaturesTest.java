package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.Whiteboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class IdentityFeaturesTest {

    private Whiteboard wb;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        wb.registerFeatures(new IdentityFeatures());
    }

    @Test
    void computesHashFromFullName() {
        wb.setStringFeature(Feature.OPPONENT_ID, "sample.Crazy");
        wb.process();
        int expected = RoboMath.fnv1a32("sample.Crazy");
        assertEquals(expected, (int) wb.getFeature(Feature.OPPONENT_ID_HASH));
    }

    @Test
    void stripsVersionSuffix() {
        wb.setStringFeature(Feature.OPPONENT_ID, "sample.Crazy (1)");
        wb.process();
        int expected = RoboMath.fnv1a32("sample.Crazy");
        assertEquals(expected, (int) wb.getFeature(Feature.OPPONENT_ID_HASH));
    }

    @Test
    void noNameSetDoesNothing() {
        wb.process();
        assertTrue(Double.isNaN(wb.getFeature(Feature.OPPONENT_ID_HASH)));
    }

    @Test
    void differentNamesProduceDifferentHashes() {
        wb.setStringFeature(Feature.OPPONENT_ID, "Bot1");
        wb.process();
        double hash1 = wb.getFeature(Feature.OPPONENT_ID_HASH);

        wb.clearFeatures();
        wb.setStringFeature(Feature.OPPONENT_ID, "Bot2");
        wb.process();
        double hash2 = wb.getFeature(Feature.OPPONENT_ID_HASH);

        assertNotEquals(hash1, hash2);
    }
}

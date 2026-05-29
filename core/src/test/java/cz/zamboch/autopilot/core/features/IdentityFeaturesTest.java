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
    void hashIsStickyAfterFirstComputation() {
        wb.setStringFeature(Feature.OPPONENT_ID, "Bot1");
        wb.process();
        double hash1 = wb.getFeature(Feature.OPPONENT_ID_HASH);

        // After clearFeatures, hash persists (opponent identity never changes)
        wb.clearFeatures();
        wb.process();
        assertEquals(hash1, wb.getFeature(Feature.OPPONENT_ID_HASH));
    }

    @Test
    void differentInstancesProduceDifferentHashes() {
        wb.setStringFeature(Feature.OPPONENT_ID, "Bot1");
        wb.process();
        double hash1 = wb.getFeature(Feature.OPPONENT_ID_HASH);

        // A fresh whiteboard with different name produces different hash
        Whiteboard wb2 = new Whiteboard();
        wb2.registerFeatures(new IdentityFeatures());
        wb2.setStringFeature(Feature.OPPONENT_ID, "Bot2");
        wb2.process();
        double hash2 = wb2.getFeature(Feature.OPPONENT_ID_HASH);

        assertNotEquals(hash1, hash2);
    }
}

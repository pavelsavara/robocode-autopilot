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
    void hashIsUnsetAfterRoundClearUntilNameSeenAgain() {
        wb.setStringFeature(Feature.OPPONENT_ID, "Bot1");
        wb.process();
        double hash1 = wb.getFeature(Feature.OPPONENT_ID_HASH);

        // After clearFeatures (round start), OPPONENT_ID is null again, so the hash
        // is left unset (NaN) — mirroring the live robot, which is re-instantiated
        // each round and has no identity until the first scan.
        wb.clearFeatures();
        wb.process();
        assertTrue(Double.isNaN(wb.getFeature(Feature.OPPONENT_ID_HASH)));

        // Once the opponent is scanned again, the hash returns (recomputed/cached).
        wb.setStringFeature(Feature.OPPONENT_ID, "Bot1");
        wb.process();
        assertEquals(hash1, wb.getFeature(Feature.OPPONENT_ID_HASH));
    }

    @Test
    void separateInstancesHaveIndependentCache() {
        wb.setStringFeature(Feature.OPPONENT_ID, "Bot1");
        wb.process();
        double hash1 = wb.getFeature(Feature.OPPONENT_ID_HASH);

        // A new IdentityFeatures instance does NOT inherit the hash (instance-level
        // cache)
        Whiteboard wb2 = new Whiteboard();
        wb2.registerFeatures(new IdentityFeatures());
        wb2.process();
        assertTrue(Double.isNaN(wb2.getFeature(Feature.OPPONENT_ID_HASH)));

        // But once its own opponent is set, it computes independently
        wb2.setStringFeature(Feature.OPPONENT_ID, "Bot2");
        wb2.process();
        double hash2 = wb2.getFeature(Feature.OPPONENT_ID_HASH);
        assertNotEquals(hash1, hash2);
    }
}

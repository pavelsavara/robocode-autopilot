package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * God-view tests for DangerOfflineFeatures (Tier 3).
 */
class DangerOfflineFeaturesTest {

    private Whiteboard wb;
    private DangerOfflineFeatures feat;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        feat = new DangerOfflineFeatures();
        wb.onRoundStart(0, 800, 600, 0.1, 10);
    }

    @Test
    void fileTypeIsTicks() {
        assertEquals(FileType.TICKS, feat.getFileType());
    }

    @Test
    void skipWhenInputsMissing() {
        feat.process(wb);
        assertFalse(wb.hasFeature(Feature.ESCAPE_ANGLE_COVERAGE));
    }

    @Test
    void coverageClampedToOne() {
        // huge eta with small mea → coverage explodes → clamp to 1
        wb.setFeature(Feature.OPPONENT_WAVE_ETA, 1000.0);
        wb.setFeature(Feature.MEA_FOR_OPPONENT_BULLET, 0.5);
        wb.setFeature(Feature.DISTANCE, 100.0);
        feat.process(wb);
        assertEquals(1.0, wb.getFeature(Feature.ESCAPE_ANGLE_COVERAGE), 1e-9);
    }

    @Test
    void coverageBounded() {
        wb.setFeature(Feature.OPPONENT_WAVE_ETA, 5.0);
        wb.setFeature(Feature.MEA_FOR_OPPONENT_BULLET, 0.6082);
        wb.setFeature(Feature.DISTANCE, 200.0);
        feat.process(wb);
        double cov = wb.getFeature(Feature.ESCAPE_ANGLE_COVERAGE);
        assertTrue(cov >= 0.0 && cov <= 1.0);
    }
}

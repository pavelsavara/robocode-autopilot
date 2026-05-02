package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * God-view tests for BattlefieldGeometryOfflineFeatures (Tier 2).
 */
class BattlefieldGeometryOfflineFeaturesTest {

    private Whiteboard wb;
    private BattlefieldGeometryOfflineFeatures feat;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        feat = new BattlefieldGeometryOfflineFeatures();
        wb.onRoundStart(0, 800, 600, 0.1, 10);
    }

    @Test
    void fileTypeIsTicks() {
        assertEquals(FileType.TICKS, feat.getFileType());
    }

    @Test
    void opponentAtCenterHasZeroCenterDistance() {
        wb.setOurState(0, 0, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan("Bot", 400, 300, 0, 0, 80);
        feat.process(wb);
        assertEquals(0.0, wb.getFeature(Feature.OPPONENT_CENTER_DISTANCE), 1e-9);
    }

    @Test
    void opponentAtCornerHasNearZeroProximity() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        // Position opponent at the (18,18) inset corner exactly
        wb.setOpponentScan("Bot", 18, 18, 0, 0, 80);
        feat.process(wb);
        assertEquals(0.0, wb.getFeature(Feature.OPPONENT_CORNER_PROXIMITY), 1e-9);
    }

    @Test
    void opponentAtCenterHasMaxCornerProximity() {
        wb.setOurState(0, 0, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan("Bot", 400, 300, 0, 0, 80);
        feat.process(wb);
        // Distance from center to (18,18) corner
        double expected = Math.hypot(400 - 18, 300 - 18);
        assertEquals(expected, wb.getFeature(Feature.OPPONENT_CORNER_PROXIMITY), 1e-9);
    }
}

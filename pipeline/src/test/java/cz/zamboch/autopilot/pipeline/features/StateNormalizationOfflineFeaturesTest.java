package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * God-view tests for StateNormalizationOfflineFeatures.
 */
class StateNormalizationOfflineFeaturesTest {

    private Whiteboard wb;
    private StateNormalizationOfflineFeatures feat;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        feat = new StateNormalizationOfflineFeatures();
        wb.onRoundStart(0, 800, 600, 0.1, 10);
    }

    @Test
    void energyRatioEqualEnergies() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 50, 0);
        wb.setTick(0);
        wb.setOpponentScan(500, 400, 0, 0, 50);
        wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, 0.0);

        feat.process(wb);
        assertEquals(0.5, wb.getFeature(Feature.ENERGY_RATIO), 0.001);
    }

    @Test
    void energyRatioWinning() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 90, 0);
        wb.setTick(0);
        wb.setOpponentScan(500, 400, 0, 0, 10);
        wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, 0.0);

        feat.process(wb);
        assertEquals(0.9, wb.getFeature(Feature.ENERGY_RATIO), 0.001);
    }

    @Test
    void energyRatioBothZero() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 0, 0);
        wb.setTick(0);
        wb.setOpponentScan(500, 400, 0, 0, 0);
        wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, 0.0);

        feat.process(wb);
        assertEquals(0.5, wb.getFeature(Feature.ENERGY_RATIO), 0.001);
    }

    @Test
    void ourLateralVelocityComputed() {
        // Moving at velocity 8 heading PI/2 (east),
        // opponent due north (bearing 0)
        wb.setOurState(400, 300, Math.PI / 2, 0, 0, 8, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan(400, 500, 0, 0, 80);
        wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, 0.0);

        feat.process(wb);
        // lateral = 8 * sin(PI/2 - 0) = 8 * sin(PI/2) = 8
        assertEquals(8.0, wb.getFeature(Feature.OUR_LATERAL_VELOCITY), 0.001);
    }

    @Test
    void ourDistToWallMinCenter() {
        // Center of 800x600 field: min is 300-18 from N or S
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan(500, 400, 0, 0, 80);
        wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, 0.0);

        feat.process(wb);
        // distN = 600-300-18=282, distS = 300-18=282, distE=800-400-18=382, distW=400-18=382
        assertEquals(282.0, wb.getFeature(Feature.OUR_DIST_TO_WALL_MIN), 0.001);
    }

    @Test
    void ourDistToWallMinCorner() {
        // Near south-west corner
        wb.setOurState(30, 25, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan(500, 400, 0, 0, 80);
        wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, 0.0);

        feat.process(wb);
        // distS = 25-18=7, distW = 30-18=12
        assertEquals(7.0, wb.getFeature(Feature.OUR_DIST_TO_WALL_MIN), 0.001);
    }

    @Test
    void skipWhenNoScan() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(5);
        feat.process(wb);
        assertFalse(wb.hasFeature(Feature.ENERGY_RATIO));
    }
}

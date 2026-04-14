package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * God-view tests for OpponentPredictionOfflineFeatures.
 */
class OpponentPredictionOfflineFeaturesTest {

    private Whiteboard wb;
    private OpponentPredictionOfflineFeatures feat;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        feat = new OpponentPredictionOfflineFeatures();
        wb.onRoundStart(0, 800, 600, 0.1, 10);
    }

    @Test
    void wallAheadDistanceHeadingNorth() {
        // Opponent at (400, 300) heading 0 (north = +Y in robocode)
        wb.setOurState(100, 100, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan(400, 300, 0, 0, 80);
        wb.setFeature(Feature.OPPONENT_FIRED, 0.0);
        wb.setFeature(Feature.OPPONENT_FIRE_POWER, 0.0);

        feat.process(wb);

        assertTrue(wb.hasFeature(Feature.OPPONENT_WALL_AHEAD_DISTANCE));
        // heading 0: dx=0, dy=1 → distance to north wall = 600-18-300 = 282
        assertEquals(282.0, wb.getFeature(Feature.OPPONENT_WALL_AHEAD_DISTANCE), 0.5);
    }

    @Test
    void wallAheadDistanceHeadingEast() {
        // heading PI/2 (east): dx=1, dy=0
        wb.setOurState(100, 100, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan(400, 300, Math.PI / 2, 0, 80);
        wb.setFeature(Feature.OPPONENT_FIRED, 0.0);
        wb.setFeature(Feature.OPPONENT_FIRE_POWER, 0.0);

        feat.process(wb);

        // east wall at bfW-18=782, dist = 782-400 = 382
        assertEquals(382.0, wb.getFeature(Feature.OPPONENT_WALL_AHEAD_DISTANCE), 0.5);
    }

    @Test
    void wallAheadDistanceDiagonal() {
        // heading PI/4 (NE): dx=sin(PI/4)≈0.707, dy=cos(PI/4)≈0.707
        wb.setOurState(100, 100, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan(400, 300, Math.PI / 4, 0, 80);
        wb.setFeature(Feature.OPPONENT_FIRED, 0.0);
        wb.setFeature(Feature.OPPONENT_FIRE_POWER, 0.0);

        feat.process(wb);

        assertTrue(wb.getFeature(Feature.OPPONENT_WALL_AHEAD_DISTANCE) > 0);
    }

    @Test
    void inferredGunHeatInitialCooldown() {
        // No fire detected yet — gun heat starts at 3.0 and cools
        wb.setOurState(100, 100, 0, 0, 0, 0, 100, 0);
        wb.setTick(10);
        wb.setOpponentScan(400, 300, 0, 0, 80);
        wb.setFeature(Feature.OPPONENT_FIRED, 0.0);
        wb.setFeature(Feature.OPPONENT_FIRE_POWER, 0.0);

        feat.process(wb);

        // 3.0 - 10*0.1 = 2.0
        assertEquals(2.0, wb.getFeature(Feature.OPPONENT_INFERRED_GUN_HEAT), 0.001);
    }

    @Test
    void inferredGunHeatAfterFire() {
        // Simulate a fire at tick 5 with power 2.0
        wb.setLastOpponentFire(5, 2.0);
        wb.setOurState(100, 100, 0, 0, 0, 0, 100, 0);
        wb.setTick(10);
        wb.setOpponentScan(400, 300, 0, 0, 80);
        wb.setFeature(Feature.OPPONENT_FIRED, 0.0);
        wb.setFeature(Feature.OPPONENT_FIRE_POWER, 0.0);

        feat.process(wb);

        // heat = 1 + 2.0/5 = 1.4, elapsed = 5, cooling = 0.1
        // 1.4 - 5*0.1 = 0.9
        assertEquals(0.9, wb.getFeature(Feature.OPPONENT_INFERRED_GUN_HEAT), 0.001);
    }

    @Test
    void inferredGunHeatCoolsToZero() {
        wb.setLastOpponentFire(0, 1.0);
        wb.setOurState(100, 100, 0, 0, 0, 0, 100, 0);
        wb.setTick(100);
        wb.setOpponentScan(400, 300, 0, 0, 80);
        wb.setFeature(Feature.OPPONENT_FIRED, 0.0);
        wb.setFeature(Feature.OPPONENT_FIRE_POWER, 0.0);

        feat.process(wb);

        // heat = 1 + 1/5 = 1.2, elapsed=100, 1.2 - 100*0.1 = -8.8 → max(0, ...) = 0
        assertEquals(0.0, wb.getFeature(Feature.OPPONENT_INFERRED_GUN_HEAT), 0.001);
    }

    @Test
    void skipWhenNoScan() {
        wb.setOurState(100, 100, 0, 0, 0, 0, 100, 0);
        wb.setTick(5);
        feat.process(wb);
        assertFalse(wb.hasFeature(Feature.OPPONENT_WALL_AHEAD_DISTANCE));
        assertFalse(wb.hasFeature(Feature.OPPONENT_INFERRED_GUN_HEAT));
    }
}

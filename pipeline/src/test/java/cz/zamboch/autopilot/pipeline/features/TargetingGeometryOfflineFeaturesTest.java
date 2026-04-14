package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * God-view tests for TargetingGeometryOfflineFeatures.
 */
class TargetingGeometryOfflineFeaturesTest {

    private Whiteboard wb;
    private TargetingGeometryOfflineFeatures feat;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        feat = new TargetingGeometryOfflineFeatures();
        wb.onRoundStart(0, 800, 600, 0.1, 10);
    }

    private void scanWithFeatures(double latVel, double distance, double vel) {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan(500, 400, 0, vel, 80);
        wb.setFeature(Feature.OPPONENT_LATERAL_VELOCITY, latVel);
        wb.setFeature(Feature.DISTANCE, distance);
        wb.setFeature(Feature.OPPONENT_VELOCITY, vel);
    }

    @Test
    void angularVelocityCorrect() {
        scanWithFeatures(4.0, 200.0, 4.0);
        feat.process(wb);
        assertEquals(4.0 / 200.0, wb.getFeature(Feature.OPPONENT_ANGULAR_VELOCITY), 0.0001);
    }

    @Test
    void angularVelocityZeroDistance() {
        scanWithFeatures(4.0, 0.0, 4.0);
        feat.process(wb);
        assertEquals(0.0, wb.getFeature(Feature.OPPONENT_ANGULAR_VELOCITY), 0.001);
    }

    @Test
    void maxTurnRateStationary() {
        scanWithFeatures(0, 300, 0);
        feat.process(wb);
        // At velocity 0: max turn = 10 degrees = 0.1745 rad
        assertEquals(Math.toRadians(10.0), wb.getFeature(Feature.OPPONENT_MAX_TURN_RATE), 0.0001);
    }

    @Test
    void maxTurnRateAtMaxSpeed() {
        scanWithFeatures(8, 300, 8);
        feat.process(wb);
        // At velocity 8: max turn = 10 - 0.75*8 = 4 degrees
        assertEquals(Math.toRadians(4.0), wb.getFeature(Feature.OPPONENT_MAX_TURN_RATE), 0.0001);
    }

    @Test
    void distanceNormInRange() {
        scanWithFeatures(0, 500, 0);
        feat.process(wb);
        double diagonal = Math.hypot(800, 600);
        assertEquals(500.0 / diagonal, wb.getFeature(Feature.DISTANCE_NORM), 0.0001);
        assertTrue(wb.getFeature(Feature.DISTANCE_NORM) >= 0);
        assertTrue(wb.getFeature(Feature.DISTANCE_NORM) <= 1);
    }

    @Test
    void skipWhenNoScan() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(5);
        feat.process(wb);
        assertFalse(wb.hasFeature(Feature.OPPONENT_ANGULAR_VELOCITY));
    }
}

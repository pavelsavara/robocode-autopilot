package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * God-view tests for MovementHistoryOfflineFeatures (Tier 2).
 */
class MovementHistoryOfflineFeaturesTest {

    private Whiteboard wb;
    private MovementHistoryOfflineFeatures feat;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        feat = new MovementHistoryOfflineFeatures();
        wb.onRoundStart(0, 800, 600, 0.1, 10);
    }

    /** Push a synthetic scan through the feature with given lat/raw velocity. */
    private void tick(long t, double latVel, double vel, int latDir) {
        wb.advanceTick();
        wb.setTick(t);
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setOpponentScan("Bot", 500, 400, 0, vel, 80);
        wb.setFeature(Feature.OPPONENT_LATERAL_VELOCITY, latVel);
        wb.setFeature(Feature.OPPONENT_VELOCITY, vel);
        wb.setFeature(Feature.OPPONENT_LATERAL_DIRECTION, latDir);
        wb.setFeature(Feature.OPPONENT_TIME_SINCE_DIRECTION_CHANGE, t);
        feat.process(wb);
    }

    @Test
    void fileTypeIsTicks() {
        assertEquals(FileType.TICKS, feat.getFileType());
    }

    @Test
    void rollingMeanOver10Scans() {
        for (int i = 0; i < 10; i++) {
            tick(i, 4.0, 4.0, 1);
        }
        assertEquals(4.0, wb.getFeature(Feature.OPPONENT_AVG_LATERAL_VELOCITY_10), 1e-9);
    }

    @Test
    void velocityVariabilityZeroForConstantVelocity() {
        for (int i = 0; i < 10; i++) {
            tick(i, 4.0, 4.0, 1);
        }
        assertEquals(0.0, wb.getFeature(Feature.OPPONENT_VELOCITY_VARIABILITY_10), 1e-9);
    }

    @Test
    void velocityVariabilityNonZeroForVariableVelocity() {
        tick(0, 0, 0, 0);
        tick(1, 0, 8, 1);
        tick(2, 0, 0, 0);
        tick(3, 0, 8, 1);
        assertTrue(wb.getFeature(Feature.OPPONENT_VELOCITY_VARIABILITY_10) > 0);
    }

    @Test
    void timeSinceVelocityChangeResetsOnLargeDelta() {
        tick(0, 0, 0, 0);
        // Steady at 0
        tick(1, 0, 0.2, 0);
        tick(2, 0, 0.1, 0);
        // First call set lastChange to tick 0 → at tick 2, time since = 2
        assertEquals(2L, (long) wb.getFeature(Feature.OPPONENT_TIME_SINCE_VELOCITY_CHANGE));
        // Now jump
        tick(3, 0, 8.0, 1);
        assertEquals(0L, (long) wb.getFeature(Feature.OPPONENT_TIME_SINCE_VELOCITY_CHANGE));
    }

    @Test
    void distanceSinceDirectionChangeAccumulates() {
        // Initial sample: ticksSinceDir=0 → resets to |vel|
        tick(0, 4, 4, 1);
        assertEquals(4.0, wb.getFeature(Feature.OPPONENT_DISTANCE_SINCE_DIRECTION_CHANGE), 1e-9);
        // Subsequent ticks accumulate (we override the time-since field to non-zero)
        wb.advanceTick();
        wb.setTick(1);
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setOpponentScan("Bot", 500, 400, 0, 4, 80);
        wb.setFeature(Feature.OPPONENT_LATERAL_VELOCITY, 4.0);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 4.0);
        wb.setFeature(Feature.OPPONENT_LATERAL_DIRECTION, 1);
        wb.setFeature(Feature.OPPONENT_TIME_SINCE_DIRECTION_CHANGE, 1L);
        feat.process(wb);
        assertEquals(8.0, wb.getFeature(Feature.OPPONENT_DISTANCE_SINCE_DIRECTION_CHANGE), 1e-9);
    }
}

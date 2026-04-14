package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * God-view tests for WaveTrackingOfflineFeatures.
 */
class WaveTrackingOfflineFeaturesTest {

    private Whiteboard wb;
    private WaveTrackingOfflineFeatures feat;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        feat = new WaveTrackingOfflineFeatures();
        wb.onRoundStart(0, 800, 600, 0.1, 10);
    }

    @Test
    void fileTypeIsWaves() {
        assertEquals(FileType.WAVES, feat.getFileType());
    }

    @Test
    void computesAllWaveFeaturesOnFire() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan(500, 400, 0, 0, 80);

        wb.setFeature(Feature.OPPONENT_FIRED, 1.0);
        wb.setFeature(Feature.OPPONENT_FIRE_POWER, 2.0);
        wb.setFeature(Feature.DISTANCE, 300.0);
        wb.setFeature(Feature.OUR_LATERAL_VELOCITY, 5.5);

        feat.process(wb);

        // bullet speed = 20 - 3*2 = 14
        assertEquals(2.0, wb.getFeature(Feature.WAVE_BULLET_POWER), 0.001);
        assertEquals(14.0, wb.getFeature(Feature.WAVE_BULLET_SPEED), 0.001);
        assertEquals(300.0, wb.getFeature(Feature.WAVE_FIRE_DISTANCE), 0.001);
        assertEquals(Math.asin(8.0 / 14.0), wb.getFeature(Feature.WAVE_MEA), 0.0001);
        assertEquals(300.0 / 14.0, wb.getFeature(Feature.WAVE_FLIGHT_TIME), 0.01);
        assertEquals(5.5, wb.getFeature(Feature.WAVE_LATERAL_VELOCITY_AT_FIRE), 0.001);
    }

    @Test
    void skipWhenNoFire() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan(500, 400, 0, 0, 80);
        wb.setFeature(Feature.OPPONENT_FIRED, 0.0);

        feat.process(wb);

        assertFalse(wb.hasFeature(Feature.WAVE_BULLET_POWER));
        assertFalse(wb.hasFeature(Feature.WAVE_BULLET_SPEED));
    }

    @Test
    void skipWhenFiredNotSet() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan(500, 400, 0, 0, 80);
        // OPPONENT_FIRED not set at all

        feat.process(wb);

        assertFalse(wb.hasFeature(Feature.WAVE_BULLET_SPEED));
    }

    @Test
    void bulletSpeedMinPower() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan(500, 400, 0, 0, 80);
        wb.setFeature(Feature.OPPONENT_FIRED, 1.0);
        wb.setFeature(Feature.OPPONENT_FIRE_POWER, 0.1);
        wb.setFeature(Feature.DISTANCE, 500.0);
        wb.setFeature(Feature.OUR_LATERAL_VELOCITY, 0.0);

        feat.process(wb);

        // speed = 20 - 3*0.1 = 19.7
        assertEquals(19.7, wb.getFeature(Feature.WAVE_BULLET_SPEED), 0.001);
    }

    @Test
    void bulletSpeedMaxPower() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan(500, 400, 0, 0, 80);
        wb.setFeature(Feature.OPPONENT_FIRED, 1.0);
        wb.setFeature(Feature.OPPONENT_FIRE_POWER, 3.0);
        wb.setFeature(Feature.DISTANCE, 500.0);
        wb.setFeature(Feature.OUR_LATERAL_VELOCITY, 0.0);

        feat.process(wb);

        // speed = 20 - 3*3 = 11
        assertEquals(11.0, wb.getFeature(Feature.WAVE_BULLET_SPEED), 0.001);
    }

    @Test
    void lateralVelocityDefaultsToZeroWhenMissing() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan(500, 400, 0, 0, 80);
        wb.setFeature(Feature.OPPONENT_FIRED, 1.0);
        wb.setFeature(Feature.OPPONENT_FIRE_POWER, 1.0);
        wb.setFeature(Feature.DISTANCE, 300.0);
        // OUR_LATERAL_VELOCITY not set

        feat.process(wb);

        assertEquals(0.0, wb.getFeature(Feature.WAVE_LATERAL_VELOCITY_AT_FIRE), 0.001);
    }

    @Test
    void meaRangeCheck() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan(500, 400, 0, 0, 80);
        wb.setFeature(Feature.OPPONENT_FIRED, 1.0);
        wb.setFeature(Feature.OPPONENT_FIRE_POWER, 1.5);
        wb.setFeature(Feature.DISTANCE, 400.0);
        wb.setFeature(Feature.OUR_LATERAL_VELOCITY, 3.0);

        feat.process(wb);

        double mea = wb.getFeature(Feature.WAVE_MEA);
        assertTrue(mea > 0, "MEA should be positive");
        assertTrue(mea < Math.PI / 2, "MEA should be < PI/2");
    }
}

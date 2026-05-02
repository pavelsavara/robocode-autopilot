package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * God-view tests for WaveOfflineFeatures (Tier 1).
 */
class WaveOfflineFeaturesTest {

    private Whiteboard wb;
    private WaveOfflineFeatures feat;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        feat = new WaveOfflineFeatures();
        wb.onRoundStart(0, 800, 600, 0.1, 10);
    }

    private void scan(double distance) {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(10);
        wb.setOpponentScan("Bot", 500, 400, 0, 0, 80);
        wb.setFeature(Feature.DISTANCE, distance);
    }

    @Test
    void fileTypeIsTicks() {
        assertEquals(FileType.TICKS, feat.getFileType());
    }

    @Test
    void usesDefaultPowerWhenWeHaveNotFired() {
        scan(300.0);
        feat.process(wb);
        // Default 2.0 power → speed = 14
        assertEquals(14.0, wb.getFeature(Feature.OUR_BULLET_SPEED), 1e-9);
        assertEquals(300.0 / 14.0, wb.getFeature(Feature.OUR_BULLET_TRAVEL_TIME), 1e-6);
        assertEquals(Math.asin(8.0 / 14.0), wb.getFeature(Feature.MEA_FOR_OUR_BULLET), 1e-9);
        // No fire yet → no ticks_since/wave fields
        assertFalse(wb.hasFeature(Feature.TICKS_SINCE_WE_FIRED));
        assertFalse(wb.hasFeature(Feature.OUR_WAVE_DISTANCE));
    }

    @Test
    void ourFirePopulatesWaveFields() {
        wb.setLastOurFire(5L, 1.0);
        scan(300.0);
        feat.process(wb);
        // power 1 → speed 17; tick=10, lastFire=5 → ticksSince=5; waveDist = 17*5 = 85
        assertEquals(17.0, wb.getFeature(Feature.OUR_BULLET_SPEED), 1e-9);
        assertEquals(5L, (long) wb.getFeature(Feature.TICKS_SINCE_WE_FIRED));
        assertEquals(85.0, wb.getFeature(Feature.OUR_WAVE_DISTANCE), 1e-9);
        assertEquals(300.0 - 85.0, wb.getFeature(Feature.OUR_WAVE_REMAINING), 1e-9);
    }

    @Test
    void opponentFirePopulatesOppFields() {
        wb.setLastOpponentFire(7L, 3.0);
        scan(220.0);
        feat.process(wb);
        // power 3 → speed 11; ticksSince=3; oppWaveDist = 11*3 = 33; remaining = 187; eta = 17
        assertEquals(11.0, wb.getFeature(Feature.OPPONENT_BULLET_SPEED), 1e-9);
        assertEquals(Math.asin(8.0 / 11.0), wb.getFeature(Feature.MEA_FOR_OPPONENT_BULLET), 1e-9);
        assertEquals(3L, (long) wb.getFeature(Feature.TICKS_SINCE_OPPONENT_FIRED));
        assertEquals(33.0, wb.getFeature(Feature.OPPONENT_WAVE_DISTANCE), 1e-9);
        assertEquals(187.0, wb.getFeature(Feature.OPPONENT_WAVE_REMAINING), 1e-9);
        assertEquals(17.0, wb.getFeature(Feature.OPPONENT_WAVE_ETA), 1e-9);
    }

    @Test
    void waveEtaClampedNonNegative() {
        wb.setLastOpponentFire(0L, 1.0);
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(50); // 50 ticks since fire
        wb.setOpponentScan("Bot", 500, 400, 0, 0, 80);
        wb.setFeature(Feature.DISTANCE, 50.0); // bullet has long since passed
        feat.process(wb);
        assertEquals(0.0, wb.getFeature(Feature.OPPONENT_WAVE_ETA), 1e-9);
        // remaining is negative — recorded as-is
        assertTrue(wb.getFeature(Feature.OPPONENT_WAVE_REMAINING) < 0);
    }

    @Test
    void skipsWhenNoScan() {
        // No scan available
        feat.process(wb);
        assertFalse(wb.hasFeature(Feature.OUR_BULLET_SPEED));
    }
}

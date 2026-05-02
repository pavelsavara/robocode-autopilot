package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * God-view tests for ScanCoverageOfflineFeatures (Tier 3).
 */
class ScanCoverageOfflineFeaturesTest {

    private Whiteboard wb;
    private ScanCoverageOfflineFeatures feat;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        feat = new ScanCoverageOfflineFeatures();
        wb.onRoundStart(0, 800, 600, 0.1, 10);
    }

    /** Simulate a tick with a scan. */
    private void scanTick(long t, double radarHeading) {
        wb.advanceTick();
        wb.setTick(t);
        wb.setOurState(400, 300, 0, 0, radarHeading, 0, 100, 0);
        wb.setOpponentScan("Bot", 500, 400, 0, 0, 80);
        feat.process(wb);
    }

    /** Simulate a tick without a scan. */
    private void noScanTick(long t, double radarHeading) {
        wb.advanceTick();
        wb.setTick(t);
        wb.setOurState(400, 300, 0, 0, radarHeading, 0, 100, 0);
        feat.process(wb);
    }

    @Test
    void fileTypeIsTicks() {
        assertEquals(FileType.TICKS, feat.getFileType());
    }

    @Test
    void radarArcAndDirectionCW() {
        wb.setTick(0);
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        feat.process(wb);
        // First tick: prev radar = NaN → sweep = 0
        assertEquals(0.0, wb.getFeature(Feature.SCAN_ARC_WIDTH), 1e-9);
        assertEquals(0, (int) wb.getFeature(Feature.RADAR_TURN_DIRECTION));
        // Second tick: radar moves CW by 0.5 rad
        scanTick(1, 0.5);
        assertEquals(0.5, wb.getFeature(Feature.SCAN_ARC_WIDTH), 1e-6);
        assertEquals(1, (int) wb.getFeature(Feature.RADAR_TURN_DIRECTION));
    }

    @Test
    void scanCoverage100PercentWhenAllTicksScanned() {
        for (int i = 0; i < 25; i++) {
            scanTick(i, i * 0.1);
        }
        assertEquals(1.0, wb.getFeature(Feature.SCAN_COVERAGE_20), 1e-9);
    }

    @Test
    void scanCoverageZeroWhenNoScans() {
        for (int i = 0; i < 25; i++) {
            noScanTick(i, 0);
        }
        assertEquals(0.0, wb.getFeature(Feature.SCAN_COVERAGE_20), 1e-9);
    }

    @Test
    void radarLockedAfterContinuousScanning() {
        // 6+ consecutive scans every tick → locked
        for (int i = 0; i < 8; i++) {
            scanTick(i, i * 0.1);
        }
        assertEquals(1.0, wb.getFeature(Feature.RADAR_LOCKED), 1e-9);
    }

    @Test
    void radarUnlockedWithGap() {
        scanTick(0, 0);
        // Big gap before next scan
        for (int i = 1; i < 10; i++) {
            noScanTick(i, 0);
        }
        scanTick(10, 0);
        assertEquals(0.0, wb.getFeature(Feature.RADAR_LOCKED), 1e-9);
    }

    @Test
    void ticksBetweenScansCorrect() {
        scanTick(0, 0);
        scanTick(3, 0);
        assertEquals(3L, (long) wb.getFeature(Feature.TICKS_BETWEEN_SCANS));
    }
}

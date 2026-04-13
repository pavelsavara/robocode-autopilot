package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TimingFeatures with hand-crafted Whiteboard state.
 */
class TimingFeaturesTest {

    private Whiteboard wb;
    private TimingFeatures processor;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        processor = new TimingFeatures();
        wb.onRoundStart(0, 800, 600, 0.1, 10);
    }

    @Test
    void recordsGunHeat() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 1.6);
        wb.setTick(0);

        processor.process(wb);

        assertTrue(wb.hasFeature(Feature.OUR_GUN_HEAT));
        assertEquals(1.6, wb.getFeature(Feature.OUR_GUN_HEAT), 0.001);
    }

    @Test
    void recordsZeroGunHeat() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0.0);
        wb.setTick(0);

        processor.process(wb);

        assertEquals(0.0, wb.getFeature(Feature.OUR_GUN_HEAT), 0.001);
    }

    @Test
    void ticksSinceScanOnScanTick() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(5);
        wb.setOpponentScan(600, 300, 0, 0, 100);

        processor.process(wb);

        assertTrue(wb.hasFeature(Feature.TICKS_SINCE_SCAN));
        assertEquals(0.0, wb.getFeature(Feature.TICKS_SINCE_SCAN), 0.001);
    }

    @Test
    void ticksSinceScanAfterGap() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);

        // Scan at tick 3
        wb.setTick(3);
        wb.setOpponentScan(600, 300, 0, 0, 100);
        processor.process(wb);

        // Advance to tick 8 (5 ticks later, no scan)
        wb.advanceTick(); // 4
        wb.advanceTick(); // 5
        wb.advanceTick(); // 6
        wb.advanceTick(); // 7
        wb.advanceTick(); // 8

        processor.process(wb);

        assertEquals(5.0, wb.getFeature(Feature.TICKS_SINCE_SCAN), 0.001);
    }

    @Test
    void ticksSinceScanBeforeFirstScan() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(3);

        processor.process(wb);

        assertTrue(wb.hasFeature(Feature.TICKS_SINCE_SCAN));
        // No scan yet — should be tick+1 = 4
        assertEquals(4.0, wb.getFeature(Feature.TICKS_SINCE_SCAN), 0.001);
    }

    @Test
    void gunHeatUpdatesOnNewState() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 3.0);
        wb.setTick(0);
        processor.process(wb);
        assertEquals(3.0, wb.getFeature(Feature.OUR_GUN_HEAT), 0.001);

        wb.advanceTick();
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 2.8);
        processor.process(wb);
        assertEquals(2.8, wb.getFeature(Feature.OUR_GUN_HEAT), 0.001);
    }
}

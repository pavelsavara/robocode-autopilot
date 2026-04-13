package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EnergyFeatures with hand-crafted Whiteboard state.
 */
class EnergyFeaturesTest {

    private Whiteboard wb;
    private EnergyFeatures processor;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        processor = new EnergyFeatures();
        wb.onRoundStart(0, 800, 600, 0.1, 10);
    }

    @Test
    void recordsOpponentEnergy() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan(600, 300, 0, 0, 85.5);

        processor.process(wb);

        assertTrue(wb.hasFeature(Feature.OPPONENT_ENERGY));
        assertEquals(85.5, wb.getFeature(Feature.OPPONENT_ENERGY), 0.001);
    }

    @Test
    void detectsFireOnEnergyDrop() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);

        // First scan at tick 0 — sets the baseline
        wb.setTick(0);
        wb.setOpponentScan(600, 300, 0, 0, 100);
        processor.process(wb);

        // No fire on first scan (prevEnergy was 0)
        assertEquals(0.0, wb.getFeature(Feature.OPPONENT_FIRED), 0.001);

        // Advance to tick 1, clear features
        wb.advanceTick();

        // Second scan — energy dropped by 2.0 (fire detected)
        wb.setOpponentScan(600, 300, 0, 0, 98.0);
        processor.process(wb);

        assertTrue(wb.hasFeature(Feature.OPPONENT_FIRED));
        assertEquals(1.0, wb.getFeature(Feature.OPPONENT_FIRED), 0.001);
        assertEquals(2.0, wb.getFeature(Feature.OPPONENT_FIRE_POWER), 0.001);
    }

    @Test
    void doesNotDetectFireOnSmallDrop() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);

        wb.setTick(0);
        wb.setOpponentScan(600, 300, 0, 0, 100);
        processor.process(wb);

        wb.advanceTick();

        // Energy drop of 0.05 — below MIN_BULLET_POWER
        wb.setOpponentScan(600, 300, 0, 0, 99.95);
        processor.process(wb);

        assertEquals(0.0, wb.getFeature(Feature.OPPONENT_FIRED), 0.001);
    }

    @Test
    void doesNotDetectFireOnLargeDrop() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);

        wb.setTick(0);
        wb.setOpponentScan(600, 300, 0, 0, 100);
        processor.process(wb);

        wb.advanceTick();

        // Energy drop of 5.0 — above MAX_BULLET_POWER (probably wall hit or our bullet)
        wb.setOpponentScan(600, 300, 0, 0, 95.0);
        processor.process(wb);

        assertEquals(0.0, wb.getFeature(Feature.OPPONENT_FIRED), 0.001);
    }

    @Test
    void doesNotDetectFireWhenWeHitOpponent() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);

        wb.setTick(0);
        wb.setOpponentScan(600, 300, 0, 0, 100);
        processor.process(wb);

        wb.advanceTick();

        // Our bullet hit this tick
        wb.setWeHitOpponentThisTick(true);
        wb.setOpponentScan(600, 300, 0, 0, 98.0);
        processor.process(wb);

        assertEquals(0.0, wb.getFeature(Feature.OPPONENT_FIRED), 0.001);
    }

    @Test
    void doesNotDetectFireWhenOpponentHitWall() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);

        wb.setTick(0);
        wb.setOpponentScan(600, 300, 0, 0, 100);
        processor.process(wb);

        wb.advanceTick();

        // Opponent hit wall this tick
        wb.setOpponentHitWallThisTick(true);
        wb.setOpponentScan(600, 300, 0, 0, 98.0);
        processor.process(wb);

        assertEquals(0.0, wb.getFeature(Feature.OPPONENT_FIRED), 0.001);
    }

    @Test
    void noFireDetectionOnEnergyGain() {
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);

        wb.setTick(0);
        wb.setOpponentScan(600, 300, 0, 0, 100);
        processor.process(wb);

        wb.advanceTick();

        // Energy went UP (e.g. opponent's bullet hit us, gaining energy back)
        wb.setOpponentScan(600, 300, 0, 0, 103.0);
        processor.process(wb);

        assertEquals(0.0, wb.getFeature(Feature.OPPONENT_FIRED), 0.001);
    }
}

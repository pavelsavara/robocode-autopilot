package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * God-view tests for TargetingOfflineFeatures (Tier 1).
 */
class TargetingOfflineFeaturesTest {

    private Whiteboard wb;
    private TargetingOfflineFeatures feat;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        feat = new TargetingOfflineFeatures();
        wb.onRoundStart(0, 800, 600, 0.1, 10);
    }

    private void setUpScan() {
        // Place opponent due east (bearing = PI/2 in robocode atan2(dx,dy)).
        // Our gun heading aligned north (=0).
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan("Bot", 600, 300, 0, 0, 80);
        wb.setFeature(Feature.DISTANCE, 200.0);
        wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, Math.PI / 2);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0.0);
        wb.setFeature(Feature.OPPONENT_LATERAL_DIRECTION, 0);
        wb.setFeature(Feature.OUR_LATERAL_VELOCITY, 0.0);
    }

    @Test
    void fileTypeIsTicks() {
        assertEquals(FileType.TICKS, feat.getFileType());
    }

    @Test
    void stationaryOpponentLinearOffsetIsZero() {
        setUpScan();
        feat.process(wb);
        assertEquals(0.0, wb.getFeature(Feature.LINEAR_TARGET_OFFSET), 1e-9);
        // Linear angle equals bearing
        assertEquals(Math.PI / 2, wb.getFeature(Feature.LINEAR_TARGET_ANGLE), 1e-9);
    }

    @Test
    void gfBearingOffsetMatchesGunVsBearing() {
        setUpScan();
        feat.process(wb);
        // gun heading 0, bearing PI/2 → bearingOffset = PI/2 (relative)
        assertEquals(Math.PI / 2, wb.getFeature(Feature.GF_BEARING_OFFSET), 1e-9);
    }

    @Test
    void gfClampedToOnePlusMinus() {
        setUpScan();
        // bearingOffset PI/2 with latDir 0 → uses dir=1, GF = PI/2 / mea > 1 → clamped to 1
        feat.process(wb);
        assertEquals(1.0, wb.getFeature(Feature.GF_CURRENT_AT_POWER_1), 1e-9);
        assertEquals(1.0, wb.getFeature(Feature.GF_CURRENT_AT_POWER_2), 1e-9);
    }

    @Test
    void opponentGuessFactorFromOurLateralVelocity() {
        setUpScan();
        wb.setFeature(Feature.OUR_LATERAL_VELOCITY, 4.0); // half max
        feat.process(wb);
        assertEquals(0.5, wb.getFeature(Feature.OPPONENT_GUESS_FACTOR), 1e-9);
    }

    @Test
    void opponentGuessFactorClampedAtMaxVelocity() {
        setUpScan();
        wb.setFeature(Feature.OUR_LATERAL_VELOCITY, 9.0); // > MAX_VELOCITY
        feat.process(wb);
        assertEquals(1.0, wb.getFeature(Feature.OPPONENT_GUESS_FACTOR), 1e-9);
    }

    @Test
    void linearOffsetMatchesLawOfSinesForMovingOpponent() {
        // Opponent heading north (0), velocity +4, bearing east (PI/2).
        // sinArg = 4/14 * sin(0 - PI/2) = 4/14 * (-1) = -2/7
        // linOffset = asin(-2/7)
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan("Bot", 600, 300, 0, 4, 80);
        wb.setFeature(Feature.DISTANCE, 200.0);
        wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, Math.PI / 2);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 4.0);
        wb.setFeature(Feature.OPPONENT_LATERAL_DIRECTION, 1);
        wb.setFeature(Feature.OUR_LATERAL_VELOCITY, 0.0);
        feat.process(wb);
        double expected = Math.asin(-2.0 / 7.0);
        assertEquals(expected, wb.getFeature(Feature.LINEAR_TARGET_OFFSET), 1e-9);
    }

    @Test
    void circularTargetAngleProducesValue() {
        setUpScan();
        feat.process(wb);
        // For stationary opponent the iteration converges quickly to bearing (eastward).
        assertEquals(Math.PI / 2, wb.getFeature(Feature.CIRCULAR_TARGET_ANGLE), 1e-3);
        assertEquals(0.0, wb.getFeature(Feature.CIRCULAR_TARGET_OFFSET), 1e-3);
    }
}

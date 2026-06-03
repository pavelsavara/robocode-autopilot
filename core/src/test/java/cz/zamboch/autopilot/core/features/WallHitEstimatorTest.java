package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class WallHitEstimatorTest {

    private Whiteboard wb;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        wb.registerFeatures(new WallHitEstimator(800, 600));
    }

    @Test
    void diagonalWallStopUsesFullPreviousVelocityForDamage() {
        wb.setFeature(Feature.TICK, 50);
        wb.setFeature(Feature.SCAN_TICK, 50);
        wb.setFeature(Feature.OPPONENT_X, 106.99118481021449);
        wb.setFeature(Feature.OPPONENT_Y, 18.086596414480596);
        wb.setFeature(Feature.OPPONENT_HEADING, 1.1268072484329965);
        wb.setFeature(Feature.OPPONENT_VELOCITY, -8.0);
        wb.process();

        wb.setFeature(Feature.TICK, 51);
        wb.setFeature(Feature.SCAN_TICK, 51);
        wb.setFeature(Feature.OPPONENT_X, 106.77065538757341);
        wb.setFeature(Feature.OPPONENT_Y, 18.000000000000057);
        wb.setFeature(Feature.OPPONENT_HEADING, 1.1966204185127698);
        wb.setFeature(Feature.OPPONENT_VELOCITY, 0.0);
        wb.process();

        assertEquals(3.0, wb.getFeature(Feature.OPPONENT_WALL_HIT_DAMAGE), 1e-9);
    }
}
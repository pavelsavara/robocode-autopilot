package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import java.util.List;
import cz.zamboch.autopilot.core.Transformer;
import cz.zamboch.autopilot.core.Whiteboard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Main's factory methods and transformer configuration.
 */
class MainTest {

    @Test
    void createTransformerRegistersAllFeatures() {
        Transformer t = Main.createTransformer();

        // Should have 10 registered features (4 offline subclasses + 6 pipeline-only)
        List<IInGameFeatures> features = t.getFeatures();
        assertEquals(10, features.size());
    }

    @Test
    void createTransformerProducesAllTickFeatures() {
        Transformer t = Main.createTransformer();

        Whiteboard wb = new Whiteboard();
        wb.onRoundStart(0, 800, 600, 0.1, 10);
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 2.0);
        wb.setTick(0);
        wb.setOpponentScan(500, 400, Math.PI, -3.5, 95);

        t.process(wb);

        // All spatial features should be set
        assertTrue(wb.hasFeature(Feature.DISTANCE));
        assertTrue(wb.hasFeature(Feature.BEARING_TO_OPPONENT_ABS));
        assertTrue(wb.hasFeature(Feature.OPPONENT_DIST_TO_WALL_MIN));

        // Movement features
        assertTrue(wb.hasFeature(Feature.OPPONENT_VELOCITY));
        assertTrue(wb.hasFeature(Feature.OPPONENT_LATERAL_VELOCITY));
        assertTrue(wb.hasFeature(Feature.OPPONENT_ADVANCING_VELOCITY));

        // Energy features
        assertTrue(wb.hasFeature(Feature.OPPONENT_ENERGY));
        assertTrue(wb.hasFeature(Feature.OPPONENT_FIRED));

        // Timing
        assertTrue(wb.hasFeature(Feature.TICKS_SINCE_SCAN));
        assertTrue(wb.hasFeature(Feature.OUR_GUN_HEAT));

        // State normalization
        assertTrue(wb.hasFeature(Feature.ENERGY_RATIO));
        assertTrue(wb.hasFeature(Feature.OUR_LATERAL_VELOCITY));

        // Opponent prediction
        assertTrue(wb.hasFeature(Feature.OPPONENT_WALL_AHEAD_DISTANCE));
        assertTrue(wb.hasFeature(Feature.OPPONENT_INFERRED_GUN_HEAT));
    }

    @Test
    void createTransformerProducesSameFeatureSet() {
        Transformer t1 = Main.createTransformer();
        Transformer t2 = Main.createTransformer();

        List<IInGameFeatures> f1 = t1.getFeatures();
        List<IInGameFeatures> f2 = t2.getFeatures();

        assertEquals(f1.size(), f2.size());

        // Same set of classes (order may differ due to topological sort)
        java.util.Set<Class<?>> classes1 = new java.util.HashSet<Class<?>>();
        java.util.Set<Class<?>> classes2 = new java.util.HashSet<Class<?>>();
        for (IInGameFeatures f : f1) classes1.add(f.getClass());
        for (IInGameFeatures f : f2) classes2.add(f.getClass());
        assertEquals(classes1, classes2);
    }
}

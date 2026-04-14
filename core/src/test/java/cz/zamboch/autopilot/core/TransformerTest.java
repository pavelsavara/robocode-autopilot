package cz.zamboch.autopilot.core;

import cz.zamboch.autopilot.core.features.EnergyFeatures;
import cz.zamboch.autopilot.core.features.MovementFeatures;
import cz.zamboch.autopilot.core.features.SpatialFeatures;
import cz.zamboch.autopilot.core.features.TimingFeatures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Transformer: dependency resolution, process ordering, and error handling.
 */
class TransformerTest {

    @Test
    void resolvesAndProcessesInDependencyOrder() {
        Transformer t = new Transformer();
        // Movement depends on SpatialFeatures (BEARING_TO_OPPONENT_ABS)
        t.register(new MovementFeatures());
        t.register(new SpatialFeatures());
        t.resolveDependencies();

        Whiteboard wb = new Whiteboard();
        wb.onRoundStart(0, 800, 600, 0.1, 10);
        wb.setOurState(100, 100, 0, 0, 0, 0, 100, 0);
        wb.setTick(0);
        wb.setOpponentScan(400, 500, Math.PI / 4, 5.0, 80);

        t.process(wb);

        // Spatial features should be computed
        assertTrue(wb.hasFeature(Feature.DISTANCE));
        assertTrue(wb.hasFeature(Feature.BEARING_TO_OPPONENT_ABS));
        // Movement features should also be computed (depends on Spatial)
        assertTrue(wb.hasFeature(Feature.OPPONENT_VELOCITY));
        assertTrue(wb.hasFeature(Feature.OPPONENT_LATERAL_VELOCITY));
    }

    @Test
    void throwsOnCircularDependency() {
        Transformer t = new Transformer();
        // Create a circular dependency with fake features
        t.register(new IInGameFeatures() {
            public Feature[] getOutputFeatures() { return new Feature[]{Feature.DISTANCE}; }
            public Feature[] getDependencies() { return new Feature[]{Feature.OPPONENT_VELOCITY}; }
            public void process(Whiteboard wb) {}
        });
        t.register(new IInGameFeatures() {
            public Feature[] getOutputFeatures() { return new Feature[]{Feature.OPPONENT_VELOCITY}; }
            public Feature[] getDependencies() { return new Feature[]{Feature.DISTANCE}; }
            public void process(Whiteboard wb) {}
        });

        assertThrows(IllegalStateException.class, t::resolveDependencies);
    }

    @Test
    void throwsIfProcessCalledBeforeResolve() {
        Transformer t = new Transformer();
        t.register(new TimingFeatures());
        Whiteboard wb = new Whiteboard();

        assertThrows(IllegalStateException.class, () -> t.process(wb));
    }

    @Test
    void handlesNoDependencies() {
        Transformer t = new Transformer();
        t.register(new TimingFeatures());
        t.register(new EnergyFeatures());
        t.resolveDependencies();

        Whiteboard wb = new Whiteboard();
        wb.onRoundStart(0, 800, 600, 0.1, 10);
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 1.5);
        wb.setTick(0);
        wb.setOpponentScan(500, 400, 0, 0, 90);

        t.process(wb);

        assertTrue(wb.hasFeature(Feature.OUR_GUN_HEAT));
        assertTrue(wb.hasFeature(Feature.OPPONENT_ENERGY));
    }

    @Test
    void getFeaturesReturnsRegisteredBeforeResolve() {
        Transformer t = new Transformer();
        TimingFeatures tf = new TimingFeatures();
        t.register(tf);

        assertEquals(1, t.getFeatures().size());
        assertSame(tf, t.getFeatures().get(0));
    }

    @Test
    void getFeaturesReturnsSortedAfterResolve() {
        Transformer t = new Transformer();
        MovementFeatures mf = new MovementFeatures();
        SpatialFeatures sf = new SpatialFeatures();
        t.register(mf); // depends on sf
        t.register(sf);
        t.resolveDependencies();

        // Spatial should come before Movement in sorted order
        assertEquals(2, t.getFeatures().size());
        assertSame(sf, t.getFeatures().get(0));
        assertSame(mf, t.getFeatures().get(1));
    }

    @Test
    void fullChainSpatialMovementEnergy() {
        Transformer t = new Transformer();
        t.register(new SpatialFeatures());
        t.register(new MovementFeatures());
        t.register(new TimingFeatures());
        t.register(new EnergyFeatures());
        t.resolveDependencies();

        Whiteboard wb = new Whiteboard();
        wb.onRoundStart(0, 800, 600, 0.1, 10);
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 2.0);

        // First scan at tick 0 (establishes prev heading)
        wb.setTick(0);
        wb.setOpponentScan(500, 400, Math.PI, -3.5, 95);
        t.process(wb);
        wb.advanceTick();

        // Second scan at tick 1 (consecutive — enables heading delta)
        wb.setTick(1);
        wb.setOurState(400, 300, 0, 0, 0, 0, 100, 1.9);
        wb.setOpponentScan(500, 400, Math.PI + 0.1, -3.5, 95);
        t.process(wb);

        // All 12 features should be set
        assertTrue(wb.hasFeature(Feature.DISTANCE));
        assertTrue(wb.hasFeature(Feature.BEARING_TO_OPPONENT_ABS));
        assertTrue(wb.hasFeature(Feature.OPPONENT_DIST_TO_WALL_MIN));
        assertTrue(wb.hasFeature(Feature.OPPONENT_VELOCITY));
        assertTrue(wb.hasFeature(Feature.OPPONENT_LATERAL_VELOCITY));
        assertTrue(wb.hasFeature(Feature.OPPONENT_ADVANCING_VELOCITY));
        assertTrue(wb.hasFeature(Feature.OPPONENT_HEADING_DELTA));
        assertTrue(wb.hasFeature(Feature.OUR_GUN_HEAT));
        assertTrue(wb.hasFeature(Feature.TICKS_SINCE_SCAN));
        assertTrue(wb.hasFeature(Feature.OPPONENT_ENERGY));
        assertTrue(wb.hasFeature(Feature.OPPONENT_FIRED));
        assertTrue(wb.hasFeature(Feature.OPPONENT_FIRE_POWER));
    }
}

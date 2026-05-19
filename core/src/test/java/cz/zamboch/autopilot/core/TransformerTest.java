package cz.zamboch.autopilot.core;

import cz.zamboch.autopilot.core.features.EnergyFeatures;
import cz.zamboch.autopilot.core.features.MovementFeatures;
import cz.zamboch.autopilot.core.features.SpatialFeatures;
import cz.zamboch.autopilot.core.features.TimingFeatures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class TransformerTest {

    @Test
    void resolvesWithoutCycle() {
        Transformer t = new Transformer();
        t.register(new SpatialFeatures());
        t.register(new MovementFeatures());
        t.register(new EnergyFeatures());
        t.register(new TimingFeatures());
        t.resolveDependencies();

        IInGameFeatures[] order = t.getExecutionOrder();
        assertEquals(4, order.length);

        // MovementFeatures depends on SpatialFeatures output
        // (OPPONENT_BEARING_ABSOLUTE)
        int spatialIdx = -1, movementIdx = -1;
        for (int i = 0; i < order.length; i++) {
            if (order[i] instanceof SpatialFeatures)
                spatialIdx = i;
            if (order[i] instanceof MovementFeatures)
                movementIdx = i;
        }
        assertTrue(spatialIdx < movementIdx,
                "SpatialFeatures must execute before MovementFeatures");
    }

    @Test
    void processWritesFeatures() {
        Transformer t = new Transformer();
        t.register(new SpatialFeatures());
        t.register(new EnergyFeatures());
        t.register(new TimingFeatures());

        Whiteboard wb = new Whiteboard();
        wb.setTick(42);
        wb.setOurPosition(100, 200);
        wb.setOurEnergy(85.0);
        wb.setGunHeat(1.5);
        wb.clearFeatures();

        t.process(wb);

        assertEquals(100.0, wb.getFeature(Feature.OUR_X));
        assertEquals(200.0, wb.getFeature(Feature.OUR_Y));
        assertEquals(85.0, wb.getFeature(Feature.OUR_ENERGY));
        assertEquals(42.0, wb.getFeature(Feature.TICK));
        assertEquals(1.5, wb.getFeature(Feature.GUN_HEAT));
    }
}

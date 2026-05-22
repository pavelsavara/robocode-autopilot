package cz.zamboch.autopilot.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ModelSelectorTest {

    /** A simple stub model that always predicts a fixed GF. */
    private static final class FixedModel implements IOnlineModel {
        private final String name;
        private final double fixedGf;
        int updateCount;

        FixedModel(String name, double fixedGf) {
            this.name = name;
            this.fixedGf = fixedGf;
        }

        @Override
        public double predict(Whiteboard wb, int slot) {
            return fixedGf;
        }

        @Override
        public void update(Whiteboard wb, int slot, double breakGf) {
            updateCount++;
        }

        @Override
        public String getName() {
            return name;
        }
    }

    @Test
    void singleModelAlwaysSelected() {
        FixedModel m = new FixedModel("Only", 0.5);
        ModelSelector sel = new ModelSelector(m);

        assertEquals(1, sel.getModelCount());
        assertEquals("Only", sel.getBestModelName());
        assertEquals(0.5, sel.predict(null, 0), 1e-9);
    }

    @Test
    void updateRecordsErrorAndUpdatesAllModels() {
        FixedModel m1 = new FixedModel("A", 0.3);
        FixedModel m2 = new FixedModel("B", -0.2);
        ModelSelector sel = new ModelSelector(m1, m2);

        // breakGf = 0.3 → m1 error=0, m2 error=0.5
        sel.update(null, 0, 0.3);

        assertEquals(1, m1.updateCount);
        assertEquals(1, m2.updateCount);
        assertEquals(0.0, sel.getAverageError(0), 1e-9);
        assertEquals(0.5, sel.getAverageError(1), 1e-9);
    }

    @Test
    void bestModelHasLowestRegret() {
        FixedModel m1 = new FixedModel("Far", 0.8);   // always predicts 0.8
        FixedModel m2 = new FixedModel("Close", 0.1); // always predicts 0.1

        ModelSelector sel = new ModelSelector(m1, m2);

        // Actual break GFs near 0.1 → m2 should win
        for (int i = 0; i < 10; i++) {
            sel.update(null, 0, 0.1 + (i * 0.01));
        }

        assertEquals("Close", sel.getBestModelName());
        assertTrue(sel.getAverageError(1) < sel.getAverageError(0));
    }

    @Test
    void predictDelegatesToBestModel() {
        FixedModel good = new FixedModel("Good", 0.2);
        FixedModel bad = new FixedModel("Bad", 0.9);
        ModelSelector sel = new ModelSelector(bad, good);

        // Break at 0.2 makes "Good" (index 1) better
        for (int i = 0; i < 5; i++) {
            sel.update(null, 0, 0.2);
        }

        assertEquals("Good", sel.getBestModelName());
        assertEquals(0.2, sel.predict(null, 0), 1e-9);
    }

    @Test
    void predictForAimUsesVcsStore() {
        VcsStore vcs = new VcsStore();
        // Load some data: middle distance segment, positive latVel segment
        int distSeg = GuessFactor.distanceSegment(400);
        int latVelSeg = GuessFactor.lateralVelocitySegment(5.0);
        int targetBin = 20;
        for (int i = 0; i < 10; i++) {
            vcs.increment(distSeg, latVelSeg, targetBin);
        }

        ModelSelector sel = new ModelSelector(vcs);
        double predicted = sel.predictForAim(400, 5.0);
        double expected = GuessFactor.binIndexToGf(targetBin, GuessFactor.NUM_BINS);
        assertEquals(expected, predicted, 1e-9);
    }

    @Test
    void recordPipelineUpdateTracksRegret() {
        FixedModel m1 = new FixedModel("A", 0.0); // not VcsStore → predicted=0.0
        ModelSelector sel = new ModelSelector(m1);

        // Break at 0.5 → error should be 0.5
        sel.recordPipelineUpdate(0, 0, 0.5);
        assertEquals(0.5, sel.getAverageError(0), 1e-9);
    }

    @Test
    void requiresAtLeastOneModel() {
        assertThrows(IllegalArgumentException.class, () -> new ModelSelector());
    }
}

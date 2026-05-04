package cz.zamboch.trivial;

import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.predictors.IGfTargetingPredictor;

import java.util.Arrays;

/**
 * Trivial GF targeting predictor — uniform distribution (1/61 per bin).
 */
public final class TrivialGfTargeting implements IGfTargetingPredictor {

    @Override
    public double[] predict(Whiteboard wb) {
        double[] uniform = new double[61];
        Arrays.fill(uniform, 1.0 / 61);
        return uniform;
    }

    @Override
    public double confidence(Whiteboard wb) {
        return 0.0;
    }

    @Override
    public String name() {
        return "trivial-uniform";
    }
}

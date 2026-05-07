package cz.zamboch.distilled;

import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.predictors.IGfTargetingPredictor;

/**
 * Distilled MLP GF targeting predictor skeleton.
 * Phase 8 will generate {@code MlpGfTargetingModel.java} with weight
 * matrices and the forward pass (ReLU hidden layers + softmax output).
 *
 * <p>Architecture: 16 input features → 128 → 128 → 64 → 61 output bins.
 * Pre-allocates intermediate buffers for zero per-tick allocation.</p>
 *
 * <p>Until the generated model is available, produces a uniform
 * distribution (no aiming preference).</p>
 */
public final class MlpGfTargeting implements IGfTargetingPredictor {

    /** Pre-allocated output buffer. */
    private final double[] output = new double[NUM_BINS];

    @Override
    public double[] predict(Whiteboard wb) {
        // Phase 8: extract 16 features, call MlpGfTargetingModel.forward(input, output)
        // For now, return uniform distribution
        double uniform = 1.0 / NUM_BINS;
        for (int i = 0; i < NUM_BINS; i++) {
            output[i] = uniform;
        }
        return output;
    }

    @Override
    public void predictInto(Whiteboard wb, double[] out) {
        // Phase 8: zero-allocation forward pass directly into out[]
        double uniform = 1.0 / NUM_BINS;
        for (int i = 0; i < out.length && i < NUM_BINS; i++) {
            out[i] = uniform;
        }
    }

    @Override
    public double confidence(Whiteboard wb) {
        return 0.0;
    }

    @Override
    public String name() {
        return "mlp-gf-targeting";
    }
}

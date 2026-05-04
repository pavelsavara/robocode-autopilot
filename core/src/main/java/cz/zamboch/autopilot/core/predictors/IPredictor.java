package cz.zamboch.autopilot.core.predictors;

import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Predictor interface for complex (non-scalar) outputs such as
 * distributions or classification results. Lazy — only called
 * when consumed.
 *
 * @param <T> prediction output type
 */
public interface IPredictor<T> {

    /** Compute and return prediction. */
    T predict(Whiteboard wb);

    /** Confidence in [0, 1]. 0 = "I have no idea", 1 = "certain". */
    double confidence(Whiteboard wb);

    /** Human-readable name for logging / virtual-gun tracking. */
    String name();
}

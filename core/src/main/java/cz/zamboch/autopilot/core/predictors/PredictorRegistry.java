package cz.zamboch.autopilot.core.predictors;

import java.util.HashMap;
import java.util.Map;

/**
 * Type-safe registry of distribution predictors, stored on the Whiteboard.
 * Keyed by predictor interface class (e.g. IGfTargetingPredictor.class).
 */
public final class PredictorRegistry {

    private final Map<Class<?>, IPredictor<?>> predictors = new HashMap<Class<?>, IPredictor<?>>();

    @SuppressWarnings("unchecked")
    public <T> void register(Class<? extends IPredictor<T>> key, IPredictor<T> impl) {
        predictors.put(key, impl);
    }

    @SuppressWarnings("unchecked")
    public <T> IPredictor<T> get(Class<? extends IPredictor<T>> key) {
        return (IPredictor<T>) predictors.get(key);
    }
}

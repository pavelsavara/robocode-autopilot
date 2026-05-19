package cz.zamboch.autopilot.core;

/**
 * Central state store for one robot's perspective during a battle.
 * All values are stored in a flat double[] array indexed by Feature ordinal.
 * No state other than features — all input and computed data lives in the
 * array.
 * <p>
 * The Transformer is a private implementation detail: registered
 * IInGameFeatures
 * are executed in dependency order when {@link #process()} is called.
 */
public final class Whiteboard {
    private final double[] features = new double[Feature.COUNT];
    private final Transformer transformer = new Transformer();

    public Whiteboard() {
        clearFeatures();
    }

    /** Register feature processors. Call before first process(). */
    public void registerFeatures(IInGameFeatures... featureSets) {
        for (IInGameFeatures f : featureSets) {
            transformer.register(f);
        }
        transformer.resolveDependencies();
    }

    /** Execute all registered feature processors in dependency order. */
    public void process() {
        transformer.process(this);
    }

    /** Set a feature value. */
    public void setFeature(Feature f, double value) {
        if (Double.isInfinite(value)) {
            value = Double.NaN;
        }
        features[f.ordinal()] = value;
    }

    /** Get a feature value. Returns NaN if not yet set. */
    public double getFeature(Feature f) {
        return features[f.ordinal()];
    }

    /** Reset all features to NaN. Typically called at round start. */
    public void clearFeatures() {
        for (int i = 0; i < features.length; i++) {
            features[i] = Double.NaN;
        }
    }
}

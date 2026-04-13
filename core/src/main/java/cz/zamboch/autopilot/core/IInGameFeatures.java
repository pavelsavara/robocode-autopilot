package cz.zamboch.autopilot.core;

/**
 * In-game feature processor interface. Computes feature values from Whiteboard state.
 * Designed to be lightweight for inclusion in the robot JAR — no CSV/IO dependencies.
 */
public interface IInGameFeatures {

    /** Feature enum values this processor produces. */
    Feature[] getOutputFeatures();

    /** Feature enum values this processor depends on (must run first). */
    Feature[] getDependencies();

    /** Compute feature value(s) and write to whiteboard. */
    void process(Whiteboard wb);
}

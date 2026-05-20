package cz.zamboch.autopilot.core.strategy;

/**
 * Gun aiming and fire-power strategy.
 */
public interface IGunStrategy {

    /** Compute the fire command (angle + power) for this tick. */
    void getFireCommand(FireCommand out);

    /** Human-readable name. */
    String getName();
}

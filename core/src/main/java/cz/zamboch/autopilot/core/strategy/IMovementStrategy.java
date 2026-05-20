package cz.zamboch.autopilot.core.strategy;

/**
 * Movement strategy.
 */
public interface IMovementStrategy {

    /** Compute the movement command for this tick. */
    void getCommand(MovementCommand out);

    /** Human-readable name. */
    String getName();
}

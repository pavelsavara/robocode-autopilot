package cz.zamboch.autopilot.core.strategy;

/**
 * What the robot should do this tick. Mutable — strategies write into
 * a shared instance to avoid per-tick allocation.
 */
public final class MovementCommand {

    /** Pixels forward (negative = reverse). */
    public double ahead;

    /** Radians to turn body. */
    public double turnRight;

    public MovementCommand() {}

    public MovementCommand(double ahead, double turnRight) {
        this.ahead = ahead;
        this.turnRight = turnRight;
    }

    /** Overwrite both fields. */
    public void set(double ahead, double turnRight) {
        this.ahead = ahead;
        this.turnRight = turnRight;
    }
}

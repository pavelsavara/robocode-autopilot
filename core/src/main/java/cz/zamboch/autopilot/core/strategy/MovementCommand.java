package cz.zamboch.autopilot.core.strategy;

/**
 * Value object — what the robot should do this tick.
 */
public final class MovementCommand {

    /** Pixels forward (negative = reverse). */
    public final double ahead;

    /** Radians to turn body. */
    public final double turnRight;

    public MovementCommand(double ahead, double turnRight) {
        this.ahead = ahead;
        this.turnRight = turnRight;
    }
}

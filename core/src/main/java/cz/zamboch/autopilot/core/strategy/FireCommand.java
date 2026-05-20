package cz.zamboch.autopilot.core.strategy;

/**
 * Gun fire command. Mutable — strategies write into a shared instance
 * to avoid per-tick allocation.
 */
public final class FireCommand {

    /** Absolute gun angle to fire at (radians). */
    public double angle;

    /** Fire power [0.1, 3.0]. 0 or NaN means don't fire. */
    public double power;

    public FireCommand() {
    }

    /** Overwrite both fields. */
    public void set(double angle, double power) {
        this.angle = angle;
        this.power = power;
    }
}

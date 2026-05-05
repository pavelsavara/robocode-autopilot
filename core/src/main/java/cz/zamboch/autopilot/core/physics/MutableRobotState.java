package cz.zamboch.autopilot.core.physics;

/**
 * Mutable robot state for zero-allocation simulation loops.
 * Use instead of {@link RobotState} in tight loops where allocating
 * a new immutable object per tick is wasteful (e.g. PrecisePredictor).
 *
 * <p>Not thread-safe. Intended for single-threaded per-tick computation.
 */
public final class MutableRobotState {

    public double x, y, heading, velocity;

    public MutableRobotState() {}

    public MutableRobotState(double x, double y, double heading, double velocity) {
        this.x = x;
        this.y = y;
        this.heading = heading;
        this.velocity = velocity;
    }

    /** Copy from an immutable RobotState. */
    public void copyFrom(RobotState s) {
        x = s.x;
        y = s.y;
        heading = s.heading;
        velocity = s.velocity;
    }

    /** Copy from another mutable state. */
    public void copyFrom(MutableRobotState s) {
        x = s.x;
        y = s.y;
        heading = s.heading;
        velocity = s.velocity;
    }

    /**
     * Advance this state by one tick in place. Zero allocation.
     * Equivalent to {@link RobotPhysics#step} but mutates {@code this}.
     */
    public void step(double accel, double turnRate, int bfW, int bfH) {
        double maxTurn = RobotPhysics.maxTurnRate(velocity);
        turnRate = Math.max(-maxTurn, Math.min(maxTurn, turnRate));

        heading += turnRate;

        velocity += accel;
        if (velocity > RobotPhysics.MAX_VELOCITY) velocity = RobotPhysics.MAX_VELOCITY;
        if (velocity < -RobotPhysics.MAX_VELOCITY) velocity = -RobotPhysics.MAX_VELOCITY;

        x += velocity * Math.sin(heading);
        y += velocity * Math.cos(heading);

        // Wall clamping
        if (x < RobotPhysics.ROBOT_HALF_SIZE) x = RobotPhysics.ROBOT_HALF_SIZE;
        else if (x > bfW - RobotPhysics.ROBOT_HALF_SIZE) x = bfW - RobotPhysics.ROBOT_HALF_SIZE;
        if (y < RobotPhysics.ROBOT_HALF_SIZE) y = RobotPhysics.ROBOT_HALF_SIZE;
        else if (y > bfH - RobotPhysics.ROBOT_HALF_SIZE) y = bfH - RobotPhysics.ROBOT_HALF_SIZE;
    }

    /** Snapshot to immutable state. */
    public RobotState toImmutable() {
        return new RobotState(x, y, heading, velocity);
    }
}

package cz.zamboch.autopilot.core.physics;

/**
 * Robocode movement rules as pure functions. Constants and formulas
 * for robot kinematics.
 */
public final class RobotPhysics {

    public static final double MAX_VELOCITY = 8.0;
    public static final double ACCELERATION = 1.0;
    public static final double DECELERATION = 2.0;
    public static final int ROBOT_HALF_SIZE = 18;

    private RobotPhysics() {}

    /** Max body turn rate in radians at given speed. */
    public static double maxTurnRate(double velocity) {
        return Math.toRadians(10.0 - 0.75 * Math.abs(velocity));
    }

    /**
     * Advance a RobotState by one tick given acceleration and turn rate.
     * Clamps position to battlefield walls (18px inset).
     */
    public static RobotState step(RobotState s, double accel, double turnRate,
                                  int bfW, int bfH) {
        // Clamp turn rate
        double maxTurn = maxTurnRate(s.velocity);
        turnRate = Math.max(-maxTurn, Math.min(maxTurn, turnRate));

        double newHeading = s.heading + turnRate;

        // Apply acceleration, clamp velocity
        double newVelocity = s.velocity + accel;
        if (newVelocity > MAX_VELOCITY) newVelocity = MAX_VELOCITY;
        if (newVelocity < -MAX_VELOCITY) newVelocity = -MAX_VELOCITY;

        // Move in heading direction
        double newX = s.x + newVelocity * Math.sin(newHeading);
        double newY = s.y + newVelocity * Math.cos(newHeading);

        // Wall clamping
        newX = Math.max(ROBOT_HALF_SIZE, Math.min(bfW - ROBOT_HALF_SIZE, newX));
        newY = Math.max(ROBOT_HALF_SIZE, Math.min(bfH - ROBOT_HALF_SIZE, newY));

        return new RobotState(newX, newY, newHeading, newVelocity);
    }
}

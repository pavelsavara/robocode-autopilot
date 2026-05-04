package cz.zamboch.autopilot.core.physics;

/**
 * Tick-by-tick forward simulation of robot movement. Given a starting
 * state and a sequence of (accel, turnRate) commands, predicts the
 * robot's trajectory.
 *
 * Phase 1: basic implementation for circular prediction validation.
 */
public final class PrecisePredictor {

    private PrecisePredictor() {}

    /**
     * Simulate N ticks of movement from the given state with constant
     * acceleration and turn rate.
     *
     * @param start initial robot state
     * @param accel acceleration per tick
     * @param turnRate turn rate per tick (radians)
     * @param ticks number of ticks to simulate
     * @param bfW battlefield width
     * @param bfH battlefield height
     * @return final robot state after N ticks
     */
    public static RobotState simulate(RobotState start, double accel,
                                      double turnRate, int ticks,
                                      int bfW, int bfH) {
        RobotState state = start;
        for (int i = 0; i < ticks; i++) {
            state = RobotPhysics.step(state, accel, turnRate, bfW, bfH);
        }
        return state;
    }
}

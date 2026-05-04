package cz.zamboch.autopilot.core.physics;

import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Immutable snapshot of robot position and motion state.
 */
public final class RobotState {

    public final double x, y, heading, velocity;

    public RobotState(double x, double y, double heading, double velocity) {
        this.x = x;
        this.y = y;
        this.heading = heading;
        this.velocity = velocity;
    }

    /** Create from the current "our" state on the whiteboard. */
    public static RobotState fromWhiteboard(Whiteboard wb) {
        return new RobotState(wb.getOurX(), wb.getOurY(),
                wb.getOurHeading(), wb.getOurVelocity());
    }

    /** Create a state at an arbitrary position. */
    public static RobotState at(double x, double y, double heading, double velocity) {
        return new RobotState(x, y, heading, velocity);
    }
}

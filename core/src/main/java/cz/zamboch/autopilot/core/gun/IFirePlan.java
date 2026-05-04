package cz.zamboch.autopilot.core.gun;

import cz.zamboch.autopilot.core.Whiteboard;

/**
 * A fire plan is a sequence of one or more shots, each with a power
 * and aiming strategy. The VirtualGunManager asks for the next shot
 * when the gun is cool; the plan tracks its own state.
 */
public interface IFirePlan {

    /** Is this plan still active (has more shots to fire)? */
    boolean hasNextShot();

    /** Power for the next shot. */
    double getNextPower();

    /** Absolute gun angle for the next shot. */
    double getNextAngle(Whiteboard wb);

    /** Called after each shot fires to advance internal state. */
    void onShotFired();

    /** Reset for a new firing cycle. */
    void reset();

    /** Human-readable name. */
    String getName();
}

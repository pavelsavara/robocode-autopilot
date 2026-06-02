package cz.zamboch.autopilot.core.strategy;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Orbit movement — circle opponent at ~400px, adjust inward/outward, with a
 * wall reversal: when continuing forward along the orbit would drive us into a wall
 * we drive backward instead, which carries us the other way around the opponent
 * WITHOUT spinning the body — so the gun stays on target and aim is undisturbed.
 * This avoids the failure mode where a wall-hugging opponent (e.g. Walls) shepherds
 * us into an edge and we sit there at zero velocity being shot.
 */
public final class OrbitMovementStrategy implements IMovementStrategy {

    /** Keep the look-ahead point this far from each wall. */
    private static final double WALL_MARGIN = 36.0;
    /** Distance ahead we test for an impending wall collision. */
    private static final double LOOK_AHEAD = 120.0;
    /** Minimum ticks between travel-direction reversals, to avoid jitter near walls. */
    private static final int FLIP_COOLDOWN = 10;

    private final Whiteboard wb;
    private final double bfWidth;
    private final double bfHeight;

    /** Travel sense along the orbit tangent: +1 forward, -1 backward. */
    private int driveDir = 1;
    /** Ticks remaining before we are allowed to reverse travel again. */
    private int flipCooldown = 0;

    public OrbitMovementStrategy(Whiteboard wb, double bfWidth, double bfHeight) {
        this.wb = wb;
        this.bfWidth = bfWidth;
        this.bfHeight = bfHeight;
    }

    @Override
    public void getCommand(MovementCommand out) {
        double distance = wb.getFeature(Feature.DISTANCE);
        if (Double.isNaN(distance)) {
            out.set(0, 0);
            return;
        }
        double bearingRadians = wb.getFeature(Feature.BEARING_RADIANS);
        double x = wb.getFeature(Feature.OUR_X);
        double y = wb.getFeature(Feature.OUR_Y);
        double heading = wb.getFeature(Feature.OUR_HEADING);

        // Perpendicular orbit heading with distance correction (unchanged aim geometry).
        double turn = bearingRadians + Math.PI / 2;
        if (distance > 450) {
            turn -= Math.PI / 6; // cut inward
        } else if (distance < 350) {
            turn += Math.PI / 6; // veer outward
        }
        turn = RoboMath.normalRelativeAngle(turn);

        // Absolute heading we would face after this turn.
        double travelHeading = RoboMath.normalAbsoluteAngle(heading + turn);

        if (flipCooldown > 0) {
            flipCooldown--;
        }
        // If driving in the current sense would push the look-ahead point into a wall,
        // reverse travel (forward<->backward). A cooldown prevents rapid jitter.
        if (flipCooldown == 0 && !Double.isNaN(x) && !Double.isNaN(y)) {
            double cur = driveDir > 0 ? travelHeading
                    : RoboMath.normalAbsoluteAngle(travelHeading + Math.PI);
            double opp = driveDir > 0 ? RoboMath.normalAbsoluteAngle(travelHeading + Math.PI)
                    : travelHeading;
            if (wouldHitWall(x, y, cur) && !wouldHitWall(x, y, opp)) {
                driveDir = -driveDir;
                flipCooldown = FLIP_COOLDOWN;
            }
        }

        out.set(100 * driveDir, turn);
    }

    /**
     * True if a point {@link #LOOK_AHEAD}px ahead along {@code heading} would lie
     * within {@link #WALL_MARGIN} of a wall. Robocode heading 0 = north (+Y), so
     * forward motion is {@code (x += sin(h), y += cos(h))}.
     */
    private boolean wouldHitWall(double x, double y, double heading) {
        double nx = x + Math.sin(heading) * LOOK_AHEAD;
        double ny = y + Math.cos(heading) * LOOK_AHEAD;
        return nx < WALL_MARGIN || nx > bfWidth - WALL_MARGIN
                || ny < WALL_MARGIN || ny > bfHeight - WALL_MARGIN;
    }

    @Override
    public String getName() {
        return "OrbitMovement";
    }

}

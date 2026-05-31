package cz.zamboch.autopilot.core.strategy;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Orbit movement — circle opponent at ~400px, adjust inward/outward, with a
 * predictive wall reversal: before the orbit tangent would carry us into a wall we
 * flip the orbit sense and circle the opponent the other way. This keeps us moving
 * perpendicular to the opponent (so dodging and lateral motion are preserved, and
 * gun aim is unaffected) while never pinning us against a wall or in a corner — the
 * failure mode where a wall-hugging opponent (e.g. Walls) shepherds us into an edge
 * and we sit there at zero velocity being shot.
 */
public final class OrbitMovementStrategy implements IMovementStrategy {

    /** Keep the look-ahead point this far from each wall. */
    private static final double WALL_MARGIN = 50.0;
    /** Distance ahead we test for an impending wall collision. */
    private static final double LOOK_AHEAD = 150.0;

    private final Whiteboard wb;
    private final double bfWidth;
    private final double bfHeight;

    /** Orbit sense: +1 or -1 (which way we circle the opponent). */
    private int orbitDir = 1;

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
        double absBearingToOpp = RoboMath.normalAbsoluteAngle(heading + bearingRadians);

        // Predictive wall reversal: if circling in the current sense would drive the
        // look-ahead point into a wall, flip to the other sense. Only flip if the
        // other sense is actually safer, so we never thrash in an open field.
        if (!Double.isNaN(x) && !Double.isNaN(y)) {
            if (wouldHitWall(x, y, orbitHeading(absBearingToOpp, distance, orbitDir))
                    && !wouldHitWall(x, y, orbitHeading(absBearingToOpp, distance, -orbitDir))) {
                orbitDir = -orbitDir;
            }
        }

        double desired = orbitHeading(absBearingToOpp, distance, orbitDir);
        double turn = RoboMath.normalRelativeAngle(desired - heading);
        out.set(100, turn);
    }

    /** Absolute travel heading for one orbit sense, including distance correction. */
    private double orbitHeading(double absBearingToOpp, double distance, int dir) {
        double h = absBearingToOpp + dir * (Math.PI / 2);
        if (distance > 450) {
            h -= dir * (Math.PI / 6); // cut inward
        } else if (distance < 350) {
            h += dir * (Math.PI / 6); // veer outward
        }
        return RoboMath.normalAbsoluteAngle(h);
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

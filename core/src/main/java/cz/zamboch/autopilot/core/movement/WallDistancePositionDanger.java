package cz.zamboch.autopilot.core.movement;

import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Position danger based on wall/corner proximity and distance to enemy.
 *
 * <p>Danger components (all in [0, 1], combined via weighted average):</p>
 * <ul>
 *   <li><b>Wall danger</b>: increases sharply within 50px of any wall</li>
 *   <li><b>Corner danger</b>: high when close to two walls simultaneously</li>
 *   <li><b>Distance danger</b>: too close (&lt;150px) or too far (&gt;500px) is bad</li>
 * </ul>
 */
public final class WallDistancePositionDanger implements IPositionDanger {

    private static final int MARGIN = 18; // robot half-size
    private static final double WALL_DANGER_THRESHOLD = 80.0;
    private static final double CORNER_DANGER_THRESHOLD = 120.0;
    private static final double PREFERRED_DIST_MIN = 150.0;
    private static final double PREFERRED_DIST_MAX = 500.0;

    private static final double W_WALL = 0.45;
    private static final double W_CORNER = 0.30;
    private static final double W_DISTANCE = 0.25;

    @Override
    public double danger(double x, double y, Whiteboard wb) {
        int bfW = wb.getBattlefieldWidth();
        int bfH = wb.getBattlefieldHeight();

        // Wall danger: min distance to any wall
        double distLeft = x - MARGIN;
        double distRight = bfW - MARGIN - x;
        double distBottom = y - MARGIN;
        double distTop = bfH - MARGIN - y;
        double minWall = Math.min(Math.min(distLeft, distRight),
                Math.min(distBottom, distTop));
        double wallDanger = minWall < WALL_DANGER_THRESHOLD
                ? 1.0 - (minWall / WALL_DANGER_THRESHOLD) : 0.0;

        // Corner danger: proximity to nearest corner (geometric mean of two wall distances)
        double minHoriz = Math.min(distLeft, distRight);
        double minVert = Math.min(distBottom, distTop);
        double cornerDist = Math.sqrt(minHoriz * minHoriz + minVert * minVert);
        double cornerDanger = cornerDist < CORNER_DANGER_THRESHOLD
                ? 1.0 - (cornerDist / CORNER_DANGER_THRESHOLD) : 0.0;

        // Distance danger: prefer to stay at medium range from opponent
        double oppX = wb.getOpponentX();
        double oppY = wb.getOpponentY();
        double dist = Math.hypot(x - oppX, y - oppY);
        double distDanger;
        if (dist < PREFERRED_DIST_MIN) {
            distDanger = 1.0 - (dist / PREFERRED_DIST_MIN);
        } else if (dist > PREFERRED_DIST_MAX) {
            distDanger = Math.min(1.0,
                    (dist - PREFERRED_DIST_MAX) / PREFERRED_DIST_MAX);
        } else {
            distDanger = 0.0;
        }

        return clamp(W_WALL * wallDanger + W_CORNER * cornerDanger
                + W_DISTANCE * distDanger);
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }
}

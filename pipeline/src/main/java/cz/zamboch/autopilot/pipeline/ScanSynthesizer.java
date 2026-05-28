package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.Whiteboard;
import robocode.control.snapshot.IRobotSnapshot;

import java.awt.geom.Arc2D;
import java.awt.geom.Rectangle2D;

/**
 * Synthesizes scan events using the same Arc2D.PIE geometry as the Robocode
 * engine.
 * Determines whether the radar sweep between two ticks intersects the
 * opponent's bounding box.
 */
final class ScanSynthesizer {
    private static final double SCAN_RADIUS = 1200.0;
    private static final double ROBOT_SIZE = 36.0;

    // Reusable Arc2D for scan detection (matches engine's scanArc)
    private final Arc2D.Double scanArc = new Arc2D.Double();

    /**
     * Attempt to synthesize a scan from {@code us}'s radar sweep onto
     * {@code them}.
     * If the sweep intersects, injects scan features into our whiteboard.
     */
    void tryScan(Perspective us, IRobotSnapshot self, IRobotSnapshot opponent, long tick) {
        double radarHeading = self.getRadarHeading();
        double prevRadar = us.prevRadarHeading();

        if (Double.isNaN(prevRadar)) {
            // First tick: no sweep yet
            us.setPrevRadarHeading(radarHeading);
            return;
        }

        // Replicate engine's scan() method:
        double scanRadians = radarHeading - prevRadar;
        if (scanRadians < -Math.PI)
            scanRadians += 2 * Math.PI;
        else if (scanRadians > Math.PI)
            scanRadians -= 2 * Math.PI;

        // Convert to Java2D coords
        double startAngle = RoboMath.normalAbsoluteAngle(prevRadar - Math.PI / 2);

        // Build Arc2D.PIE (same as engine)
        double r = SCAN_RADIUS;
        scanArc.setArc(self.getX() - r, self.getY() - r, 2 * r, 2 * r,
                Math.toDegrees(startAngle), Math.toDegrees(scanRadians), Arc2D.PIE);

        // Target bounding box (36x36 centered on opponent)
        double half = ROBOT_SIZE / 2;
        Rectangle2D.Double targetBox = new Rectangle2D.Double(
                opponent.getX() - half, opponent.getY() - half, ROBOT_SIZE, ROBOT_SIZE);

        if (intersects(scanArc, targetBox)) {
            injectScan(us.wb(), self, opponent, tick);
        }

        us.setPrevRadarHeading(radarHeading);
    }

    /**
     * Engine-exact intersection: line from arc center to start point, OR arc
     * boundary.
     */
    private static boolean intersects(Arc2D arc, Rectangle2D rect) {
        return rect.intersectsLine(arc.getCenterX(), arc.getCenterY(),
                arc.getStartPoint().getX(), arc.getStartPoint().getY())
                || arc.intersects(rect);
    }

    private static void injectScan(Whiteboard wb, IRobotSnapshot self,
            IRobotSnapshot opponent, long tick) {
        double dx = opponent.getX() - self.getX();
        double dy = opponent.getY() - self.getY();
        double distance = Math.hypot(dx, dy);

        double angle = Math.atan2(dx, dy);
        double bearing = RoboMath.normalRelativeAngle(angle - self.getBodyHeading());

        wb.setFeature(Feature.DISTANCE, distance);
        wb.setFeature(Feature.BEARING_RADIANS, bearing);
        wb.setFeature(Feature.OPPONENT_HEADING, opponent.getBodyHeading());
        wb.setFeature(Feature.OPPONENT_VELOCITY, opponent.getVelocity());
        wb.setFeature(Feature.OPPONENT_ENERGY, opponent.getEnergy());
        wb.setFeature(Feature.LAST_SCAN_TICK, tick);
        wb.setStringFeature(Feature.OPPONENT_ID, opponent.getShortName());
    }

}

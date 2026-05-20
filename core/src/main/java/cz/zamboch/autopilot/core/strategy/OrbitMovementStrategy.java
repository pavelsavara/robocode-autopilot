package cz.zamboch.autopilot.core.strategy;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Orbit movement — circle opponent at ~400px, adjust inward/outward.
 */
public final class OrbitMovementStrategy implements IMovementStrategy {

    private final Whiteboard wb;

    public OrbitMovementStrategy(Whiteboard wb) {
        this.wb = wb;
    }

    @Override
    public void getCommand(MovementCommand out) {
        double distance = wb.getFeature(Feature.DISTANCE);
        if (Double.isNaN(distance)) {
            out.set(0, 0);
            return;
        }
        double bearingRadians = wb.getFeature(Feature.BEARING_RADIANS);

        // Turn perpendicular to opponent (orbit)
        double turn = bearingRadians + Math.PI / 2;
        // Adjust distance: move closer if >450, farther if <350
        if (distance > 450) {
            turn -= Math.PI / 6; // cut inward
        } else if (distance < 350) {
            turn += Math.PI / 6; // veer outward
        }
        out.set(100, normalizeTurn(turn));
    }

    @Override
    public String getName() {
        return "OrbitMovement";
    }

    private static double normalizeTurn(double angle) {
        while (angle > Math.PI)
            angle -= 2 * Math.PI;
        while (angle < -Math.PI)
            angle += 2 * Math.PI;
        return angle;
    }
}

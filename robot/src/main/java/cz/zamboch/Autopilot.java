package cz.zamboch;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.MovementFeatures;
import cz.zamboch.autopilot.core.features.SpatialFeatures;
import cz.zamboch.autopilot.core.features.FireFeatures;
import cz.zamboch.autopilot.core.features.TimingFeatures;
import robocode.AdvancedRobot;
import robocode.RobotStatus;
import robocode.ScannedRobotEvent;
import robocode.StatusEvent;

/**
 * Autopilot — competition robot.
 * Phase 1: Simple orbit movement + head-on gun.
 * Uses the Whiteboard/Transformer feature infrastructure from day 1.
 */
public final class Autopilot extends AdvancedRobot {
    private final Whiteboard wb = new Whiteboard();

    @Override
    public void onStatus(StatusEvent event) {
        RobotStatus status = event.getStatus();
        wb.setFeature(Feature.TICK, status.getTime());
        wb.setFeature(Feature.OUR_X, status.getX());
        wb.setFeature(Feature.OUR_Y, status.getY());
        wb.setFeature(Feature.OUR_HEADING, status.getHeadingRadians());
        wb.setFeature(Feature.OUR_VELOCITY, status.getVelocity());
        wb.setFeature(Feature.OUR_ENERGY, status.getEnergy());
        wb.setFeature(Feature.GUN_HEAT, status.getGunHeat());
        wb.setFeature(Feature.GUN_HEADING, status.getGunHeadingRadians());
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent event) {
        wb.setFeature(Feature.DISTANCE, event.getDistance());
        wb.setFeature(Feature.BEARING_RADIANS, event.getBearingRadians());
        wb.setFeature(Feature.OPPONENT_HEADING, event.getHeadingRadians());
        wb.setFeature(Feature.OPPONENT_VELOCITY, event.getVelocity());
        wb.setFeature(Feature.OPPONENT_ENERGY, event.getEnergy());
        wb.setFeature(Feature.LAST_SCAN_TICK, wb.getFeature(Feature.TICK));

    }

    @Override
    public void run() {
        // Register feature processors
        wb.registerFeatures(
                new SpatialFeatures(),
                new MovementFeatures(),
                new TimingFeatures(),
                new FireFeatures());

        // Set battle constants
        wb.setFeature(Feature.BATTLEFIELD_WIDTH, getBattleFieldWidth());
        wb.setFeature(Feature.BATTLEFIELD_HEIGHT, getBattleFieldHeight());

        // Infinite radar spin
        setTurnRadarRight(Double.POSITIVE_INFINITY);

        while (true) {
            doTurn();
            execute();
        }
    }

    private void doTurn() {
        wb.process();

        // --- Movement: orbit at ~400px distance ---
        double distance = wb.getFeature(Feature.DISTANCE);
        if (!Double.isNaN(distance)) {
            double bearingRadians = wb.getFeature(Feature.BEARING_RADIANS);

            // Turn perpendicular to opponent (orbit)
            double turn = bearingRadians + Math.PI / 2;
            // Adjust distance: move closer if >450, farther if <350
            if (distance > 450) {
                turn -= Math.PI / 6; // cut inward
            } else if (distance < 350) {
                turn += Math.PI / 6; // veer outward
            }
            setTurnRightRadians(normalizeTurn(turn));
            setAhead(100);
        }

        // --- Gun: head-on targeting ---
        if (!Double.isNaN(distance)) {
            double absoluteBearing = wb.getFeature(Feature.OPPONENT_BEARING_ABSOLUTE);
            double gunHeading = wb.getFeature(Feature.GUN_HEADING);
            double gunTurn = absoluteBearing - gunHeading;
            setTurnGunRightRadians(normalizeTurn(gunTurn));

            if (getGunHeat() == 0 && Math.abs(getGunTurnRemaining()) < 5) {
                // Fire power inversely proportional to distance
                double power = Math.min(3.0, Math.max(1.0, (400.0 - distance) / 100.0 + 1.0));
                setFire(power);
            }
        }

        // --- Radar: lock on opponent ---
        double absoluteBearing = wb.getFeature(Feature.OPPONENT_BEARING_ABSOLUTE);
        if (!Double.isNaN(absoluteBearing)) {
            double radarTurn = absoluteBearing - Math.toRadians(getRadarHeading());
            // Overshoot by 1.9x for reliable lock
            setTurnRadarRightRadians(normalizeTurn(radarTurn) * 1.9);
        }

        // --- Debug properties: dump all whiteboard features ---
        for (Feature f : Feature.values()) {
            double v = wb.getFeature(f);
            setDebugProperty(f.name(), Double.isNaN(v) ? "NaN" : String.valueOf(v));
        }
    }

    /** Normalize angle to [-PI, PI] */
    private static double normalizeTurn(double angle) {
        while (angle > Math.PI)
            angle -= 2 * Math.PI;
        while (angle < -Math.PI)
            angle += 2 * Math.PI;
        return angle;
    }

}

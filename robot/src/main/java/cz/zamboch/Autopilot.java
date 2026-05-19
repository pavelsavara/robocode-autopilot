package cz.zamboch;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Transformer;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.EnergyFeatures;
import cz.zamboch.autopilot.core.features.MovementFeatures;
import cz.zamboch.autopilot.core.features.SpatialFeatures;
import cz.zamboch.autopilot.core.features.TimingFeatures;
import robocode.AdvancedRobot;
import robocode.ScannedRobotEvent;

/**
 * Autopilot — competition robot.
 * Phase 1: Simple orbit movement + head-on gun.
 * Uses the Whiteboard/Transformer feature infrastructure from day 1.
 */
public final class Autopilot extends AdvancedRobot {
    private final Whiteboard wb = new Whiteboard();
    private final Transformer transformer = new Transformer();

    @Override
    public void run() {
        // Register features
        transformer.register(new SpatialFeatures());
        transformer.register(new MovementFeatures());
        transformer.register(new EnergyFeatures());
        transformer.register(new TimingFeatures());
        transformer.resolveDependencies();

        // Set battle constants
        wb.setBattlefieldSize(getBattleFieldWidth(), getBattleFieldHeight());

        // Infinite radar spin
        setTurnRadarRight(Double.POSITIVE_INFINITY);

        while (true) {
            doTurn();
            execute();
        }
    }

    private void doTurn() {
        wb.setTick(getTime());
        wb.setOurPosition(getX(), getY());
        wb.setOurHeading(Math.toRadians(getHeading()));
        wb.setOurVelocity(getVelocity());
        wb.setOurEnergy(getEnergy());
        wb.setGunHeat(getGunHeat());
        wb.setGunHeadingRadians(Math.toRadians(getGunHeading()));

        wb.clearFeatures();
        transformer.process(wb);

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
            double gunTurn = absoluteBearing - wb.getGunHeadingRadians();
            setTurnGunRightRadians(normalizeTurn(gunTurn));

            if (getGunHeat() == 0 && Math.abs(getGunTurnRemaining()) < 5) {
                // Fire power inversely proportional to distance
                double power = Math.min(3.0, Math.max(1.0, (400.0 - distance) / 100.0 + 1.0));
                setFire(power);
            }
        }

        // --- Radar: lock on opponent ---
        if (wb.getLastScan() != null) {
            double absoluteBearing = wb.getFeature(Feature.OPPONENT_BEARING_ABSOLUTE);
            double radarTurn = absoluteBearing - Math.toRadians(getRadarHeading());
            // Overshoot by 1.9x for reliable lock
            setTurnRadarRightRadians(normalizeTurn(radarTurn) * 1.9);
        }

        // --- Debug properties (saved in .br recordings) ---
        setDebugProperty("tick", String.valueOf(getTime()));
        setDebugProperty("distance", fmt(wb.getFeature(Feature.DISTANCE)));
        setDebugProperty("ourEnergy", fmt(getEnergy()));
        setDebugProperty("oppEnergy", fmt(wb.getFeature(Feature.OPPONENT_ENERGY)));
        setDebugProperty("gunHeat", fmt(getGunHeat()));
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        wb.setLastScan(e, getTime());
    }

    /** Normalize angle to [-PI, PI] */
    private static double normalizeTurn(double angle) {
        while (angle > Math.PI)
            angle -= 2 * Math.PI;
        while (angle < -Math.PI)
            angle += 2 * Math.PI;
        return angle;
    }

    private static String fmt(double v) {
        if (Double.isNaN(v))
            return "NaN";
        return String.format("%.1f", v);
    }
}

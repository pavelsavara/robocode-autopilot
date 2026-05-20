package cz.zamboch;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.MovementFeatures;
import cz.zamboch.autopilot.core.features.SpatialFeatures;
import cz.zamboch.autopilot.core.features.FireFeatures;
import cz.zamboch.autopilot.core.features.TimingFeatures;
import cz.zamboch.autopilot.core.strategy.*;
import robocode.AdvancedRobot;
import robocode.BulletHitEvent;
import robocode.HitByBulletEvent;
import robocode.HitRobotEvent;
import robocode.RobotStatus;
import robocode.Rules;
import robocode.ScannedRobotEvent;
import robocode.StatusEvent;

/**
 * Autopilot — competition robot.
 * Uses the Whiteboard/Transformer feature infrastructure from day 1.
 * Strategy interfaces decouple decision logic from robot wiring.
 */
public final class Autopilot extends AdvancedRobot {
    private final Whiteboard wb = new Whiteboard();
    private IRadarStrategy radar;
    private IGunStrategy gun;
    private IMovementStrategy movement;
    private final MovementCommand moveCmd = new MovementCommand();
    private final FireCommand fireCmd = new FireCommand();

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
        wb.setFeature(Feature.RADAR_HEADING, status.getRadarHeadingRadians());
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
    public void onBulletHit(BulletHitEvent e) {
        double current = wb.getFeature(Feature.OUR_BULLET_DAMAGE_TO_OPPONENT);
        double damage = Rules.getBulletDamage(e.getBullet().getPower());
        wb.setFeature(Feature.OUR_BULLET_DAMAGE_TO_OPPONENT, (Double.isNaN(current) ? 0 : current) + damage);
    }

    @Override
    public void onHitByBullet(HitByBulletEvent e) {
        double current = wb.getFeature(Feature.OPPONENT_BULLET_ENERGY_GAIN);
        double gain = Rules.getBulletHitBonus(e.getBullet().getPower());
        wb.setFeature(Feature.OPPONENT_BULLET_ENERGY_GAIN, (Double.isNaN(current) ? 0 : current) + gain);
    }

    @Override
    public void onHitRobot(HitRobotEvent e) {
        double current = wb.getFeature(Feature.RAM_DAMAGE_TO_OPPONENT);
        wb.setFeature(Feature.RAM_DAMAGE_TO_OPPONENT, (Double.isNaN(current) ? 0 : current) + Rules.ROBOT_HIT_DAMAGE);
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

        // Create strategies
        radar = new NarrowLockRadar(wb);
        gun = new HeadOnGunStrategy(wb);
        movement = new OrbitMovementStrategy(wb);

        while (true) {
            doTurn();
            execute();
        }
    }

    private void doTurn() {
        wb.process();

        // Radar
        setTurnRadarRightRadians(radar.getRadarTurn());

        // Movement
        movement.getCommand(moveCmd);
        setTurnRightRadians(moveCmd.turnRight);
        setAhead(moveCmd.ahead);

        // Gun
        gun.getFireCommand(fireCmd);
        if (!Double.isNaN(fireCmd.angle)) {
            double gunHeading = wb.getFeature(Feature.GUN_HEADING);
            double gunTurn = fireCmd.angle - gunHeading;
            setTurnGunRightRadians(RoboMath.normalRelativeAngle(gunTurn));
            if (fireCmd.power > 0 && Math.abs(getGunTurnRemaining()) < 5) {
                setFire(fireCmd.power);
            }
        }

        // Debug
        for (Feature f : Feature.values()) {
            double v = wb.getFeature(f);
            setDebugProperty(f.name(), Double.isNaN(v) ? "NaN" : String.valueOf(v));
        }
    }

}

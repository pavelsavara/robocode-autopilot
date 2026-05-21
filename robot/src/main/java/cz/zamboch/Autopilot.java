package cz.zamboch;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.VcsFile;
import cz.zamboch.autopilot.core.VcsStore;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.IdentityFeatures;
import cz.zamboch.autopilot.core.features.MovementFeatures;
import cz.zamboch.autopilot.core.features.SpatialFeatures;
import cz.zamboch.autopilot.core.features.FireFeatures;
import cz.zamboch.autopilot.core.features.TimingFeatures;
import cz.zamboch.autopilot.core.features.OurWaveFeatures;
import cz.zamboch.autopilot.core.features.WaveTracker;
import cz.zamboch.autopilot.core.strategy.*;
import robocode.AdvancedRobot;
import robocode.BattleEndedEvent;
import robocode.BulletHitEvent;
import robocode.HitByBulletEvent;
import robocode.HitRobotEvent;
import robocode.RobotStatus;
import robocode.Rules;
import robocode.ScannedRobotEvent;
import robocode.StatusEvent;

import java.io.File;
import java.io.IOException;

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
    private int opponentHash;
    private boolean vcsLoaded;

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

        // Opponent identity (once)
        if (!vcsLoaded) {
            String name = event.getName();
            wb.setStringFeature(Feature.OPPONENT_ID, name);
            int sp = name.indexOf(' ');
            String botId = (sp < 0) ? name : name.substring(0, sp);
            opponentHash = RoboMath.fnv1a32(botId);
            File dataFile = getDataFile("vcs.dat");
            VcsStore store = VcsFile.loadForOpponent(dataFile, opponentHash);
            wb.setVcsStore(store);
            vcsLoaded = true;
        }
    }

    @Override
    public void onBattleEnded(BattleEndedEvent event) {
        VcsStore store = wb.getVcsStore();
        if (store != null && vcsLoaded) {
            try {
                VcsFile.saveForOpponent(getDataFile("vcs.dat"), opponentHash, store);
            } catch (IOException e) {
                // Best effort persistence
            }
        }
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
                new FireFeatures(),
                new IdentityFeatures(),
                new OurWaveFeatures(),
                new WaveTracker());

        // Set battle constants
        wb.setFeature(Feature.BATTLEFIELD_WIDTH, getBattleFieldWidth());
        wb.setFeature(Feature.BATTLEFIELD_HEIGHT, getBattleFieldHeight());

        // Create strategies
        radar = new NarrowLockRadar(wb);
        gun = new GFGunStrategy(wb);
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
                snapshotFireFeatures(fireCmd.power);
            }
        }

        // Debug
        for (Feature f : Feature.values()) {
            if (f == Feature.OPPONENT_ID) {
                String s = wb.getStringFeature(f);
                setDebugProperty(f.name(), s != null ? s : "");
                continue;
            }
            double v = wb.getFeature(f);
            setDebugProperty(f.name(), Double.isNaN(v) ? "NaN" : String.valueOf(v));
        }
    }

    /**
     * Snapshot current state into OUR_FIRE_* features for WaveTracker to pick up
     * next tick.
     */
    private void snapshotFireFeatures(double power) {
        wb.setFeature(Feature.OUR_FIRE_POWER, power);
        wb.setFeature(Feature.OUR_FIRE_X, wb.getFeature(Feature.OUR_X));
        wb.setFeature(Feature.OUR_FIRE_Y, wb.getFeature(Feature.OUR_Y));
        wb.setFeature(Feature.OUR_FIRE_TICK, wb.getFeature(Feature.TICK));
        wb.setFeature(Feature.OUR_FIRE_BEARING_ABSOLUTE, wb.getFeature(Feature.OPPONENT_BEARING_ABSOLUTE));
        wb.setFeature(Feature.OUR_FIRE_DISTANCE, wb.getFeature(Feature.DISTANCE));
        wb.setFeature(Feature.OUR_FIRE_LATERAL_VELOCITY, wb.getFeature(Feature.OPPONENT_LATERAL_VELOCITY));
        wb.setFeature(Feature.OUR_FIRE_ADVANCING_VELOCITY, wb.getFeature(Feature.OPPONENT_ADVANCING_VELOCITY));
        wb.setFeature(Feature.OUR_FIRE_OPPONENT_X, wb.getFeature(Feature.OPPONENT_X));
        wb.setFeature(Feature.OUR_FIRE_OPPONENT_Y, wb.getFeature(Feature.OPPONENT_Y));
    }

}

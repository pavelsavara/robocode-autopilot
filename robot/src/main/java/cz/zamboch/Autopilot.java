package cz.zamboch;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.VcsFile;
import cz.zamboch.autopilot.core.VcsStore;
import cz.zamboch.autopilot.core.ModelSelector;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.IdentityFeatures;
import cz.zamboch.autopilot.core.features.MovementFeatures;
import cz.zamboch.autopilot.core.features.SpatialFeatures;
import cz.zamboch.autopilot.core.features.FireFeatures;
import cz.zamboch.autopilot.core.features.TimingFeatures;
import cz.zamboch.autopilot.core.features.WallHitEstimator;
import cz.zamboch.autopilot.core.features.OurWaveFeatures;
import cz.zamboch.autopilot.core.features.WaveTracker;
import cz.zamboch.autopilot.core.features.TheirWaveTracker;
import cz.zamboch.autopilot.core.strategy.*;
import robocode.AdvancedRobot;
import robocode.BattleEndedEvent;
import robocode.Bullet;
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

    // Observer mode support
    private boolean observerMode;
    private int nextObserverBulletId = 1;

    @Override
    public void onStatus(StatusEvent event) {
        RobotStatus status = event.getStatus();
        // Save accumulators before ring rotation (setFeature TICK rotates the ring)
        carryForwardAccumulators();
        wb.setFeature(Feature.TICK, status.getTime());
        // Restore accumulators into the new (cleared) ring slot
        restoreAccumulators();
        wb.setFeature(Feature.OUR_X, status.getX());
        wb.setFeature(Feature.OUR_Y, status.getY());
        wb.setFeature(Feature.OUR_HEADING, status.getHeadingRadians());
        wb.setFeature(Feature.OUR_VELOCITY, status.getVelocity());
        wb.setFeature(Feature.OUR_ENERGY, status.getEnergy());
        wb.setFeature(Feature.GUN_HEAT, status.getGunHeat());
        wb.setFeature(Feature.GUN_HEADING, status.getGunHeadingRadians());
        wb.setFeature(Feature.RADAR_HEADING, status.getRadarHeadingRadians());
    }

    private static final Feature[] ACCUMULATOR_FEATURES = {
            Feature.OUR_BULLET_DAMAGE_TO_OPPONENT,
            Feature.OPPONENT_BULLET_ENERGY_GAIN,
            Feature.RAM_DAMAGE_TO_OPPONENT,
            Feature.OPPONENT_RAM_ENERGY_GAIN,
            Feature.OPPONENT_WALL_HIT_DAMAGE
    };

    /**
     * Copy accumulator values from current slot before ring rotation clears them.
     */
    private void carryForwardAccumulators() {
        for (Feature f : ACCUMULATOR_FEATURES) {
            double val = wb.getFeature(f);
            if (!Double.isNaN(val) && val != 0) {
                // Store in a temp — ring rotation will clear the slot,
                // then we restore after TICK is set.
                // Actually: we read BEFORE setFeature(TICK), so just save+restore.
                accumulatorCarry[f.ordinal()] = val;
            }
        }
    }

    /** Restore carried-forward accumulators into the new (cleared) ring slot. */
    private void restoreAccumulators() {
        for (Feature f : ACCUMULATOR_FEATURES) {
            double val = accumulatorCarry[f.ordinal()];
            if (val != 0) {
                wb.setFeature(f, val);
                accumulatorCarry[f.ordinal()] = 0;
            }
        }
    }

    private final double[] accumulatorCarry = new double[Feature.COUNT];

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
            wb.setModelSelector(new ModelSelector(store));
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
        wb.markBulletHit(e.getBullet().hashCode());
        double current = wb.getFeature(Feature.OUR_BULLET_DAMAGE_TO_OPPONENT);
        double damage = Rules.getBulletDamage(e.getBullet().getPower());
        wb.setFeature(Feature.OUR_BULLET_DAMAGE_TO_OPPONENT, (Double.isNaN(current) ? 0 : current) + damage);
    }

    @Override
    public void onHitByBullet(HitByBulletEvent e) {
        double current = wb.getFeature(Feature.OPPONENT_BULLET_ENERGY_GAIN);
        double gain = Rules.getBulletHitBonus(e.getBullet().getPower());
        wb.setFeature(Feature.OPPONENT_BULLET_ENERGY_GAIN, (Double.isNaN(current) ? 0 : current) + gain);
        wb.markTheirBulletHitUs(e.getBullet().getPower());
    }

    @Override
    public void onHitRobot(HitRobotEvent e) {
        // Both robots always take ROBOT_HIT_DAMAGE
        double current = wb.getFeature(Feature.RAM_DAMAGE_TO_OPPONENT);
        wb.setFeature(Feature.RAM_DAMAGE_TO_OPPONENT, (Double.isNaN(current) ? 0 : current) + Rules.ROBOT_HIT_DAMAGE);

        // If opponent is "at fault" (they rammed us), they gain ROBOT_HIT_BONUS
        if (!e.isMyFault()) {
            double gain = wb.getFeature(Feature.OPPONENT_RAM_ENERGY_GAIN);
            wb.setFeature(Feature.OPPONENT_RAM_ENERGY_GAIN, (Double.isNaN(gain) ? 0 : gain) + Rules.ROBOT_HIT_BONUS);
        }
    }

    /**
     * Initialize this Autopilot in observer mode — no real peer, no battle loop.
     * Call event handlers externally, then call {@link #doTurn()} each tick.
     */
    public void initForObserver(VcsStore vcsStore, double bfWidth, double bfHeight) {
        observerMode = true;

        // Register feature processors
        wb.registerFeatures(
                new SpatialFeatures(),
                new MovementFeatures(),
                new TimingFeatures(),
                new WallHitEstimator(),
                new FireFeatures(),
                new IdentityFeatures(),
                new OurWaveFeatures(),
                new WaveTracker(),
                new TheirWaveTracker());

        // Set battle constants
        wb.setFeature(Feature.BATTLEFIELD_WIDTH, bfWidth);
        wb.setFeature(Feature.BATTLEFIELD_HEIGHT, bfHeight);

        // Create strategies
        radar = new NarrowLockRadar(wb);
        gun = new GFGunStrategy(wb);
        movement = new OrbitMovementStrategy(wb);

        // Load VCS if provided
        if (vcsStore != null) {
            wb.setVcsStore(vcsStore);
            wb.setModelSelector(new ModelSelector(vcsStore));
            vcsLoaded = true;
        }
    }

    /** Access the whiteboard (for observer/pipeline integration). */
    public Whiteboard getWhiteboard() {
        return wb;
    }

    @Override
    public void run() {
        // Register feature processors
        wb.registerFeatures(
                new SpatialFeatures(),
                new MovementFeatures(),
                new TimingFeatures(),
                new WallHitEstimator(),
                new FireFeatures(),
                new IdentityFeatures(),
                new OurWaveFeatures(),
                new WaveTracker(),
                new TheirWaveTracker());

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

    public void doTurn() {
        wb.process();

        // Reset accumulators after FireFeatures has consumed them on scan ticks
        double tick = wb.getFeature(Feature.TICK);
        double lastScan = wb.getFeature(Feature.LAST_SCAN_TICK);
        if (!Double.isNaN(tick) && tick == lastScan) {
            for (Feature f : ACCUMULATOR_FEATURES) {
                wb.setFeature(f, 0);
            }
        }

        // Radar
        double radarTurn = radar.getRadarTurn();
        if (!observerMode) {
            setTurnRadarRightRadians(radarTurn);
        }

        // Movement
        movement.getCommand(moveCmd);
        if (!observerMode) {
            setTurnRightRadians(moveCmd.turnRight);
            setAhead(moveCmd.ahead);
        }

        // Gun
        gun.getFireCommand(fireCmd);
        if (!Double.isNaN(fireCmd.angle)) {
            double gunHeading = wb.getFeature(Feature.GUN_HEADING);
            double gunTurn = fireCmd.angle - gunHeading;
            if (!observerMode) {
                setTurnGunRightRadians(RoboMath.normalRelativeAngle(gunTurn));
                if (fireCmd.power > 0 && Math.abs(getGunTurnRemaining()) < 5) {
                    Bullet bullet = setFireBullet(fireCmd.power);
                    if (bullet != null) {
                        snapshotFireFeatures(fireCmd.power, bullet.hashCode());
                    }
                }
            } else {
                // Observer mode: fire when gun is cool
                if (fireCmd.power > 0 && wb.getFeature(Feature.GUN_HEAT) <= 0) {
                    snapshotFireFeatures(fireCmd.power, nextObserverBulletId++);
                }
            }
        }

        // Debug output (only in live mode)
        if (!observerMode) {
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
    }

    /**
     * Snapshot current state into OUR_FIRE_* features for WaveTracker to pick up
     * next tick.
     */
    private void snapshotFireFeatures(double power, int bulletId) {
        wb.setFeature(Feature.OUR_FIRE_POWER, power);
        wb.setFeature(Feature.OUR_FIRE_BULLET_ID, bulletId);
        wb.setFeature(Feature.OUR_FIRE_X, wb.getFeature(Feature.OUR_X));
        wb.setFeature(Feature.OUR_FIRE_Y, wb.getFeature(Feature.OUR_Y));
        wb.setFeature(Feature.OUR_FIRE_TICK, wb.getFeature(Feature.TICK));
        wb.setFeature(Feature.OUR_FIRE_BEARING_ABSOLUTE, wb.getFeature(Feature.OPPONENT_BEARING_ABSOLUTE));
        wb.setFeature(Feature.OUR_FIRE_DISTANCE, wb.getFeature(Feature.DISTANCE));
        wb.setFeature(Feature.OUR_FIRE_LATERAL_VELOCITY, wb.getFeature(Feature.OPPONENT_LATERAL_VELOCITY));
        wb.setFeature(Feature.OUR_FIRE_ADVANCING_VELOCITY, wb.getFeature(Feature.OPPONENT_ADVANCING_VELOCITY));
        wb.setFeature(Feature.OUR_FIRE_OPPONENT_X, wb.getFeature(Feature.OPPONENT_X));
        wb.setFeature(Feature.OUR_FIRE_OPPONENT_Y, wb.getFeature(Feature.OPPONENT_Y));
        wb.setFeature(Feature.OUR_FIRE_AIM_GF, wb.getFeature(Feature.GUN_AIM_GF));
        wb.setFeature(Feature.OUR_FIRE_IS_REAL, 1.0);
    }

}

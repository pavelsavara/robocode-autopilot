package cz.zamboch;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Transformer;
import cz.zamboch.autopilot.core.WaveRecord;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.CombatProgressFeatures;
import cz.zamboch.autopilot.core.features.EnergyFeatures;
import cz.zamboch.autopilot.core.features.EnvelopeFeatures;
import cz.zamboch.autopilot.core.features.IdentityFeatures;
import cz.zamboch.autopilot.core.features.MovementFeatures;
import cz.zamboch.autopilot.core.features.MultiWaveFeatures;
import cz.zamboch.autopilot.core.features.PositionFeatures;
import cz.zamboch.autopilot.core.features.SpatialFeatures;
import cz.zamboch.autopilot.core.features.TargetingFeatures;
import cz.zamboch.autopilot.core.features.TimingFeatures;
import cz.zamboch.autopilot.core.features.WindowFeatures;
import cz.zamboch.autopilot.core.gun.VcsGun;
import cz.zamboch.autopilot.core.gun.VirtualGunManager;
import cz.zamboch.autopilot.core.movement.MovementStrategyManager;
import cz.zamboch.autopilot.core.movement.PathPlanner;
import cz.zamboch.autopilot.core.movement.VcsWaveDanger;
import cz.zamboch.autopilot.core.movement.WallDistancePositionDanger;
import cz.zamboch.autopilot.core.movement.WaveSurfMovement;
import cz.zamboch.autopilot.core.persistence.PersistenceManager;
import cz.zamboch.autopilot.core.physics.ReachableEnvelope;
import cz.zamboch.autopilot.core.predictors.IFingerprintPredictor;
import cz.zamboch.autopilot.core.predictors.IGfTargetingPredictor;
import cz.zamboch.autopilot.core.strategy.IGunStrategy;
import cz.zamboch.autopilot.core.strategy.IMovementStrategy;
import cz.zamboch.autopilot.core.strategy.IRadarStrategy;
import cz.zamboch.autopilot.core.strategy.MovementCommand;
import cz.zamboch.autopilot.core.strategy.StrategyComputer;
import cz.zamboch.autopilot.core.strategy.StrategyParams;
import cz.zamboch.autopilot.core.util.RoboMath;
import cz.zamboch.distilled.GbmFingerprint;
import cz.zamboch.distilled.GbmFirePowerPredictor;
import cz.zamboch.distilled.GbmFireTimingPredictor;
import cz.zamboch.distilled.GbmMovementPredictor;
import cz.zamboch.distilled.MlpGfTargeting;
import cz.zamboch.trivial.CircularGun;
import cz.zamboch.trivial.EnergyRatioStrategyComputer;
import cz.zamboch.trivial.HeadOnGun;
import cz.zamboch.trivial.LinearGun;
import cz.zamboch.trivial.NarrowLockRadar;
import cz.zamboch.trivial.OpponentProfileData;
import cz.zamboch.trivial.OrbitalMovement;
import cz.zamboch.trivial.RandomDodgeMovement;
import cz.zamboch.trivial.StopAndGoMovement;
import robocode.AdvancedRobot;
import robocode.BattleEndedEvent;
import robocode.BulletHitEvent;
import robocode.DeathEvent;
import robocode.HitByBulletEvent;
import robocode.RoundEndedEvent;
import robocode.ScannedRobotEvent;
import robocode.StatusEvent;
import robocode.WinEvent;
import robocode.Rules;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Competition robot. Wires predictors → strategy → gun/movement/radar.
 *
 * <p>Subsystems persist across rounds (VGM hit history, movement damage stats,
 * VCS histograms). Cross-battle persistence via binary data file.</p>
 */
public final class Autopilot extends AdvancedRobot {

    private static final String DATA_FILE_NAME = "autopilot.dat";

    private Whiteboard whiteboard;
    private Transformer transformer;
    private VirtualGunManager gunManager;
    private MovementStrategyManager moveManager;
    private IRadarStrategy radarStrategy;
    private StrategyComputer strategyComputer;
    private StrategyParams currentParams;
    private PersistenceManager persistence;

    // Keep references to predictors for model-load diagnostics
    private GbmFirePowerPredictor firePowerPredictor;
    private GbmMovementPredictor movementPredictor;
    private GbmFireTimingPredictor fireTimingPredictor;

    /** True after one-time subsystem creation (round 0). */
    private boolean firstInit;
    /** Last round number for which onRoundStart was called. */
    private int lastInitRound = -1;
    /** True after the first scan (opponent profile lookup done). */
    private boolean opponentProfileLoaded;

    /**
     * Initialize subsystems (once) and per-round state (every round).
     * Safe to call multiple times within the same round.
     */
    private void ensureInitialized() {
        int currentRound = getRoundNum();

        if (!firstInit) {
            firstInit = true;

            // Force class loading of envelope tables early
            ReachableEnvelope.ensureLoaded();

            whiteboard = new Whiteboard();
            transformer = createTransformer();
            gunManager = createGunManager();
            moveManager = createMoveManager();
            radarStrategy = new NarrowLockRadar();
            strategyComputer = new EnergyRatioStrategyComputer();

            // Register distribution predictors
            whiteboard.getPredictorRegistry().register(
                    IGfTargetingPredictor.class, new MlpGfTargeting());
            whiteboard.getPredictorRegistry().register(
                    IFingerprintPredictor.class, new GbmFingerprint());

            // Set up cross-battle persistence
            persistence = new PersistenceManager();
            persistence.register(gunManager);
            persistence.register(moveManager);

            // Load saved state from previous battle
            // Use FileInputStream via getDataFile — Robocode allows reading own data
            try {
                File dataFile = getDataFile(DATA_FILE_NAME);
                if (dataFile.exists() && dataFile.length() > 0) {
                    java.io.FileInputStream fis = new java.io.FileInputStream(dataFile);
                    try {
                        byte[] data = new byte[(int) dataFile.length()];
                        int offset = 0;
                        while (offset < data.length) {
                            int read = fis.read(data, offset, data.length - offset);
                            if (read < 0) break;
                            offset += read;
                        }
                        persistence.load(data);
                    } finally {
                        fis.close();
                    }
                }
            } catch (Exception e) {
                // Best-effort — proceed without saved data
            }
        }

        // Per-round initialisation
        if (currentRound != lastInitRound) {
            lastInitRound = currentRound;
            opponentProfileLoaded = false;

            whiteboard.onRoundStart(currentRound, (int) getBattleFieldWidth(),
                    (int) getBattleFieldHeight(), getGunCoolingRate(),
                    getNumRounds());
            gunManager.onRoundStart();
            moveManager.onRoundStart();
            currentParams = strategyComputer.compute(whiteboard);
        }
    }

    @Override
    public void run() {
        ensureInitialized();

        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true);

        while (true) {
            // Log model load status after scans have triggered lazy loading
            // (readable by Control API via getOutputStreamSnapshot)
            if (whiteboard.getTick() == 10 && firePowerPredictor != null) {
                if (firePowerPredictor.isModelLoaded()
                        && movementPredictor.isModelLoaded()
                        && fireTimingPredictor.isModelLoaded()) {
                    out.println("ML_MODELS_LOADED");
                } else {
                    out.println("ML_MODELS_FAILED"
                            + " fp=" + firePowerPredictor.isModelLoaded()
                            + " mv=" + movementPredictor.isModelLoaded()
                            + " ft=" + fireTimingPredictor.isModelLoaded());
                }
            }

            // Movement — every tick
            MovementCommand cmd = moveManager.getActiveCommand(whiteboard, currentParams);
            setAhead(cmd.ahead);
            setTurnRightRadians(cmd.turnRight);

            // Radar — every tick
            setTurnRadarRightRadians(radarStrategy.getRadarTurn(whiteboard));

            // Gun — aim toward selected strategy's angle
            setTurnGunRightRadians(gunManager.getGunTurnAngle(whiteboard));

            // Fire when ready
            if (gunManager.shouldFire(whiteboard) && getEnergy() > currentParams.firePowerBudget) {
                double firePower = currentParams.firePowerBudget;
                setFire(firePower);
                whiteboard.incrementOurShotsFired();
                whiteboard.setLastOurFire(whiteboard.getTick(), firePower);
                // Track our wave for multi-wave features
                double speed = 20.0 - 3.0 * firePower;
                double dist = whiteboard.hasFeature(Feature.DISTANCE)
                        ? whiteboard.getFeature(Feature.DISTANCE) : 0;
                // Bearing from us (firer) to opponent (target) at fire time
                double fireBearing = Math.atan2(
                        whiteboard.getOpponentX() - getX(),
                        whiteboard.getOpponentY() - getY());
                whiteboard.addOurWave(new WaveRecord(
                        getX(), getY(), speed, firePower,
                        whiteboard.getTick(), dist, fireBearing));
            }

            execute();
        }
    }

    @Override
    public void onStatus(StatusEvent e) {
        ensureInitialized();
        whiteboard.advanceTick();
        whiteboard.setTick(e.getStatus().getTime());
        whiteboard.setOurState(
                e.getStatus().getX(),
                e.getStatus().getY(),
                Math.toRadians(e.getStatus().getHeading()),
                Math.toRadians(e.getStatus().getGunHeading()),
                Math.toRadians(e.getStatus().getRadarHeading()),
                e.getStatus().getVelocity(),
                e.getStatus().getEnergy(),
                e.getStatus().getGunHeat()
        );

        // Feature interpolation on no-scan ticks (7m)
        interpolateIfNoScan();
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        // Compute absolute opponent position
        double absBearing = Math.toRadians(getHeading()) + Math.toRadians(e.getBearing());
        double oppX = getX() + e.getDistance() * Math.sin(absBearing);
        double oppY = getY() + e.getDistance() * Math.cos(absBearing);

        whiteboard.setOpponentScan(
                e.getName(),
                oppX,
                oppY,
                Math.toRadians(e.getHeading()),
                e.getVelocity(),
                e.getEnergy()
        );

        // Opponent profile lookup on first scan (7k)
        if (!opponentProfileLoaded) {
            opponentProfileLoaded = true;
            loadOpponentProfile();
        }

        // Features + scalar predictors
        transformer.process(whiteboard);

        // Virtual gun tracking
        gunManager.onScan(whiteboard, currentParams.firePowerBudget);

        // Periodic strategy refresh (every 50 ticks)
        if (whiteboard.getTick() % 50 == 0) {
            currentParams = strategyComputer.compute(whiteboard);
        }
    }

    @Override
    public void onBulletHit(BulletHitEvent e) {
        whiteboard.setWeHitOpponentThisTick(true);
        whiteboard.incrementOurBulletHitCount();
        double power = e.getBullet().getPower();
        whiteboard.addDamageDealt(Rules.getBulletDamage(power));
    }

    @Override
    public void onHitByBullet(HitByBulletEvent e) {
        whiteboard.incrementOpponentBulletHitCount();
        double power = e.getBullet().getPower();
        whiteboard.addDamageReceived(Rules.getBulletDamage(power));
    }

    @Override
    public void onWin(WinEvent e) {
        whiteboard.incrementRoundsWon();
    }

    @Override
    public void onDeath(DeathEvent e) {
        whiteboard.incrementRoundsLost();
    }

    @Override
    public void onRoundEnded(RoundEndedEvent e) {
        moveManager.onRoundEnd(whiteboard);
    }

    @Override
    public void onBattleEnded(BattleEndedEvent e) {
        // Cross-battle persistence: save state for next battle
        // Must use RobocodeFileOutputStream — raw FileOutputStream is blocked by sandbox
        if (persistence != null) {
            try {
                byte[] data = persistence.save();
                if (data.length > 0) {
                    robocode.RobocodeFileOutputStream rfos =
                            new robocode.RobocodeFileOutputStream(getDataFile(DATA_FILE_NAME));
                    try {
                        rfos.write(data);
                    } finally {
                        rfos.close();
                    }
                }
            } catch (Exception ex) {
                // Best-effort
            }
        }
    }

    // === Feature interpolation on no-scan ticks (7m) ===

    /**
     * When onScannedRobot doesn't fire (radar miss), opponent features are stale.
     * Extrapolate opponent position using last known velocity and heading,
     * and recompute basic spatial features (distance, bearing).
     */
    private void interpolateIfNoScan() {
        if (whiteboard.isScanAvailableThisTick()) {
            return;
        }
        long scanAge = whiteboard.getTick() - whiteboard.getLastScanTick();
        if (scanAge <= 0 || scanAge >= 5 || whiteboard.getLastScanTick() < 0) {
            return;
        }

        // Dead-reckoning: advance opponent position by one tick
        double oppVel = whiteboard.getOpponentVelocity();
        double oppHead = whiteboard.getOpponentHeading();
        double oppX = whiteboard.getOpponentX() + oppVel * Math.sin(oppHead);
        double oppY = whiteboard.getOpponentY() + oppVel * Math.cos(oppHead);
        whiteboard.interpolateOpponent(oppX, oppY);

        // Recompute basic spatial features
        double dx = oppX - whiteboard.getOurX();
        double dy = oppY - whiteboard.getOurY();
        double dist = Math.hypot(dx, dy);
        double bearing = Math.atan2(dx, dy);
        whiteboard.setFeature(Feature.DISTANCE, dist);
        whiteboard.setFeature(Feature.BEARING_TO_OPPONENT_ABS,
                RoboMath.normalAbsoluteAngle(bearing));
    }

    // === Opponent profile lookup (7k) ===

    /**
     * On the first scan, hash the opponent name and look up their
     * offline strength rating from OpponentProfileData.
     */
    private void loadOpponentProfile() {
        String botId = whiteboard.getOpponentBotId();
        if (botId == null || botId.isEmpty()) {
            return;
        }
        int botIdHash = IdentityFeatures.fnv1a32(botId);
        double strength = OpponentProfileData.getStrengthRating(botIdHash);
        whiteboard.setFeature(Feature.OPPONENT_STRENGTH_RATING, strength);
    }

    // === Factory methods ===

    private Transformer createTransformer() {
        Transformer t = new Transformer();
        t.register(new PositionFeatures());
        t.register(new SpatialFeatures());
        t.register(new MovementFeatures());
        t.register(new EnergyFeatures());
        t.register(new TimingFeatures());
        t.register(new IdentityFeatures());
        t.register(new TargetingFeatures());
        t.register(new MultiWaveFeatures());
        t.register(new EnvelopeFeatures());
        t.register(new CombatProgressFeatures());
        // 20-tick sliding window statistics (key for ML models)
        t.register(new WindowFeatures());
        // Distilled ML predictors
        firePowerPredictor = new GbmFirePowerPredictor();
        movementPredictor = new GbmMovementPredictor();
        fireTimingPredictor = new GbmFireTimingPredictor();
        t.register(firePowerPredictor);
        t.register(movementPredictor);
        t.register(fireTimingPredictor);
        t.resolveDependencies();
        return t;
    }

    private static VirtualGunManager createGunManager() {
        List<IGunStrategy> strategies = new ArrayList<IGunStrategy>();
        strategies.add(new HeadOnGun());
        strategies.add(new LinearGun());
        strategies.add(new CircularGun());
        strategies.add(new VcsGun());
        return new VirtualGunManager(strategies);
    }

    private MovementStrategyManager createMoveManager() {
        List<IMovementStrategy> strategies = new ArrayList<IMovementStrategy>();
        strategies.add(new OrbitalMovement());
        strategies.add(new RandomDodgeMovement());
        strategies.add(new StopAndGoMovement());
        // Wave-surf: uses PathPlanner with VCS-based wave danger
        PathPlanner planner = new PathPlanner(
                new WallDistancePositionDanger(),
                new VcsWaveDanger(),
                (int) getBattleFieldWidth(), (int) getBattleFieldHeight());
        strategies.add(new WaveSurfMovement(planner));
        return new MovementStrategyManager(strategies);
    }
}

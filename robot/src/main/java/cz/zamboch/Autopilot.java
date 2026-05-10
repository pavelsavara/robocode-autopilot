package cz.zamboch;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Transformer;
import cz.zamboch.autopilot.core.WaveRecord;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.CombatProgressFeatures;
import cz.zamboch.autopilot.core.features.EnergyFeatures;
import cz.zamboch.autopilot.core.features.EnvelopeFeatures;
import cz.zamboch.autopilot.core.features.IdentityFeatures;
import cz.zamboch.autopilot.core.features.MlDerivedFeatures;
import cz.zamboch.autopilot.core.features.MovementFeatures;
import cz.zamboch.autopilot.core.features.MultiWaveFeatures;
import cz.zamboch.autopilot.core.features.PositionFeatures;
import cz.zamboch.autopilot.core.features.SpatialFeatures;
import cz.zamboch.autopilot.core.features.TargetingFeatures;
import cz.zamboch.autopilot.core.features.ScanCoverageFeatures;
import cz.zamboch.autopilot.core.features.TimingFeatures;
import cz.zamboch.autopilot.core.features.WindowFeatures;
import cz.zamboch.autopilot.core.gun.CircularGun;
import cz.zamboch.autopilot.core.gun.HeadOnGun;
import cz.zamboch.autopilot.core.gun.LinearGun;
import cz.zamboch.autopilot.core.gun.VcsGun;
import cz.zamboch.autopilot.core.gun.VcsSamplingGun;
import cz.zamboch.autopilot.core.gun.VirtualGunManager;
import cz.zamboch.autopilot.core.movement.MovementStrategyManager;
import cz.zamboch.autopilot.core.movement.PathPlanner;
import cz.zamboch.autopilot.core.movement.VcsWaveDanger;
import cz.zamboch.autopilot.core.movement.WallDistancePositionDanger;
import cz.zamboch.autopilot.core.movement.WaveSurfMovement;
import cz.zamboch.autopilot.core.persistence.PersistenceManager;
import cz.zamboch.autopilot.core.physics.ReachableEnvelope;
import cz.zamboch.autopilot.core.predictors.IGfTargetingPredictor;
import cz.zamboch.autopilot.core.strategy.IGunStrategy;
import cz.zamboch.autopilot.core.strategy.IMovementStrategy;
import cz.zamboch.autopilot.core.strategy.IRadarStrategy;
import cz.zamboch.autopilot.core.strategy.MovementCommand;
import cz.zamboch.autopilot.core.strategy.StrategyComputer;
import cz.zamboch.autopilot.core.strategy.StrategyParams;
import cz.zamboch.autopilot.core.util.RoboMath;
import cz.zamboch.distilled.GbmFirePowerPredictor;
import cz.zamboch.distilled.GbmFireTimingPredictor;
import cz.zamboch.distilled.GbmMovementPredictor;
import cz.zamboch.distilled.DefaultDataFile;
import cz.zamboch.distilled.MlpGfTargeting;
import cz.zamboch.distilled.PredictiveGun;
import cz.zamboch.trivial.EnergyRatioStrategyComputer;
import cz.zamboch.trivial.NarrowLockRadar;
import cz.zamboch.trivial.OpponentProfileData;
import cz.zamboch.trivial.OrbitalMovement;
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

    // Keep references to predictors for model-load diagnostics and throttling
    private GbmFirePowerPredictor firePowerPredictor;
    private GbmMovementPredictor movementPredictor;
    private GbmFireTimingPredictor fireTimingPredictor;

    /** Adaptive CPU budget: tracks tick time, throttles tree count on skipped turns. */
    private cz.zamboch.autopilot.core.ml.TickBudget tickBudget;

    /** Per-opponent VCS histogram store for cross-battle warm-start. */
    private cz.zamboch.autopilot.core.persistence.VcsHistogramStore vcsStore;

    /** True after one-time subsystem creation (round 0). */
    private boolean firstInit;
    /** Last round number for which onRoundStart was called. */
    private int lastInitRound = -1;
    /** True after the first scan (opponent profile lookup done). */
    private boolean opponentProfileLoaded;

    /** Write a debug message. Captured in .br via out.println(), extracted by pipeline to debug.log. */
    private void log(String msg) {
        out.println(msg);
    }

    /**
     * Initialize subsystems (once) and per-round state (every round).
     * Safe to call multiple times within the same round.
     */
    private void ensureInitialized() {
        int currentRound = getRoundNum();

        if (!firstInit) {
            firstInit = true;

            // Force class loading of envelope tables and ML models early
            // (static initializers decode Base64 model data — must happen before first tick)
            ReachableEnvelope.ensureLoaded();
            GbmFirePowerPredictor.ensureLoaded();
            GbmMovementPredictor.ensureLoaded();
            GbmFireTimingPredictor.ensureLoaded();

            whiteboard = new Whiteboard();
            transformer = createTransformer();
            gunManager = createGunManager();
            moveManager = createMoveManager();
            radarStrategy = new NarrowLockRadar();
            strategyComputer = new EnergyRatioStrategyComputer();
            tickBudget = new cz.zamboch.autopilot.core.ml.TickBudget(200);
            vcsStore = new cz.zamboch.autopilot.core.persistence.VcsHistogramStore();

            // Register distribution predictors
            whiteboard.getPredictorRegistry().register(
                    IGfTargetingPredictor.class, new MlpGfTargeting());

            // Set up cross-battle persistence
            persistence = new PersistenceManager();
            persistence.register(gunManager);
            persistence.register(moveManager);
            persistence.register(tickBudget);
            persistence.register(vcsStore);

            // Load saved state from previous battle
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
                        String status = persistence.loadWithStatus(data);
                        log("DATA_LOAD " + dataFile.length() + "b: " + status);
                    } finally {
                        fis.close();
                    }
                } else {
                    // No data file — try embedded fallback from training battles
                    byte[] embedded = DefaultDataFile.decode();
                    if (embedded.length > 0) {
                        String status = persistence.loadWithStatus(embedded);
                        log("DATA_LOAD embedded " + embedded.length + "b: " + status);
                    } else {
                        log("DATA_LOAD no file, no embedded fallback");
                    }
                }
            } catch (Exception e) {
                log("DATA_LOAD error: " + e.getMessage());
            }

            // Initialize VCS gun histogram with Gaussian prior at GF=0
            // so the VCS gun has reasonable aim before observing actual hits
            whiteboard.initVcsPrior(3);
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
            // Movement — every tick (skip before first scan to avoid NaN)
            MovementCommand cmd;
            if (whiteboard.getLastScanTick() < 0) {
                cmd = new MovementCommand();
                cmd.set(0, 0);
            } else {
                cmd = moveManager.getActiveCommand(whiteboard, currentParams);
            }
            // Sanity: guard against NaN in movement output
            if (Double.isNaN(cmd.ahead) || Double.isNaN(cmd.turnRight)) {
                log("WARN NaN_MOVE tick=" + whiteboard.getTick());
                cmd.set(0, 0);
            }
            lastMoveAhead = cmd.ahead;
            lastMoveTurn = cmd.turnRight;
            setAhead(cmd.ahead);
            setTurnRightRadians(cmd.turnRight);

            // Radar — every tick
            setTurnRadarRightRadians(radarStrategy.getRadarTurn(whiteboard));

            // Gun — aim toward selected strategy's angle
            double gunTurn = gunManager.getGunTurnAngle(whiteboard);
            // Sanity: guard against NaN in gun output
            if (Double.isNaN(gunTurn)) {
                log("WARN NaN_GUN tick=" + whiteboard.getTick());
                gunTurn = 0;
            }
            setTurnGunRightRadians(gunTurn);

            // Fire when ready — clamp power to what we can afford
            double firePower = currentParams.firePowerBudget;
            if (firePower > getEnergy() - 0.1) {
                firePower = Math.max(0.1, getEnergy() - 0.1);
            }
            if (gunManager.shouldFire(whiteboard) && getEnergy() > 0.2) {
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
                // Opponent lateral direction at fire time (for VCS segmentation)
                int fireLateralDir = whiteboard.hasFeature(Feature.OPPONENT_LATERAL_DIRECTION)
                        ? (int) whiteboard.getFeature(Feature.OPPONENT_LATERAL_DIRECTION) : 1;
                // Opponent absolute velocity at fire time (for VCS velocity segmentation)
                double oppAbsVel = whiteboard.hasFeature(Feature.OPPONENT_VELOCITY)
                        ? Math.abs(whiteboard.getFeature(Feature.OPPONENT_VELOCITY)) : 8.0;
                whiteboard.addOurWave(new WaveRecord(
                        getX(), getY(), speed, firePower,
                        whiteboard.getTick(), dist, fireBearing, fireLateralDir, oppAbsVel));
            }

            // Emit structured tick log for internal.csv extraction
            emitTickLog();

            execute();
            tickBudget.tickEnd();
        }
    }

    @Override
    public void onStatus(StatusEvent e) {
        ensureInitialized();
        tickBudget.tickStart();
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

        // Sanity: guard against NaN from scan event
        if (Double.isNaN(oppX) || Double.isNaN(oppY) || Double.isNaN(e.getEnergy())) {
            log("WARN NaN_SCAN tick=" + whiteboard.getTick());
            return;
        }

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

        // Push adaptive tree budget to predictors before inference
        int budget = tickBudget.getBudget();
        firePowerPredictor.setMaxTrees(budget);
        movementPredictor.setMaxTrees(budget);
        fireTimingPredictor.setMaxTrees(budget);

        // Features + scalar predictors
        transformer.process(whiteboard);

        // Virtual gun tracking
        gunManager.onScan(whiteboard, currentParams.firePowerBudget);

        // Refresh strategy every scan (compute() is pure arithmetic, negligible cost)
        currentParams = strategyComputer.compute(whiteboard);

        // Store fire power budget on whiteboard so TargetingFeatures can use it next tick
        whiteboard.setCurrentFirePowerBudget(currentParams.firePowerBudget);
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
    public void onSkippedTurn(robocode.SkippedTurnEvent e) {
        if (tickBudget != null) {
            tickBudget.onSkippedTurn(getRoundNum(), e.getSkippedTurn());
            log("SKIPPED tick=" + e.getSkippedTurn()
                    + " round=" + getRoundNum()
                    + " budget=" + tickBudget.getBudget()
                    + " ceiling=" + tickBudget.getCeiling()
                    + " lastMicros=" + tickBudget.getLastTickMicros());
        }
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
        // Close feature logger before persistence save
        if (firePowerPredictor != null) {
            firePowerPredictor.closeLogger();
        }

        // Save current VCS histograms for this opponent before persisting
        if (vcsStore != null && whiteboard.getOpponentBotId() != null) {
            int hash = IdentityFeatures.fnv1a32(whiteboard.getOpponentBotId());
            vcsStore.saveFrom(hash, whiteboard);
        }

        // Cross-battle persistence: save state for next battle
        if (persistence != null) {
            try {
                byte[] data = persistence.save();
                if (data.length > 0) {
                    File dataFile = getDataFile(DATA_FILE_NAME);
                    robocode.RobocodeFileOutputStream rfos =
                            new robocode.RobocodeFileOutputStream(dataFile);
                    try {
                        rfos.write(data);
                    } finally {
                        rfos.close();
                    }
                    log("DATA_SAVE " + data.length + "b to " + dataFile.getName()
                            + " budget=" + tickBudget.getBudget()
                            + " ceiling=" + tickBudget.getCeiling());
                }
            } catch (Exception ex) {
                log("DATA_SAVE error: " + ex.getMessage());
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
        try {
            double strength = OpponentProfileData.getStrengthRating(botIdHash);
            whiteboard.setFeature(Feature.OPPONENT_STRENGTH_RATING, strength);
        } catch (Exception e) {
            log("WARN OPPONENT_PROFILE decode failed: " + e.getMessage());
        }

        // Warm-start VCS histograms from cross-battle store
        if (vcsStore != null && vcsStore.loadInto(botIdHash, whiteboard)) {
            log("VCS_LOAD opponent=" + botId + " entries=" + vcsStore.size());
        }
    }

    // === Factory methods ===

    // === Structured tick logging for internal.csv ===

    /**
     * Emit one CSV row per scan tick via {@code out.println("TICK,...")}.
     * Captured in .br recording, extracted by pipeline to internal.csv.
     * Header is generated by the pipeline from {@link Feature} enum.
     */
    private void emitTickLog() {
        if (!whiteboard.isScanAvailableThisTick()) {
            return;
        }
        Feature[] allFeatures = Feature.values();

        StringBuilder row = new StringBuilder(512);
        row.append("TICK,");
        row.append(whiteboard.getRound()).append(',');
        row.append(whiteboard.getTick());
        for (Feature f : allFeatures) {
            row.append(',');
            if (whiteboard.hasFeature(f)) {
                double v = whiteboard.getFeature(f);
                if (v == Math.floor(v) && !Double.isInfinite(v) && Math.abs(v) < 1e9) {
                    row.append((long) v);
                } else {
                    row.append(Math.round(v * 10000.0) / 10000.0);
                }
            }
        }
        row.append(',').append(gunManager.getSelectedIndex());
        row.append(',').append(moveManager.getActiveIndex());
        row.append(',').append(Math.round(currentParams.firePowerBudget * 1000.0) / 1000.0);
        row.append(',').append(Math.round(currentParams.aggression * 1000.0) / 1000.0);
        for (int i = 0; i < gunManager.getStrategyCount(); i++) {
            row.append(',').append(Math.round(gunManager.getHitRateOf(i) * 10000.0) / 10000.0);
        }
        row.append(',').append(Math.round(lastMoveAhead * 100.0) / 100.0);
        row.append(',').append(Math.round(lastMoveTurn * 10000.0) / 10000.0);
        out.println(row.toString());
    }

    /** Cached movement command values for tick logging (avoids re-computing). */
    private double lastMoveAhead;
    private double lastMoveTurn;

    // === Factory methods (continued) ===

    private Transformer createTransformer() {
        Transformer t = new Transformer();
        t.register(new PositionFeatures());
        t.register(new SpatialFeatures());
        t.register(new MovementFeatures());
        t.register(new EnergyFeatures());
        t.register(new TimingFeatures());
        t.register(new IdentityFeatures());
        // ML-derived features: state normalisation, geometry, segmentation, wave/fire,
        // movement history — previously pipeline-only, now computed in-game for ML models
        t.register(new MlDerivedFeatures());
        t.register(new TargetingFeatures());
        t.register(new MultiWaveFeatures());
        t.register(new EnvelopeFeatures());
        t.register(new CombatProgressFeatures());
        // 20-tick sliding window statistics (key for ML models)
        t.register(new WindowFeatures());
        // Scan coverage features (radar quality metrics for future model retraining)
        t.register(new ScanCoverageFeatures());
        // Distilled ML predictors — eagerly loaded at init, not lazily on first scan
        firePowerPredictor = new GbmFirePowerPredictor();
        movementPredictor = new GbmMovementPredictor();
        fireTimingPredictor = new GbmFireTimingPredictor();
        firePowerPredictor.loadModel();
        movementPredictor.loadModel();
        fireTimingPredictor.loadModel();
        // Initialize feature logger for fire power diagnostics (zero cost when disabled)
        firePowerPredictor.initLogger(getDataDirectory());
        // Log eager load results immediately
        log("ML_EAGER_LOAD fp=" + firePowerPredictor.isModelLoaded()
                + " mv=" + movementPredictor.isModelLoaded()
                + " ft=" + fireTimingPredictor.isModelLoaded());
        t.register(firePowerPredictor);
        t.register(movementPredictor);
        t.register(fireTimingPredictor);
        t.resolveDependencies();
        return t;
    }

    private static VirtualGunManager createGunManager() {
        List<IGunStrategy> strategies = new ArrayList<IGunStrategy>();
        // Order = priority for tie-breaking: lower index wins within epsilon.
        // Decision #10: CircularGun primary, HeadOnGun last.
        strategies.add(new CircularGun());       // 0 — best general-purpose
        strategies.add(new VcsGun());            // 1 — learns opponent patterns (peak-firing)
        strategies.add(new VcsSamplingGun());    // 2 — anti-profiling (probabilistic GF sampling)
        strategies.add(new PredictiveGun());     // 3 — ML-based prediction
        strategies.add(new LinearGun());         // 4 — good against smooth movers
        strategies.add(new HeadOnGun());         // 5 — stationary/slow opponents
        return new VirtualGunManager(strategies);
    }

    private MovementStrategyManager createMoveManager() {
        List<IMovementStrategy> strategies = new ArrayList<IMovementStrategy>();
        // WaveSurf FIRST — better than orbital (33% vs 81% opponent HR)
        VcsWaveDanger waveDanger = new VcsWaveDanger();
        PathPlanner planner = new PathPlanner(
                new WallDistancePositionDanger(),
                waveDanger,
                (int) getBattleFieldWidth(), (int) getBattleFieldHeight());
        strategies.add(new WaveSurfMovement(planner, waveDanger));
        strategies.add(new OrbitalMovement());
        return new MovementStrategyManager(strategies);
    }
}

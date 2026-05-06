package cz.zamboch;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Transformer;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.gun.VirtualGunManager;
import cz.zamboch.autopilot.core.movement.KeepAllPruner;
import cz.zamboch.autopilot.core.movement.MovementStrategyManager;
import cz.zamboch.autopilot.core.movement.PathPlanner;
import cz.zamboch.autopilot.core.movement.UniformWaveDanger;
import cz.zamboch.autopilot.core.movement.WallDistancePositionDanger;
import cz.zamboch.autopilot.core.movement.WaveSurfMovement;
import cz.zamboch.autopilot.core.predictors.IFingerprintPredictor;
import cz.zamboch.autopilot.core.predictors.IGfTargetingPredictor;
import cz.zamboch.autopilot.core.strategy.IGunStrategy;
import cz.zamboch.autopilot.core.strategy.IMovementStrategy;
import cz.zamboch.autopilot.core.strategy.IRadarStrategy;
import cz.zamboch.autopilot.core.strategy.MovementCommand;
import cz.zamboch.autopilot.core.strategy.StrategyComputer;
import cz.zamboch.autopilot.core.strategy.StrategyParams;
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
import cz.zamboch.autopilot.core.physics.ReachableEnvelope;
import cz.zamboch.trivial.*;
import robocode.AdvancedRobot;
import robocode.BulletHitEvent;
import robocode.DeathEvent;
import robocode.HitByBulletEvent;
import robocode.RoundEndedEvent;
import robocode.ScannedRobotEvent;
import robocode.StatusEvent;
import robocode.WinEvent;
import robocode.Rules;

import java.util.ArrayList;
import java.util.List;

/**
 * Competition robot. Wires predictors → strategy → gun/movement/radar.
 * Phase 1: trivial predictors (random/constant) behind real interfaces.
 */
public final class Autopilot extends AdvancedRobot {

    private Whiteboard whiteboard;
    private Transformer transformer;
    private VirtualGunManager gunManager;
    private MovementStrategyManager moveManager;
    private IRadarStrategy radarStrategy;
    private StrategyComputer strategyComputer;
    private StrategyParams currentParams;
    private boolean initialized;

    /**
     * Initialize all subsystems. Called once per round, from whichever
     * event fires first (onStatus or run). Safe to call multiple times.
     */
    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;

        // Force class loading of envelope tables early
        ReachableEnvelope.ensureLoaded();

        whiteboard = new Whiteboard();
        transformer = createTransformer();
        gunManager = createGunManager();
        moveManager = createMoveManager();
        radarStrategy = new NarrowLockRadar();
        strategyComputer = new TrivialStrategyComputer();

        // Register distribution predictors
        whiteboard.getPredictorRegistry().register(
                IGfTargetingPredictor.class, new TrivialGfTargeting());
        whiteboard.getPredictorRegistry().register(
                IFingerprintPredictor.class, new TrivialFingerprint());

        whiteboard.onRoundStart(getRoundNum(), (int) getBattleFieldWidth(),
                (int) getBattleFieldHeight(), getGunCoolingRate(),
                getNumRounds());
        currentParams = strategyComputer.compute(whiteboard);
    }

    @Override
    public void run() {
        ensureInitialized();

        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true);

        while (true) {
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
                whiteboard.addOurWave(new cz.zamboch.autopilot.core.WaveRecord(
                        getX(), getY(), speed, firePower,
                        whiteboard.getTick(), dist));
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

    private Transformer createTransformer() {
        Transformer t = new Transformer();
        // Existing feature processors
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
        // Scalar predictors (participate in dependency chain)
        t.register(new TrivialFirePowerPredictor());
        t.register(new TrivialMovementPredictor());
        t.register(new TrivialFireTimingPredictor());
        t.resolveDependencies();
        return t;
    }

    private static VirtualGunManager createGunManager() {
        List<IGunStrategy> strategies = new ArrayList<IGunStrategy>();
        strategies.add(new HeadOnGun());
        strategies.add(new LinearGun());
        strategies.add(new CircularGun());
        strategies.add(new RandomGfGun());
        return new VirtualGunManager(strategies);
    }

    private MovementStrategyManager createMoveManager() {
        List<IMovementStrategy> strategies = new ArrayList<IMovementStrategy>();
        strategies.add(new OrbitalMovement());
        strategies.add(new RandomDodgeMovement());
        strategies.add(new StopAndGoMovement());
        // Wave-surf: uses PathPlanner with hand-tuned danger
        PathPlanner planner = new PathPlanner(
                new WallDistancePositionDanger(),
                new UniformWaveDanger(),
                new KeepAllPruner(),
                (int) getBattleFieldWidth(), (int) getBattleFieldHeight());
        strategies.add(new WaveSurfMovement(planner));
        return new MovementStrategyManager(strategies);
    }
}

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
import robocode.RoundEndedEvent;
import robocode.Rules;
import robocode.ScannedRobotEvent;
import robocode.StatusEvent;

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
    /** Observer instances must never persist the VCS to disk. */
    private boolean isObserver;
    /** Observer-mode opponent identity / load gate (the observer is a single
     *  long-lived instance, so instance fields naturally survive across rounds). */
    private int opponentHash;
    private boolean vcsLoaded;

    // ---- Live-mode per-battle state ----------------------------------------
    // Robocode re-instantiates the live robot every round, but the robot class
    // loader (and therefore these statics) lives for the whole battle and is
    // discarded at battle end. So these survive between rounds yet reset for a
    // fresh battle/opponent. This lets the VCS model be loaded once per battle and
    // keep accumulating across rounds, and lets round 2+ know the opponent (and
    // attach the learned model) before the first scan.
    private static int sLiveOpponentHash;
    private static boolean sLiveVcsLoaded;
    private static VcsStore sLiveVcsStore;

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
     * Features that persist across ring rotations but are NOT reset on scan ticks.
     */
    private static final Feature[] STICKY_FEATURES = {
            Feature.LAST_SCAN_TICK,
            Feature.BATTLEFIELD_WIDTH,
            Feature.BATTLEFIELD_HEIGHT
    };

    /**
     * Copy accumulator and sticky values from current slot before ring rotation
     * clears them.
     */
    private void carryForwardAccumulators() {
        for (Feature f : ACCUMULATOR_FEATURES) {
            double val = wb.getFeature(f);
            if (!Double.isNaN(val) && val != 0) {
                accumulatorCarry[f.ordinal()] = val;
            }
        }
        for (Feature f : STICKY_FEATURES) {
            double val = wb.getFeature(f);
            if (!Double.isNaN(val)) {
                accumulatorCarry[f.ordinal()] = val;
            }
        }
    }

    /** Restore carried-forward values into the new (cleared) ring slot. */
    private void restoreAccumulators() {
        for (Feature f : ACCUMULATOR_FEATURES) {
            double val = accumulatorCarry[f.ordinal()];
            if (val != 0) {
                wb.setFeature(f, val);
                accumulatorCarry[f.ordinal()] = 0;
            }
        }
        for (Feature f : STICKY_FEATURES) {
            double val = accumulatorCarry[f.ordinal()];
            if (!Double.isNaN(val) && val != 0) {
                wb.setFeature(f, val);
            }
            accumulatorCarry[f.ordinal()] = 0;
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

        // Opponent identity — always set name (survives clearFeatures between rounds)
        wb.setStringFeature(Feature.OPPONENT_ID, event.getName());

        // VCS loading — once per battle, keyed by opponent. The hash is recomputed on
        // every scan (cheap) so a stale per-battle static carried over from a previous
        // opponent self-heals into a correct (re)load.
        String name = event.getName();
        int sp = name.indexOf(' ');
        String botId = (sp < 0) ? name : name.substring(0, sp);
        int hash = RoboMath.fnv1a32(botId);
        if (!isVcsLoaded() || currentOpponentHash() != hash) {
            VcsStore store = VcsFile.loadForOpponent(getDataFile("vcs.dat"), hash);
            attachVcsStore(hash, store);
        }
    }

    @Override
    public void onRoundEnded(RoundEndedEvent event) {
        // Persist accumulated learning at the end of every round so the next round
        // (and future battles vs the same opponent) build on it.
        persistVcs();
    }

    @Override
    public void onBattleEnded(BattleEndedEvent event) {
        persistVcs();
    }

    /** Whether the VCS model for the current opponent has been loaded. */
    private boolean isVcsLoaded() {
        return isObserver ? vcsLoaded : sLiveVcsLoaded;
    }

    /** Opponent hash the currently-loaded VCS model belongs to. */
    private int currentOpponentHash() {
        return isObserver ? opponentHash : sLiveOpponentHash;
    }

    /**
     * Attach a VCS store to the whiteboard with a fresh {@link ModelSelector}.
     * The store accumulates across rounds; the ModelSelector's regret window is
     * fresh each round (the live robot gets a new ModelSelector per re-instantiation,
     * the observer recreates one in {@link #resetForRound()}).
     */
    private void attachVcsStore(int hash, VcsStore store) {
        wb.setVcsStore(store);
        wb.setModelSelector(new ModelSelector(store));
        if (isObserver) {
            opponentHash = hash;
            vcsLoaded = true;
        } else {
            sLiveOpponentHash = hash;
            sLiveVcsStore = store;
            sLiveVcsLoaded = true;
        }
    }

    /**
     * Live mode only: re-attach the per-battle accumulating VCS store at round start,
     * before the first scan, so round 2+ targeting benefits from prior-round learning
     * immediately.
     */
    private void attachKnownVcs() {
        if (isObserver) {
            return;
        }
        if (sLiveVcsLoaded && sLiveVcsStore != null) {
            wb.setVcsStore(sLiveVcsStore);
            wb.setModelSelector(new ModelSelector(sLiveVcsStore));
        }
    }

    /** Persist the accumulated VCS model (live mode only; observers never write). */
    private void persistVcs() {
        if (isObserver) {
            return;
        }
        if (sLiveVcsLoaded && sLiveVcsStore != null) {
            try {
                VcsFile.saveForOpponent(getDataFile("vcs.dat"), sLiveOpponentHash, sLiveVcsStore);
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
     * The peer must be an {@link cz.zamboch.autopilot.pipeline.ObserverRobotPeer}
     * (set via setPeer before calling this).
     * Call event handlers externally, then call {@link #doTurn()} each tick.
     */
    public void initForObserver(VcsStore vcsStore, double bfWidth, double bfHeight) {
        this.isObserver = true;
        initCommon(bfWidth, bfHeight);

        // Load VCS if provided
        if (vcsStore != null) {
            wb.setVcsStore(vcsStore);
            wb.setModelSelector(new ModelSelector(vcsStore));
            vcsLoaded = true;
        }
    }

    /**
     * Reset per-battle/per-round learned state to fresh-instance baseline (observer
     * mode only). The live robot is re-instantiated by Robocode every round, so it
     * reloads the vcs.dat baseline and resets all strategy state each round. The
     * observer is a single long-lived instance, so it must explicitly mirror that
     * fresh-instance behavior to stay a faithful shadow — otherwise cross-round
     * learning (VCS model) and stateful strategy fields (radar lock direction)
     * diverge from the live robot in rounds 2+, shifting gun aim and fire timing.
     * <p>
     * Reloading is deferred to the first {@code onScannedRobot} of the round (the
     * {@link #vcsLoaded} gate), exactly like the live robot.
     */
    /**
     * Reset per-round strategy state to fresh-instance baseline (observer mode only).
     * The live robot is re-instantiated by Robocode every round, which resets its
     * stateful strategy fields (e.g. radar lock direction) AND gives it a fresh
     * {@link ModelSelector} regret window — while the per-battle VCS counts persist
     * via a static. The observer is a single long-lived instance, so it must mirror
     * that: recreate the strategies and the ModelSelector, but KEEP the accumulating
     * {@link VcsStore} so cross-round learning matches the live robot exactly.
     */
    public void resetForRound() {
        // VCS counts accumulate across rounds — do NOT clear the store. Recreate only
        // the ModelSelector so its regret window starts fresh (mirrors the live
        // robot's new ModelSelector per re-instantiation).
        VcsStore store = wb.getVcsStore();
        if (store != null) {
            wb.setModelSelector(new ModelSelector(store));
        }
        // Recreate strategies so their internal cross-round state (e.g.
        // NarrowLockRadar.lastTurnDirection) resets to its fresh-instance default.
        radar = new NarrowLockRadar(wb);
        gun = new GFGunStrategy(wb);
        movement = new OrbitMovementStrategy(wb);
        // Clear any carried-forward accumulator/sticky values from the prior round.
        java.util.Arrays.fill(accumulatorCarry, 0.0);
    }

    private boolean featuresRegistered;

    /** Shared initialization for both live and observer modes. */
    private void initCommon(double bfWidth, double bfHeight) {
        if (!featuresRegistered) {
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
            featuresRegistered = true;
        }

        wb.setFeature(Feature.BATTLEFIELD_WIDTH, bfWidth);
        wb.setFeature(Feature.BATTLEFIELD_HEIGHT, bfHeight);

        radar = new NarrowLockRadar(wb);
        gun = new GFGunStrategy(wb);
        movement = new OrbitMovementStrategy(wb);
    }

    /** Access the whiteboard (for observer/pipeline integration). */
    public Whiteboard getWhiteboard() {
        return wb;
    }

    @Override
    public void run() {
        initCommon(getBattleFieldWidth(), getBattleFieldHeight());
        // Round 2+ of the same battle: the opponent and its accumulated VCS model are
        // already known from a prior round via the per-battle static, so attach the
        // model now — before the first scan — so targeting benefits immediately.
        attachKnownVcs();

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
        setTurnRadarRightRadians(radar.getRadarTurn());

        // Movement
        movement.getCommand(moveCmd);
        setTurnRightRadians(moveCmd.turnRight);
        setAhead(moveCmd.ahead);

        // Gun — unified code path for both live and observer mode.
        // In live mode: setTurnGunRightRadians → peer stores radians;
        // getGunTurnRemaining() returns degrees (AdvancedRobot converts).
        // In observer mode: setTurnGunRightRadians → ObserverRobotPeer stores radians;
        // getGunTurnRemaining() returns radians (same internal storage),
        // AdvancedRobot.getGunTurnRemaining() wraps with toDegrees().
        // Both paths: check < 5 degrees, call setFireBullet which checks gun heat.
        gun.getFireCommand(fireCmd);
        if (!Double.isNaN(fireCmd.angle)) {
            double gunHeading = wb.getFeature(Feature.GUN_HEADING);
            double gunTurn = fireCmd.angle - gunHeading;
            setTurnGunRightRadians(RoboMath.normalRelativeAngle(gunTurn));
            if (fireCmd.power > 0 && Math.abs(getGunTurnRemaining()) < 5) {
                Bullet bullet = setFireBullet(fireCmd.power);
                if (bullet != null) {
                    snapshotFireFeatures(fireCmd.power, bullet.hashCode());
                }
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

        // Debug — every alive wave's columns, keyed COLUMN/waveId, so Layer 0
        // fidelity can compare the in-flight wave set against the observer shadow.
        wb.forEachAliveWaveProperty(this::setDebugProperty);
        // Break columns (RES_*) of waves that resolved this tick — the only Layer 0
        // coverage of the virtual waves' break geometry (gone from the alive set).
        wb.forEachJustResolvedWaveBreak(this::setDebugProperty);
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

        // Aim-time geometry: the gun was aimed reacting to the previous tick's
        // world state (T-1), one tick before this fire tick (T). Attribute the
        // aiming decision to that tick by snapshotting the previous-tick features.
        wb.setFeature(Feature.OUR_AIM_X, wb.getPreviousTickFeature(Feature.OUR_X));
        wb.setFeature(Feature.OUR_AIM_Y, wb.getPreviousTickFeature(Feature.OUR_Y));
        wb.setFeature(Feature.OUR_AIM_OPPONENT_X, wb.getPreviousTickFeature(Feature.OPPONENT_X));
        wb.setFeature(Feature.OUR_AIM_OPPONENT_Y, wb.getPreviousTickFeature(Feature.OPPONENT_Y));
        wb.setFeature(Feature.OUR_AIM_DISTANCE, wb.getPreviousTickFeature(Feature.DISTANCE));
        wb.setFeature(Feature.OUR_AIM_BEARING_ABSOLUTE,
                wb.getPreviousTickFeature(Feature.OPPONENT_BEARING_ABSOLUTE));
    }

}

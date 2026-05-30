package cz.zamboch.autopilot.pipeline;

import cz.zamboch.Autopilot;
import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.ModelSelector;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.VcsStore;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.IdentityFeatures;
import cz.zamboch.autopilot.core.features.MovementFeatures;
import cz.zamboch.autopilot.core.features.SpatialFeatures;
import cz.zamboch.autopilot.core.features.TimingFeatures;
import net.sf.robocode.security.HiddenAccess;
import robocode.*;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds one observer Autopilot instance + its Whiteboard + per-round state.
 * Two ObserverContexts form a pair (one per perspective in a 1v1 battle).
 */
public final class ObserverContext {

    private final int perspectiveIndex; // 0 or 1
    private final Autopilot observer;
    private final ObserverRobotPeer peer;
    private final EventReconstructor reconstructor;
    private final double bfWidth;
    private final double bfHeight;
    /**
     * Separate whiteboard for the god-view (engine ground-truth) wave resolver.
     * It owns an INDEPENDENT VcsStore + ModelSelector so that god-view wave
     * resolution never trains the observer's robot-side model (which must stay a
     * faithful shadow of the live robot for Layer 0 fidelity).
     */
    private final Whiteboard godWb;
    private ObserverContext peerContext; // the other perspective
    private boolean dead;
    private boolean diedThisTick;

    public ObserverContext(int perspectiveIndex, double bfWidth, double bfHeight, double gunCoolingRate) {
        this.perspectiveIndex = perspectiveIndex;
        this.bfWidth = bfWidth;
        this.bfHeight = bfHeight;
        this.observer = new Autopilot();
        this.peer = new ObserverRobotPeer(bfWidth, bfHeight, gunCoolingRate);
        this.reconstructor = new EventReconstructor();

        observer.setPeer(peer);
        observer.initForObserver(null, bfWidth, bfHeight);

        // God-view whiteboard with its own independent model.
        this.godWb = new Whiteboard();
        VcsStore godVcs = new VcsStore();
        this.godWb.setVcsStore(godVcs);
        this.godWb.setModelSelector(new ModelSelector(godVcs));
        // Register the kinematic ground-truth feature subset on the god-view
        // whiteboard so it independently reconstructs the per-tick TICKS features
        // from the engine snapshot (see seedGodView). Deliberately excludes the
        // wave/fire inference processors (FireFeatures, OurWaveFeatures,
        // WaveTracker, TheirWaveTracker) — those are robot-side heuristics whose
        // god-view counterpart is produced authoritatively by GodViewWaveResolver,
        // and WallHitEstimator, whose accumulator output is robot-side-only.
        this.godWb.registerFeatures(
                new SpatialFeatures(),
                new MovementFeatures(),
                new TimingFeatures(),
                new IdentityFeatures());
    }

    /**
     * Factory: creates a pair of linked ObserverContexts for a 1v1 battle.
     *
     * @return array of two ObserverContexts, index 0 and 1
     */
    public static ObserverContext[] createPair(double bfWidth, double bfHeight, double gunCoolingRate) {
        ObserverContext ctx0 = new ObserverContext(0, bfWidth, bfHeight, gunCoolingRate);
        ObserverContext ctx1 = new ObserverContext(1, bfWidth, bfHeight, gunCoolingRate);
        ctx0.peerContext = ctx1;
        ctx1.peerContext = ctx0;
        return new ObserverContext[] { ctx0, ctx1 };
    }

    /**
     * Full tick pipeline: reconstruct events from snapshot, feed to observer, run
     * strategy.
     * This is the primary API for the orchestrator.
     */
    public void processTick(ITurnSnapshot curr) {
        if (dead)
            return;

        IRobotSnapshot[] robots = curr.getRobots();
        IRobotSnapshot me = robots[perspectiveIndex];

        // 1. Advance peer tick mechanics (gun cooling)
        peer.executeTick();

        // 2. Update peer with authoritative snapshot state
        peer.updateState(me.getX(), me.getY(), me.getGunHeading(), me.getGunHeat(), me.getEnergy());

        // 3. Build StatusEvent from snapshot
        RobotStatus status = HiddenAccess.createStatus(
                me.getEnergy(), me.getX(), me.getY(),
                me.getBodyHeading(), me.getGunHeading(), me.getRadarHeading(),
                me.getVelocity(),
                0, 0, 0, 0, // bodyTurnRemaining, radarTurnRemaining, gunTurnRemaining, distanceRemaining
                me.getGunHeat(),
                1, 0, // others, sentries
                curr.getRound(), 1, curr.getTurn() // roundNum, numRounds, turn
        );

        // 4. Reconstruct combat events (scans, bullet hits, etc.)
        TickEvents combatEvents = reconstructor.reconstruct(curr, perspectiveIndex, bfWidth, bfHeight);

        // 5. Combine: StatusEvent first, then combat events
        List<Event> allEvents = new ArrayList<>();
        allEvents.add(new StatusEvent(status));
        allEvents.addAll(combatEvents.events());

        // 6. Dispatch events + run strategy
        feedEvents(new TickEvents(allEvents));
        doTurn();
    }

    /**
     * Independently reconstruct the god-view whiteboard's per-tick TICKS features
     * directly from the engine snapshot (ground truth), then run the registered
     * kinematic feature processors. This replaces the former {@code copyFrom}
     * approach: the god-view no longer inherits the robot-side reconstruction
     * (and its scan-gap staleness / NaN), but is built omnisciently from the
     * engine's true robot states every tick.
     * <p>
     * The opponent's position is set EVERY tick (not just scan ticks), so
     * {@code LAST_SCAN_TICK == TICK} always holds on the god-view and its
     * opponent geometry is exact ground truth. Must be called after
     * {@link #processTick(ITurnSnapshot)} and before god-view wave resolution.
     */
    public void seedGodView(ITurnSnapshot curr) {
        if (dead) {
            return;
        }
        IRobotSnapshot[] robots = curr.getRobots();
        IRobotSnapshot me = robots[perspectiveIndex];
        IRobotSnapshot opp = robots[1 - perspectiveIndex];
        long tick = curr.getTurn();

        // Self base inputs (ground truth, every tick). Setting TICK first rotates
        // the god-view tick ring and clears the new slot.
        godWb.setFeature(Feature.TICK, tick);
        godWb.setFeature(Feature.OUR_X, me.getX());
        godWb.setFeature(Feature.OUR_Y, me.getY());
        godWb.setFeature(Feature.OUR_HEADING, me.getBodyHeading());
        godWb.setFeature(Feature.OUR_VELOCITY, me.getVelocity());
        godWb.setFeature(Feature.OUR_ENERGY, me.getEnergy());
        godWb.setFeature(Feature.GUN_HEAT, me.getGunHeat());
        godWb.setFeature(Feature.GUN_HEADING, me.getGunHeading());
        godWb.setFeature(Feature.RADAR_HEADING, me.getRadarHeading());

        // Omniscient opponent base inputs (ground truth, every tick). Geometry
        // mirrors GodViewQualityValidator.validateSpatial exactly so SpatialFeatures
        // reconstructs the opponent position to the engine truth.
        double dx = opp.getX() - me.getX();
        double dy = opp.getY() - me.getY();
        double absBearing = Math.atan2(dx, dy);
        godWb.setFeature(Feature.DISTANCE, Math.hypot(dx, dy));
        godWb.setFeature(Feature.BEARING_RADIANS,
                RoboMath.normalRelativeAngle(absBearing - me.getBodyHeading()));
        godWb.setFeature(Feature.OPPONENT_HEADING, opp.getBodyHeading());
        godWb.setFeature(Feature.OPPONENT_VELOCITY, opp.getVelocity());
        godWb.setFeature(Feature.OPPONENT_ENERGY, opp.getEnergy());
        godWb.setFeature(Feature.LAST_SCAN_TICK, tick);
        godWb.setStringFeature(Feature.OPPONENT_ID, opp.getName());

        godWb.process();
    }

    /**
     * Feed reconstructed events to the observer Autopilot.
     * Prefer {@link #processTick(ITurnSnapshot)} for normal operation.
     * This lower-level method is useful for unit testing with hand-crafted events.
     */
    public void feedEvents(TickEvents events) {
        if (dead)
            return;

        // Dispatch events to the observer's handlers
        for (Event event : events.events()) {
            if (event instanceof StatusEvent se) {
                observer.onStatus(se);
            } else if (event instanceof ScannedRobotEvent sre) {
                observer.onScannedRobot(sre);
            } else if (event instanceof HitByBulletEvent hbe) {
                observer.onHitByBullet(hbe);
            } else if (event instanceof BulletHitEvent bhe) {
                observer.onBulletHit(bhe);
            } else if (event instanceof HitRobotEvent hre) {
                observer.onHitRobot(hre);
            } else if (event instanceof DeathEvent) {
                diedThisTick = true;
            }
            // HitWallEvent, RobotDeathEvent, WinEvent: no handler in Autopilot
        }
    }

    /**
     * Run the observer's strategy for this tick (computes derived features).
     * Prefer {@link #processTick(ITurnSnapshot)} for normal operation.
     */
    public void doTurn() {
        if (dead)
            return;
        observer.doTurn();
        if (diedThisTick) {
            dead = true;
            diedThisTick = false;
        }
    }

    /** Reset state for a new round (round 0, for tests/back-compat). */
    public void resetRound() {
        resetRound(0);
    }

    /**
     * Reset state for a new round.
     *
     * @param round zero-based round number (used to seed the peer's bullet-id
     *              sequence so it matches the live engine's per-round numbering)
     */
    public void resetRound(int round) {
        dead = false;
        diedThisTick = false;
        reconstructor.resetRound();
        peer.resetRound(perspectiveIndex, round);
        // Reset the observer Autopilot's per-round strategy state to fresh-instance
        // baseline (Robocode re-instantiates the live robot every round). The VCS model
        // intentionally persists across rounds — the live robot keeps its accumulating
        // store in a per-battle static, so the observer mirrors that to stay a faithful
        // shadow in rounds 2+.
        observer.resetForRound();
        // Clear whiteboard so the observer starts fresh each round (matches live robot
        // behavior).
        // The live robot's ring rotation + carry-forward yields NaN for all features at
        // round start
        // because carryForward saves NaN (first tick of new round hasn't set anything
        // yet).
        observer.getWhiteboard().clearFeatures();
        // Reset the god-view whiteboard too — seedGodView rebuilds its features
        // from ground truth each tick, but the tick ring must start clean.
        godWb.clearFeatures();
    }

    public int perspectiveIndex() {
        return perspectiveIndex;
    }

    /**
     * Seed the event reconstructor from the round-start (spawn) snapshot so the
     * observer can reconstruct the round's opening scan on turn 1. Must be called
     * after {@link #resetRound(int)} and before the first {@link #processTick}.
     */
    public void seedRoundStart(ITurnSnapshot startSnapshot) {
        reconstructor.seedRoundStart(startSnapshot, perspectiveIndex);
    }

    /**
     * Point the observer at a read-only data directory so its Autopilot loads the
     * same persisted VCS model the live robot loads (keyed by OPPONENT_ID_HASH,
     * once per battle, into its own VcsStore). The observer never writes here.
     */
    public void setDataDir(java.io.File dataDir) {
        peer.setDataDir(dataDir);
    }

    public Whiteboard wb() {
        return observer.getWhiteboard();
    }

    /**
     * Debug properties the observer Autopilot published this tick (same publish
     * path the live robot uses). For {@code observer.csv} fidelity dumping.
     */
    public java.util.Map<String, String> observerDebugProperties() {
        return peer.getDebugProperties();
    }

    /**
     * God-view whiteboard (engine ground-truth wave resolution). Has its own
     * VcsStore + ModelSelector, independent of the robot-side {@link #wb()}.
     */
    public Whiteboard godWb() {
        return godWb;
    }

    public Autopilot observer() {
        return observer;
    }

    public ObserverRobotPeer peer() {
        return peer;
    }

    public EventReconstructor reconstructor() {
        return reconstructor;
    }

    public ObserverContext peerContext() {
        return peerContext;
    }

    public boolean isDead() {
        return dead;
    }
}

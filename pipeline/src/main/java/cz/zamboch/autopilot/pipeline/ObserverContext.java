package cz.zamboch.autopilot.pipeline;

import cz.zamboch.Autopilot;
import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.ModelSelector;
import cz.zamboch.autopilot.core.VcsStore;
import cz.zamboch.autopilot.core.Whiteboard;
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
        this.godWb.setFeature(Feature.BATTLEFIELD_WIDTH, bfWidth);
        this.godWb.setFeature(Feature.BATTLEFIELD_HEIGHT, bfHeight);
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
        // Clear whiteboard so the observer starts fresh each round (matches live robot
        // behavior).
        // The live robot's ring rotation + carry-forward yields NaN for all features at
        // round start
        // because carryForward saves NaN (first tick of new round hasn't set anything
        // yet).
        observer.getWhiteboard().clearFeatures();
        observer.getWhiteboard().setFeature(Feature.BATTLEFIELD_WIDTH, bfWidth);
        observer.getWhiteboard().setFeature(Feature.BATTLEFIELD_HEIGHT, bfHeight);
    }

    public int perspectiveIndex() {
        return perspectiveIndex;
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

package cz.zamboch.autopilot.pipeline;

import cz.zamboch.Autopilot;
import cz.zamboch.autopilot.core.Whiteboard;
import robocode.*;
import robocode.control.snapshot.IRobotSnapshot;

/**
 * Holds one observer Autopilot instance + its Whiteboard + per-round state.
 * Two ObserverContexts form a pair (one per perspective in a 1v1 battle).
 */
public final class ObserverContext {

    private final int perspectiveIndex; // 0 or 1
    private final Autopilot observer;
    private final ObserverRobotPeer peer;
    private final EventReconstructor reconstructor;
    private ObserverContext peerContext; // the other perspective
    private boolean dead;

    public ObserverContext(int perspectiveIndex, double bfWidth, double bfHeight, double gunCoolingRate) {
        this.perspectiveIndex = perspectiveIndex;
        this.observer = new Autopilot();
        this.peer = new ObserverRobotPeer(bfWidth, bfHeight, gunCoolingRate);
        this.reconstructor = new EventReconstructor();

        observer.setPeer(peer);
        observer.initForObserver(null, bfWidth, bfHeight);
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
        return new ObserverContext[]{ctx0, ctx1};
    }

    /**
     * Feed reconstructed events to the observer Autopilot.
     * If this observer is dead, the call is a no-op.
     */
    public void feedEvents(TickEvents events) {
        if (dead) return;

        // Execute the peer's tick mechanics (gun cooling, etc.)
        peer.executeTick();

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
                dead = true;
            }
            // HitWallEvent, RobotDeathEvent, WinEvent: no handler in Autopilot
        }
    }

    /**
     * Run the observer's strategy for this tick (computes derived features).
     * If this observer is dead, the call is a no-op.
     */
    public void doTurn() {
        if (dead) return;
        observer.doTurn();
    }

    /** Reset state for a new round. */
    public void resetRound() {
        dead = false;
        reconstructor.resetRound();
    }

    public int perspectiveIndex() {
        return perspectiveIndex;
    }

    public Whiteboard wb() {
        return observer.getWhiteboard();
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

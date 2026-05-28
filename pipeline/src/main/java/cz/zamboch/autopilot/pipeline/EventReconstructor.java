package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.RoboMath;
import robocode.*;
import robocode.control.snapshot.*;

import java.awt.geom.Arc2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reconstructs Robocode events from consecutive {@link ITurnSnapshot}s.
 * <p>
 * Designed for 1v1 battles only (exactly 2 robots, indices 0 and 1).
 * Maintains inter-tick state (radar heading, velocity, position, etc.)
 * to detect state transitions that map to events.
 * <p>
 * Call {@link #resetRound()} at the start of each round.
 */
public final class EventReconstructor {

    private static final double SCAN_RADIUS = 1200.0;
    private static final double ROBOT_SIZE = 36.0;

    // Per-tick state (from my robot's perspective)
    private double prevRadarHeading = Double.NaN;
    private double prevVelocity = 0;
    private double prevHeading = 0;
    private double prevX = 0;
    private double prevY = 0;
    private RobotState prevState = RobotState.ACTIVE;
    private RobotState prevOpponentState = RobotState.ACTIVE;

    // Bullet deduplication
    private final Set<Integer> knownBulletIds = new HashSet<>();

    // Reusable arc for scan detection
    private final Arc2D.Double scanArc = new Arc2D.Double();

    /** Reset all inter-tick state. Call at the beginning of each round. */
    public void resetRound() {
        prevRadarHeading = Double.NaN;
        prevVelocity = 0;
        prevHeading = 0;
        prevX = 0;
        prevY = 0;
        prevState = RobotState.ACTIVE;
        prevOpponentState = RobotState.ACTIVE;
        knownBulletIds.clear();
    }

    /**
     * Reconstruct events for the robot at {@code myIndex} from the given turn
     * snapshot.
     *
     * @param turn     the turn snapshot
     * @param myIndex  contestant index of the robot whose perspective we
     *                 reconstruct (0 or 1)
     * @param bfWidth  battlefield width in pixels
     * @param bfHeight battlefield height in pixels
     * @return reconstructed events for this tick
     */
    public TickEvents reconstruct(ITurnSnapshot turn, int myIndex, double bfWidth, double bfHeight) {
        IRobotSnapshot[] robots = turn.getRobots();
        IRobotSnapshot me = robots[myIndex];
        int opponentIndex = 1 - myIndex;
        IRobotSnapshot opponent = robots[opponentIndex];
        long tick = turn.getTurn();

        List<Event> events = new ArrayList<>();

        // === Bullet events ===
        detectBulletEvents(turn, myIndex, me, opponent, events);

        // === Wall hit ===
        detectWallHit(me, bfWidth, bfHeight, events);

        // === Robot collision (ram) ===
        detectRam(me, opponent, events);

        // === Scan ===
        detectScan(me, opponent, events);

        // === Death events ===
        detectDeath(me, opponent, events);

        // === Set event time and sort by priority (engine dispatch order: highest
        // first) ===
        for (Event e : events) {
            e.setTime(tick);
        }
        events.sort(EVENT_PRIORITY_ORDER);

        // === Update state for next tick ===
        prevRadarHeading = me.getRadarHeading();
        prevVelocity = me.getVelocity();
        prevHeading = me.getBodyHeading();
        prevX = me.getX();
        prevY = me.getY();
        prevState = me.getState();
        prevOpponentState = opponent.getState();

        return new TickEvents(events);
    }

    // ==================== Bullet Events ====================

    private void detectBulletEvents(ITurnSnapshot turn, int myIndex,
            IRobotSnapshot me, IRobotSnapshot opponent, List<Event> events) {
        IBulletSnapshot[] bullets = turn.getBullets();
        if (bullets == null)
            return;

        for (IBulletSnapshot bs : bullets) {
            int bulletId = bs.getBulletId();
            int owner = bs.getOwnerIndex();
            int victim = bs.getVictimIndex();
            double power = bs.getPower();
            BulletState bState = bs.getState();

            if (bState == BulletState.HIT_VICTIM) {
                if (!knownBulletIds.add(bulletId))
                    continue; // already processed

                if (owner == myIndex) {
                    // Our bullet hit the opponent → BulletHitEvent
                    Bullet bullet = new Bullet(bs.getHeading(), bs.getX(), bs.getY(),
                            power, me.getShortName(), opponent.getShortName(), false, bulletId);
                    events.add(new BulletHitEvent(opponent.getShortName(), opponent.getEnergy(), bullet));
                } else if (victim == myIndex) {
                    // Opponent's bullet hit us → HitByBulletEvent
                    double bearing = RoboMath.normalRelativeAngle(
                            bs.getHeading() + Math.PI - me.getBodyHeading());
                    Bullet bullet = new Bullet(bs.getHeading(), bs.getX(), bs.getY(),
                            power, opponent.getShortName(), me.getShortName(), false, bulletId);
                    events.add(new HitByBulletEvent(bearing, bullet));
                }
            } else if (bState == BulletState.HIT_WALL && owner == myIndex) {
                if (!knownBulletIds.add(bulletId))
                    continue;
                // Our bullet missed (hit wall) → BulletMissedEvent
                Bullet bullet = new Bullet(bs.getHeading(), bs.getX(), bs.getY(),
                        power, me.getShortName(), null, false, bulletId);
                events.add(new BulletMissedEvent(bullet));
            } else if (bState == BulletState.HIT_BULLET && owner == myIndex) {
                if (!knownBulletIds.add(bulletId))
                    continue;
                // Our bullet hit another bullet → BulletHitBulletEvent
                Bullet myBullet = new Bullet(bs.getHeading(), bs.getX(), bs.getY(),
                        power, me.getShortName(), null, false, bulletId);
                // Find the other bullet involved in this collision
                Bullet otherBullet = findOtherHitBullet(bullets, bulletId, myIndex, opponent.getShortName());
                events.add(new BulletHitBulletEvent(myBullet, otherBullet));
            }
        }
    }

    private Bullet findOtherHitBullet(IBulletSnapshot[] bullets, int excludeId,
            int myIndex, String opponentName) {
        for (IBulletSnapshot bs : bullets) {
            if (bs.getBulletId() != excludeId && bs.getState() == BulletState.HIT_BULLET
                    && bs.getOwnerIndex() != myIndex) {
                return new Bullet(bs.getHeading(), bs.getX(), bs.getY(),
                        bs.getPower(), opponentName, null, false, bs.getBulletId());
            }
        }
        // Fallback: no matching bullet found (shouldn't happen in valid replays)
        return new Bullet(0, 0, 0, 0, opponentName, null, false, -1);
    }

    // ==================== Wall Hit ====================

    private void detectWallHit(IRobotSnapshot me, double bfWidth, double bfHeight, List<Event> events) {
        if (me.getState() == RobotState.HIT_WALL && prevState != RobotState.HIT_WALL) {
            double bearing = computeWallBearing(me, bfWidth, bfHeight);
            events.add(new HitWallEvent(bearing));
        }
    }

    /**
     * Compute bearing to the wall that was hit, matching the engine's formula.
     * Engine logic (RobotPeer.checkWallCollision):
     * left wall: normalRelativeAngle(3π/2 - bodyHeading)
     * right wall: normalRelativeAngle(π/2 - bodyHeading)
     * bottom wall: normalRelativeAngle(π - bodyHeading)
     * top wall: normalRelativeAngle(-bodyHeading)
     *
     * Corner priority: engine checks X walls first (if/else if), then Y walls
     * (independent if/else if). If both X and Y exceed bounds, Y wall angle
     * OVERWRITES X. So Y walls take precedence in corner collisions.
     * We match this by checking Y walls FIRST in our if-else chain (first match
     * wins).
     */
    private double computeWallBearing(IRobotSnapshot me, double bfWidth, double bfHeight) {
        double x = me.getX();
        double y = me.getY();
        double heading = me.getBodyHeading();
        double half = ROBOT_SIZE / 2;

        // Determine which wall was hit based on proximity
        double distLeft = x - half;
        double distRight = bfWidth - half - x;
        double distBottom = y - half;
        double distTop = bfHeight - half - y;

        double minDist = Math.min(Math.min(distLeft, distRight), Math.min(distBottom, distTop));

        // Y walls checked first — matches engine where Y overwrites X in corner hits
        double wallNormal;
        if (minDist == distBottom) {
            wallNormal = Math.PI; // bottom wall
        } else if (minDist == distTop) {
            wallNormal = 0; // top wall
        } else if (minDist == distLeft) {
            wallNormal = 3 * Math.PI / 2; // left wall
        } else {
            wallNormal = Math.PI / 2; // right wall
        }
        return RoboMath.normalRelativeAngle(wallNormal - heading);
    }

    // ==================== Ram (Robot Collision) ====================

    private void detectRam(IRobotSnapshot me, IRobotSnapshot opponent, List<Event> events) {
        boolean myTransition = me.getState() == RobotState.HIT_ROBOT && prevState != RobotState.HIT_ROBOT;
        boolean oppTransition = opponent.getState() == RobotState.HIT_ROBOT
                && prevOpponentState != RobotState.HIT_ROBOT;

        if (myTransition || oppTransition) {
            double dx = opponent.getX() - me.getX();
            double dy = opponent.getY() - me.getY();
            double angle = Math.atan2(dx, dy);
            double bearing = RoboMath.normalRelativeAngle(angle - me.getBodyHeading());

            // At-fault logic matches engine: moving toward the opponent
            boolean atFault = isAtFault(bearing);

            events.add(new HitRobotEvent(opponent.getShortName(), bearing, opponent.getEnergy(), atFault));
        }
    }

    /**
     * Determine if we are at fault in the collision.
     * Engine formula (RobotPeer.checkRobotCollision):
     * (velocity > 0 && bearing > -PI/2 && bearing < PI/2)
     * || (velocity < 0 && (bearing < -PI/2 || bearing > PI/2))
     * Uses prevVelocity as the best available approximation of pre-collision
     * velocity.
     */
    private boolean isAtFault(double bearing) {
        if (prevVelocity == 0)
            return false;
        return (prevVelocity > 0 && bearing > -Math.PI / 2 && bearing < Math.PI / 2)
                || (prevVelocity < 0 && (bearing < -Math.PI / 2 || bearing > Math.PI / 2));
    }

    // ==================== Scan ====================

    private void detectScan(IRobotSnapshot me, IRobotSnapshot opponent, List<Event> events) {
        double radarHeading = me.getRadarHeading();

        if (Double.isNaN(prevRadarHeading)) {
            // First tick — no sweep yet
            return;
        }

        // Engine: performScan returns immediately if dead
        if (me.getState() == RobotState.DEAD)
            return;

        // Skip if opponent is dead
        if (opponent.getState() == RobotState.DEAD)
            return;

        double scanRadians = radarHeading - prevRadarHeading;
        if (scanRadians < -Math.PI)
            scanRadians += 2 * Math.PI;
        else if (scanRadians > Math.PI)
            scanRadians -= 2 * Math.PI;

        if (scanRadians == 0)
            return; // No radar movement

        // Build Arc2D.PIE (same geometry as engine)
        double startAngle = RoboMath.normalAbsoluteAngle(prevRadarHeading - Math.PI / 2);
        double r = SCAN_RADIUS;
        scanArc.setArc(me.getX() - r, me.getY() - r, 2 * r, 2 * r,
                Math.toDegrees(startAngle), Math.toDegrees(scanRadians), Arc2D.PIE);

        // Target bounding box (36x36 centered on opponent)
        double half = ROBOT_SIZE / 2;
        Rectangle2D.Double targetBox = new Rectangle2D.Double(
                opponent.getX() - half, opponent.getY() - half, ROBOT_SIZE, ROBOT_SIZE);

        if (intersects(scanArc, targetBox)) {
            double dx = opponent.getX() - me.getX();
            double dy = opponent.getY() - me.getY();
            double distance = Math.hypot(dx, dy);
            double angle = Math.atan2(dx, dy);
            double bearing = RoboMath.normalRelativeAngle(angle - me.getBodyHeading());

            events.add(new ScannedRobotEvent(
                    opponent.getShortName(),
                    opponent.getEnergy(),
                    bearing,
                    distance,
                    opponent.getBodyHeading(),
                    opponent.getVelocity(),
                    false));
        }
    }

    private static boolean intersects(Arc2D arc, Rectangle2D rect) {
        return rect.intersectsLine(arc.getCenterX(), arc.getCenterY(),
                arc.getStartPoint().getX(), arc.getStartPoint().getY())
                || arc.intersects(rect);
    }

    // ==================== Event Priority (engine dispatch order)
    // ====================

    /**
     * Comparator that sorts events in engine dispatch order: highest priority
     * first.
     * Matches EventManager.processEvents() which calls eventQueue.sort() using
     * Event.compareTo (time first, then descending priority).
     */
    private static final Comparator<Event> EVENT_PRIORITY_ORDER = Comparator
            .comparingInt(EventReconstructor::eventPriority).reversed();

    /** Returns the engine's DEFAULT_PRIORITY for each event type. */
    private static int eventPriority(Event e) {
        if (e instanceof WinEvent)
            return 100;
        if (e instanceof RobotDeathEvent)
            return 70;
        if (e instanceof BulletMissedEvent)
            return 60;
        if (e instanceof BulletHitBulletEvent)
            return 55;
        if (e instanceof BulletHitEvent)
            return 50;
        if (e instanceof HitRobotEvent)
            return 40;
        if (e instanceof HitWallEvent)
            return 30;
        if (e instanceof HitByBulletEvent)
            return 20;
        if (e instanceof ScannedRobotEvent)
            return 10;
        if (e instanceof DeathEvent)
            return -1;
        return 80; // Event base default
    }

    // ==================== Death Events ====================

    private void detectDeath(IRobotSnapshot me, IRobotSnapshot opponent, List<Event> events) {
        // Opponent died this tick
        if (opponent.getState() == RobotState.DEAD && prevOpponentState != RobotState.DEAD) {
            events.add(new RobotDeathEvent(opponent.getShortName()));
        }
        // We died this tick
        if (me.getState() == RobotState.DEAD && prevState != RobotState.DEAD) {
            events.add(new DeathEvent());
        }
        // We won (opponent dead, we alive)
        if (opponent.getState() == RobotState.DEAD && me.getState() != RobotState.DEAD
                && prevOpponentState != RobotState.DEAD) {
            events.add(new WinEvent());
        }
    }
}

package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.RoboMath;
import robocode.*;
import robocode.control.snapshot.*;

import java.awt.geom.Arc2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
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
    private double prevEnergy = 100;

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
        prevEnergy = 100;
        knownBulletIds.clear();
    }

    /**
     * Reconstruct events for the robot at {@code myIndex} from the given turn snapshot.
     *
     * @param turn      the turn snapshot
     * @param myIndex   contestant index of the robot whose perspective we reconstruct (0 or 1)
     * @param bfWidth   battlefield width in pixels
     * @param bfHeight  battlefield height in pixels
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
        detectWallHit(me, events);

        // === Robot collision (ram) ===
        detectRam(me, opponent, events);

        // === Scan ===
        detectScan(me, opponent, events);

        // === Death events ===
        detectDeath(me, opponent, events);

        // === Update state for next tick ===
        prevRadarHeading = me.getRadarHeading();
        prevVelocity = me.getVelocity();
        prevHeading = me.getBodyHeading();
        prevX = me.getX();
        prevY = me.getY();
        prevState = me.getState();
        prevOpponentState = opponent.getState();
        prevEnergy = me.getEnergy();

        return new TickEvents(events);
    }

    // ==================== Bullet Events ====================

    private void detectBulletEvents(ITurnSnapshot turn, int myIndex,
            IRobotSnapshot me, IRobotSnapshot opponent, List<Event> events) {
        IBulletSnapshot[] bullets = turn.getBullets();
        if (bullets == null) return;

        for (IBulletSnapshot bs : bullets) {
            int bulletId = bs.getBulletId();
            int owner = bs.getOwnerIndex();
            int victim = bs.getVictimIndex();
            double power = bs.getPower();
            BulletState bState = bs.getState();

            if (bState == BulletState.HIT_VICTIM) {
                if (!knownBulletIds.add(bulletId)) continue; // already processed

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
                if (!knownBulletIds.add(bulletId)) continue;
                // Our bullet missed (hit wall) → BulletMissedEvent
                Bullet bullet = new Bullet(bs.getHeading(), bs.getX(), bs.getY(),
                        power, me.getShortName(), null, false, bulletId);
                events.add(new BulletMissedEvent(bullet));
            }
        }
    }

    // ==================== Wall Hit ====================

    private void detectWallHit(IRobotSnapshot me, List<Event> events) {
        if (me.getState() == RobotState.HIT_WALL && prevState != RobotState.HIT_WALL) {
            // State transition → wall hit
            // Bearing to wall: approximate using heading (engine computes from exact contact)
            double bearing = computeWallBearing(me);
            events.add(new HitWallEvent(bearing));
        }
    }

    /**
     * Approximate the bearing to the wall relative to the robot's heading.
     * The engine uses the exact contact geometry; we estimate from position.
     */
    private double computeWallBearing(IRobotSnapshot me) {
        // Use previous heading (the heading at impact time)
        return RoboMath.normalRelativeAngle(-prevHeading);
    }

    // ==================== Ram (Robot Collision) ====================

    private void detectRam(IRobotSnapshot me, IRobotSnapshot opponent, List<Event> events) {
        if (me.getState() == RobotState.HIT_ROBOT && prevState != RobotState.HIT_ROBOT) {
            double dx = opponent.getX() - me.getX();
            double dy = opponent.getY() - me.getY();
            double angle = Math.atan2(dx, dy);
            double bearing = RoboMath.normalRelativeAngle(angle - me.getBodyHeading());

            // Determine if we are at fault (moving toward the opponent)
            boolean atFault = isAtFault(me);

            events.add(new HitRobotEvent(opponent.getShortName(), bearing, opponent.getEnergy(), atFault));
        }
    }

    private boolean isAtFault(IRobotSnapshot me) {
        // Use previous velocity: if positive component toward opponent → at fault
        if (prevVelocity == 0) return false;
        // If we had positive velocity we were advancing forward in our heading direction
        return prevVelocity > 0;
    }

    // ==================== Scan ====================

    private void detectScan(IRobotSnapshot me, IRobotSnapshot opponent, List<Event> events) {
        double radarHeading = me.getRadarHeading();

        if (Double.isNaN(prevRadarHeading)) {
            // First tick — no sweep yet
            return;
        }

        // Skip if opponent is dead
        if (opponent.getState() == RobotState.DEAD) return;

        double scanRadians = radarHeading - prevRadarHeading;
        if (scanRadians < -Math.PI) scanRadians += 2 * Math.PI;
        else if (scanRadians > Math.PI) scanRadians -= 2 * Math.PI;

        if (scanRadians == 0) return; // No radar movement

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

package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import robocode.Rules;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;

import java.awt.geom.Arc2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;

/**
 * Replays turn snapshots into two Whiteboard perspectives.
 * Synthesizes scan events via radar sweep intersection and detects
 * opponent fire events via energy drops.
 * Sets features directly on the Whiteboard (no event objects needed).
 */
public final class Player {
    private static final double SCAN_RADIUS = 1200.0; // robocode.Rules.RADAR_SCAN_RADIUS
    private static final double ROBOT_SIZE = 36.0;

    private final Whiteboard wbA;
    private final Whiteboard wbB;

    private int currentRound = -1;

    // Radar tracking for scan synthesis
    private double prevRadarHeadingA = Double.NaN;
    private double prevRadarHeadingB = Double.NaN;

    // Dead robot tracking
    private boolean deadA = false;
    private boolean deadB = false;

    // Reusable Arc2D for scan detection (matches engine's scanArc)
    private final Arc2D.Double scanArc = new Arc2D.Double();

    public Player(Whiteboard wbA, Whiteboard wbB) {
        this.wbA = wbA;
        this.wbB = wbB;
    }

    /**
     * Process one turn snapshot. Injects state into both whiteboards as features.
     *
     * @return true if a new round started (caller should finalize previous round)
     */
    public boolean processTurn(int roundIndex, ITurnSnapshot turn,
            double bfWidth, double bfHeight) {
        boolean newRound = (roundIndex != currentRound);
        if (newRound) {
            currentRound = roundIndex;
            prevRadarHeadingA = Double.NaN;
            prevRadarHeadingB = Double.NaN;

            deadA = false;
            deadB = false;
            wbA.clearFeatures();
            wbB.clearFeatures();
        }

        IRobotSnapshot[] robots = turn.getRobots();
        if (robots.length < 2) {
            return newRound;
        }

        IRobotSnapshot robotA = robots[0];
        IRobotSnapshot robotB = robots[1];

        // Stop processing after robot dies or is disabled (energy=0)
        if (robotA.getState() == RobotState.DEAD || robotA.getEnergy() <= 0)
            deadA = true;
        if (robotB.getState() == RobotState.DEAD || robotB.getEnergy() <= 0)
            deadB = true;

        long tick = (long) turn.getTurn();

        // Inject perspective A: robotA is "us", robotB is opponent
        if (!deadA) {
            injectOwnState(wbA, robotA, tick, bfWidth, bfHeight);
            if (!deadB)
                synthesizeScan(wbA, robotA, robotB, tick, true);
        }

        // Inject perspective B: robotB is "us", robotA is opponent
        if (!deadB) {
            injectOwnState(wbB, robotB, tick, bfWidth, bfHeight);
            if (!deadA)
                synthesizeScan(wbB, robotB, robotA, tick, false);
        }

        // Detect bullet hits and rams from snapshot
        if (!deadA || !deadB) {
            detectBulletHits(turn, robotA, robotB);
            detectRams(robotA, robotB);
        }

        return newRound;
    }

    private void injectOwnState(Whiteboard wb, IRobotSnapshot self,
            long tick, double bfWidth, double bfHeight) {
        wb.setFeature(Feature.TICK, tick);
        wb.setFeature(Feature.OUR_X, self.getX());
        wb.setFeature(Feature.OUR_Y, self.getY());
        wb.setFeature(Feature.OUR_HEADING, self.getBodyHeading());
        wb.setFeature(Feature.OUR_VELOCITY, self.getVelocity());
        wb.setFeature(Feature.OUR_ENERGY, self.getEnergy());
        wb.setFeature(Feature.GUN_HEAT, self.getGunHeat());
        wb.setFeature(Feature.GUN_HEADING, self.getGunHeading());
        wb.setFeature(Feature.BATTLEFIELD_WIDTH, bfWidth);
        wb.setFeature(Feature.BATTLEFIELD_HEIGHT, bfHeight);
    }

    /**
     * Synthesize a scan event using the same Arc2D.PIE geometry as the engine.
     * Engine scan condition: scan fires whenever anything moved (heading,
     * position).
     * Since we only call this when the robot is alive, it's always moving.
     */
    private void synthesizeScan(Whiteboard wb, IRobotSnapshot self,
            IRobotSnapshot opponent, long tick, boolean isA) {
        double radarHeading = self.getRadarHeading();
        double prevRadar = isA ? prevRadarHeadingA : prevRadarHeadingB;

        if (Double.isNaN(prevRadar)) {
            // First tick: engine's lastRadarHeading is initial heading (= body heading
            // before move)
            // On tick 0, nothing has moved yet so radar == body heading.
            prevRadar = radarHeading;
            if (isA)
                prevRadarHeadingA = radarHeading;
            else
                prevRadarHeadingB = radarHeading;
            // No sweep on very first tick (no commands processed yet)
            return;
        }

        // Replicate engine's scan() method:
        // scanRadians = currentRadarHeading - lastRadarHeading
        double scanRadians = radarHeading - prevRadar;
        if (scanRadians < -Math.PI)
            scanRadians += 2 * Math.PI;
        else if (scanRadians > Math.PI)
            scanRadians -= 2 * Math.PI;

        // Convert to Java2D coords: startAngle = prevRadar - PI/2, normalized to [0,
        // 2PI)
        double startAngle = normalAbsoluteAngle(prevRadar - Math.PI / 2);

        // Build Arc2D.PIE (same as engine)
        double r = SCAN_RADIUS;
        scanArc.setArc(self.getX() - r, self.getY() - r, 2 * r, 2 * r,
                Math.toDegrees(startAngle), Math.toDegrees(scanRadians), Arc2D.PIE);

        // Target bounding box (36x36 centered on opponent)
        double half = ROBOT_SIZE / 2;
        Rectangle2D.Double targetBox = new Rectangle2D.Double(
                opponent.getX() - half, opponent.getY() - half, ROBOT_SIZE, ROBOT_SIZE);

        // Engine's intersects check: line from center to start point OR arc intersects
        // rect
        if (intersects(scanArc, targetBox)) {
            injectScan(wb, self, opponent, tick, isA);
        }

        if (isA)
            prevRadarHeadingA = radarHeading;
        else
            prevRadarHeadingB = radarHeading;
    }

    /**
     * Engine-exact intersection: line from arc center to start point, OR arc
     * boundary.
     */
    private static boolean intersects(Arc2D arc, Rectangle2D rect) {
        return rect.intersectsLine(arc.getCenterX(), arc.getCenterY(),
                arc.getStartPoint().getX(), arc.getStartPoint().getY())
                || arc.intersects(rect);
    }

    private void injectScan(Whiteboard wb, IRobotSnapshot self,
            IRobotSnapshot opponent, long tick, boolean isA) {
        // Compute ScannedRobotEvent-equivalent values (same as engine)
        double dx = opponent.getX() - self.getX();
        double dy = opponent.getY() - self.getY();
        double distance = Math.hypot(dx, dy);

        // Engine: normalRelativeAngle(atan2(dx, dy) - getBodyHeading())
        double angle = Math.atan2(dx, dy);
        double bearing = normalRelativeAngle(angle - self.getBodyHeading());

        // Set scan features directly
        wb.setFeature(Feature.DISTANCE, distance);
        wb.setFeature(Feature.BEARING_RADIANS, bearing);
        wb.setFeature(Feature.OPPONENT_HEADING, opponent.getBodyHeading());
        wb.setFeature(Feature.OPPONENT_VELOCITY, opponent.getVelocity());
        wb.setFeature(Feature.OPPONENT_ENERGY, opponent.getEnergy());
        wb.setFeature(Feature.LAST_SCAN_TICK, tick);

    }

    /**
     * Detect bullet hits from turn snapshot and accumulate energy changes.
     * Mirrors robot's onBulletHit and onHitByBullet events.
     */
    private void detectBulletHits(ITurnSnapshot turn, IRobotSnapshot robotA, IRobotSnapshot robotB) {
        IBulletSnapshot[] bullets = turn.getBullets();
        if (bullets == null)
            return;

        for (IBulletSnapshot bullet : bullets) {
            if (bullet.getState() != BulletState.HIT_VICTIM)
                continue;

            int owner = bullet.getOwnerIndex();
            int victim = bullet.getVictimIndex();
            double power = bullet.getPower();

            // Perspective A: robotA(0) is us, robotB(1) is opponent
            if (!deadA) {
                if (owner == 0 && victim == 1) {
                    // Our bullet hit opponent → accumulate damage
                    accumulate(wbA, Feature.OUR_BULLET_DAMAGE_TO_OPPONENT, Rules.getBulletDamage(power));
                } else if (owner == 1 && victim == 0) {
                    // Opponent bullet hit us → opponent gains energy
                    accumulate(wbA, Feature.OPPONENT_BULLET_ENERGY_GAIN, Rules.getBulletHitBonus(power));
                }
            }

            // Perspective B: robotB(1) is us, robotA(0) is opponent
            if (!deadB) {
                if (owner == 1 && victim == 0) {
                    // Our bullet hit opponent → accumulate damage
                    accumulate(wbB, Feature.OUR_BULLET_DAMAGE_TO_OPPONENT, Rules.getBulletDamage(power));
                } else if (owner == 0 && victim == 1) {
                    // Opponent bullet hit us → opponent gains energy
                    accumulate(wbB, Feature.OPPONENT_BULLET_ENERGY_GAIN, Rules.getBulletHitBonus(power));
                }
            }
        }
    }

    /**
     * Detect rams from robot states and accumulate damage.
     * Engine sets RobotState.HIT_ROBOT on the "at fault" robot.
     * Each at-fault robot causes 0.6 damage to both robots.
     */
    private void detectRams(IRobotSnapshot robotA, IRobotSnapshot robotB) {
        boolean aHitRobot = (robotA.getState() == RobotState.HIT_ROBOT);
        boolean bHitRobot = (robotB.getState() == RobotState.HIT_ROBOT);

        if (!aHitRobot && !bHitRobot)
            return;

        // Total damage to opponent = 0.6 per at-fault robot
        double ramDmg = 0;
        if (aHitRobot)
            ramDmg += Rules.ROBOT_HIT_DAMAGE;
        if (bHitRobot)
            ramDmg += Rules.ROBOT_HIT_DAMAGE;

        // Both perspectives see the same total ram damage (symmetrical)
        if (!deadA)
            accumulate(wbA, Feature.RAM_DAMAGE_TO_OPPONENT, ramDmg);
        if (!deadB)
            accumulate(wbB, Feature.RAM_DAMAGE_TO_OPPONENT, ramDmg);
    }

    /** Accumulate a value into a whiteboard feature (treats NaN as 0). */
    private static void accumulate(Whiteboard wb, Feature feature, double value) {
        double current = wb.getFeature(feature);
        wb.setFeature(feature, (Double.isNaN(current) ? 0 : current) + value);
    }

    /** Normalize angle to [0, 2*PI) — matches engine's normalAbsoluteAngle. */
    private static double normalAbsoluteAngle(double angle) {
        return (angle %= (2 * Math.PI)) >= 0 ? angle : (angle + 2 * Math.PI);
    }

    /** Normalize angle to [-PI, PI) — matches engine's normalRelativeAngle. */
    private static double normalRelativeAngle(double angle) {
        return (angle %= (2 * Math.PI)) >= 0
                ? (angle < Math.PI) ? angle : angle - 2 * Math.PI
                : (angle >= -Math.PI) ? angle : angle + 2 * Math.PI;
    }

    /** Determine round winner by robot states. */
    public void finalizeRound(Whiteboard wbA, Whiteboard wbB,
            IRobotSnapshot robotA, IRobotSnapshot robotB) {
        // Winner has more energy or is alive while opponent is dead
        boolean aDead = (robotA.getState() == RobotState.DEAD);
        boolean bDead = (robotB.getState() == RobotState.DEAD);
        if (aDead && !bDead) {
            wbA.setFeature(Feature.ROUND_RESULT, -1);
            wbB.setFeature(Feature.ROUND_RESULT, 1);
        } else if (bDead && !aDead) {
            wbA.setFeature(Feature.ROUND_RESULT, 1);
            wbB.setFeature(Feature.ROUND_RESULT, -1);
        } else {
            // Tie or timeout — compare energy
            double diff = robotA.getEnergy() - robotB.getEnergy();
            wbA.setFeature(Feature.ROUND_RESULT, diff > 0 ? 1 : (diff < 0 ? -1 : 0));
            wbB.setFeature(Feature.ROUND_RESULT, diff > 0 ? -1 : (diff < 0 ? 1 : 0));
        }
    }

    /**
     * Read robot names from the first turn of a recording.
     */
    public static String[] getRobotNames(Loader loader) throws IOException, ClassNotFoundException {
        final String[][] names = { null };
        loader.forEachTurn(new Loader.TurnConsumer() {
            @Override
            public void accept(int roundIndex, ITurnSnapshot turn) {
                if (names[0] == null) {
                    IRobotSnapshot[] robots = turn.getRobots();
                    names[0] = new String[] {
                            robots[0].getShortName(),
                            robots[1].getShortName()
                    };
                }
            }
        });
        return names[0];
    }
}

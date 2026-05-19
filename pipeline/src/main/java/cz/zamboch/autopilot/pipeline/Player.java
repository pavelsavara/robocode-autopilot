package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Whiteboard;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.RobotState;

import java.awt.geom.Arc2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Replays turn snapshots into two Whiteboard perspectives.
 * Synthesizes scan events via radar sweep intersection and detects
 * opponent fire events via energy drops.
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

    // Energy tracking for fire detection
    private double prevEnergyA = Double.NaN;
    private double prevEnergyB = Double.NaN;

    // Bullet tracking
    private final Map<Integer, Integer> bulletOwners = new HashMap<Integer, Integer>();

    public Player(Whiteboard wbA, Whiteboard wbB) {
        this.wbA = wbA;
        this.wbB = wbB;
    }

    /**
     * Process one turn snapshot. Injects state into both whiteboards.
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
            prevEnergyA = Double.NaN;
            prevEnergyB = Double.NaN;
            bulletOwners.clear();
            wbA.clearFeatures();
            wbB.clearFeatures();
        }

        IRobotSnapshot[] robots = turn.getRobots();
        if (robots.length < 2) {
            return newRound;
        }

        IRobotSnapshot robotA = robots[0];
        IRobotSnapshot robotB = robots[1];

        long tick = (long) turn.getTurn();

        // Inject perspective A: robotA is "us", robotB is opponent
        injectOwnState(wbA, robotA, tick, bfWidth, bfHeight);
        synthesizeScan(wbA, robotA, robotB, tick, true);
        detectOpponentFire(wbA, robotB, true);

        // Inject perspective B: robotB is "us", robotA is opponent
        injectOwnState(wbB, robotB, tick, bfWidth, bfHeight);
        synthesizeScan(wbB, robotB, robotA, tick, false);
        detectOpponentFire(wbB, robotA, false);

        return newRound;
    }

    private void injectOwnState(Whiteboard wb, IRobotSnapshot self,
            long tick, double bfWidth, double bfHeight) {
        wb.setTick(tick);
        wb.setOurPosition(self.getX(), self.getY());
        wb.setOurHeading(self.getBodyHeading());
        wb.setOurVelocity(self.getVelocity());
        wb.setOurEnergy(self.getEnergy());
        wb.setGunHeat(self.getGunHeat());
        wb.setGunHeadingRadians(self.getGunHeading());
        wb.setBattlefieldSize(bfWidth, bfHeight);
    }

    /**
     * Synthesize a scan event if the radar sweep arc intersects the opponent's
     * bounding box.
     */
    private void synthesizeScan(Whiteboard wb, IRobotSnapshot self,
            IRobotSnapshot opponent, long tick, boolean isA) {
        double radarHeading = self.getRadarHeading();
        double prevRadar = isA ? prevRadarHeadingA : prevRadarHeadingB;

        if (Double.isNaN(prevRadar)) {
            // First tick of round — assume scan happens
            if (isA) {
                prevRadarHeadingA = radarHeading;
            } else {
                prevRadarHeadingB = radarHeading;
            }
            injectScan(wb, self, opponent, tick);
            return;
        }

        // Check if radar sweep arc intersects opponent bounding box
        if (radarSweepIntersects(self.getX(), self.getY(), prevRadar, radarHeading,
                opponent.getX(), opponent.getY())) {
            injectScan(wb, self, opponent, tick);
        }

        if (isA) {
            prevRadarHeadingA = radarHeading;
        } else {
            prevRadarHeadingB = radarHeading;
        }
    }

    private void injectScan(Whiteboard wb, IRobotSnapshot self,
            IRobotSnapshot opponent, long tick) {
        // Compute ScannedRobotEvent-equivalent values
        double dx = opponent.getX() - self.getX();
        double dy = opponent.getY() - self.getY();
        double distance = Math.sqrt(dx * dx + dy * dy);

        // Absolute bearing from self to opponent (radians, robocode convention:
        // 0=north, CW)
        double absoluteBearing = Math.atan2(dx, dy);

        // Bearing relative to self's heading
        double selfHeadingRad = self.getBodyHeading();
        double bearing = absoluteBearing - selfHeadingRad;
        // Normalize to [-PI, PI]
        while (bearing > Math.PI)
            bearing -= 2 * Math.PI;
        while (bearing < -Math.PI)
            bearing += 2 * Math.PI;

        // Opponent heading and velocity
        double oppHeading = opponent.getBodyHeading();
        double oppVelocity = opponent.getVelocity();
        double oppEnergy = opponent.getEnergy();

        wb.setScanData(tick, distance, Math.toDegrees(bearing),
                oppHeading, oppVelocity, oppEnergy);
    }

    /**
     * Detect opponent fire via energy drop (0.1 to 3.0 energy decrease without
     * bullet hit).
     */
    private void detectOpponentFire(Whiteboard wb, IRobotSnapshot opponent, boolean isA) {
        double currentEnergy = opponent.getEnergy();
        double prevEnergy = isA ? prevEnergyA : prevEnergyB;

        if (!Double.isNaN(prevEnergy)) {
            double drop = prevEnergy - currentEnergy;
            // Fire power is between 0.1 and 3.0
            if (drop >= 0.1 && drop <= 3.0) {
                wb.setOpponentFired(drop);
            }
        }

        if (isA) {
            prevEnergyA = currentEnergy;
        } else {
            prevEnergyB = currentEnergy;
        }
    }

    /** Determine round winner by robot states. */
    public void finalizeRound(Whiteboard wbA, Whiteboard wbB,
            IRobotSnapshot robotA, IRobotSnapshot robotB) {
        // Winner has more energy or is alive while opponent is dead
        boolean aDead = (robotA.getState() == RobotState.DEAD);
        boolean bDead = (robotB.getState() == RobotState.DEAD);
        if (aDead && !bDead) {
            wbA.setRoundResult(-1);
            wbB.setRoundResult(1);
        } else if (bDead && !aDead) {
            wbA.setRoundResult(1);
            wbB.setRoundResult(-1);
        } else {
            // Tie or timeout — compare energy
            double diff = robotA.getEnergy() - robotB.getEnergy();
            wbA.setRoundResult(diff > 0 ? 1 : (diff < 0 ? -1 : 0));
            wbB.setRoundResult(diff > 0 ? -1 : (diff < 0 ? 1 : 0));
        }
    }

    /**
     * Check if a radar sweep arc (pie shape) intersects a robot's bounding box.
     * Replicates Robocode engine's scan detection logic.
     */
    static boolean radarSweepIntersects(double selfX, double selfY,
            double prevRadarHeading, double currentRadarHeading,
            double targetX, double targetY) {
        // Compute sweep angle
        double sweep = currentRadarHeading - prevRadarHeading;
        // Normalize to [-PI, PI]
        while (sweep > Math.PI)
            sweep -= 2 * Math.PI;
        while (sweep < -Math.PI)
            sweep += 2 * Math.PI;

        if (Math.abs(sweep) < 0.0001) {
            sweep = 0.001; // Minimum sweep
        }

        // Convert to Java2D angles (degrees, counter-clockwise from east)
        double startAngleDeg = 90 - Math.toDegrees(prevRadarHeading);
        double extentDeg = -Math.toDegrees(sweep);

        Arc2D arc = new Arc2D.Double(
                selfX - SCAN_RADIUS, selfY - SCAN_RADIUS,
                2 * SCAN_RADIUS, 2 * SCAN_RADIUS,
                startAngleDeg, extentDeg, Arc2D.PIE);

        double halfSize = ROBOT_SIZE / 2;
        Rectangle2D targetBox = new Rectangle2D.Double(
                targetX - halfSize, targetY - halfSize,
                ROBOT_SIZE, ROBOT_SIZE);

        return arc.intersects(targetBox);
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

package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;

import java.io.IOException;

/**
 * Replays turn snapshots into two Whiteboard perspectives.
 * Delegates scan synthesis to {@link ScanSynthesizer} and
 * damage detection to {@link DamageDetector}.
 */
public final class Player {
    private final Whiteboard wbA;
    private final Whiteboard wbB;
    private final ScanSynthesizer scanner;
    private final DamageDetector damage;

    private int currentRound = -1;
    private boolean deadA = false;
    private boolean deadB = false;

    public Player(Whiteboard wbA, Whiteboard wbB) {
        this.wbA = wbA;
        this.wbB = wbB;
        this.scanner = new ScanSynthesizer();
        this.damage = new DamageDetector(wbA, wbB);
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
            scanner.reset();
            damage.reset();
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

        // Stop processing only after robot is truly dead (not just disabled).
        // A disabled robot (energy=0) can recover from bullet hits and still
        // receives scan events.
        if (robotA.getState() == RobotState.DEAD)
            deadA = true;
        if (robotB.getState() == RobotState.DEAD)
            deadB = true;

        long tick = (long) turn.getTurn();

        // Detect bullet hits and rams first — robot receives these events
        // (onBulletHit, onHitByBullet) before onScannedRobot, so accumulators
        // must be updated before FireFeatures reads them on scan ticks.
        if (!deadA || !deadB) {
            damage.detectBulletHits(turn, deadA, deadB);
            damage.detectRams(robotA, robotB, deadA, deadB);
        }

        // Inject perspective A: robotA is "us", robotB is opponent
        if (!deadA) {
            injectOwnState(wbA, robotA, tick, bfWidth, bfHeight);
            if (!deadB)
                scanner.tryScan(wbA, robotA, robotB, tick, true);
        }

        // Inject perspective B: robotB is "us", robotA is opponent
        if (!deadB) {
            injectOwnState(wbB, robotB, tick, bfWidth, bfHeight);
            if (!deadA)
                scanner.tryScan(wbB, robotB, robotA, tick, false);
        }

        return newRound;
    }

    private static void injectOwnState(Whiteboard wb, IRobotSnapshot self,
            long tick, double bfWidth, double bfHeight) {
        wb.setFeature(Feature.TICK, tick);
        wb.setFeature(Feature.OUR_X, self.getX());
        wb.setFeature(Feature.OUR_Y, self.getY());
        wb.setFeature(Feature.OUR_HEADING, self.getBodyHeading());
        wb.setFeature(Feature.OUR_VELOCITY, self.getVelocity());
        wb.setFeature(Feature.OUR_ENERGY, self.getEnergy());
        wb.setFeature(Feature.GUN_HEAT, self.getGunHeat());
        wb.setFeature(Feature.GUN_HEADING, self.getGunHeading());
        wb.setFeature(Feature.RADAR_HEADING, self.getRadarHeading());
        wb.setFeature(Feature.BATTLEFIELD_WIDTH, bfWidth);
        wb.setFeature(Feature.BATTLEFIELD_HEIGHT, bfHeight);
    }

    /** Determine round winner by robot states. */
    public void finalizeRound(Whiteboard wbA, Whiteboard wbB,
            IRobotSnapshot robotA, IRobotSnapshot robotB) {
        boolean aDead = (robotA.getState() == RobotState.DEAD);
        boolean bDead = (robotB.getState() == RobotState.DEAD);
        if (aDead && !bDead) {
            wbA.setFeature(Feature.ROUND_RESULT, -1);
            wbB.setFeature(Feature.ROUND_RESULT, 1);
        } else if (bDead && !aDead) {
            wbA.setFeature(Feature.ROUND_RESULT, 1);
            wbB.setFeature(Feature.ROUND_RESULT, -1);
        } else {
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

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
    private final Perspective[] perspectives;
    private final ScanSynthesizer scanner;
    private final DamageDetector damage;

    private int currentRound = -1;

    public Player(Perspective[] perspectives) {
        this.perspectives = perspectives;
        this.scanner = new ScanSynthesizer();
        this.damage = new DamageDetector();
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
            damage.reset();
            for (Perspective us : perspectives) {
                us.resetRound();
                us.wb().clearFeatures();
            }
        }

        IRobotSnapshot[] robots = turn.getRobots();
        if (robots.length < 2) {
            return newRound;
        }

        // Mark dead perspectives
        for (Perspective us : perspectives) {
            if (robots[us.robotIndex()].getState() == RobotState.DEAD)
                us.setDead(true);
        }

        long tick = (long) turn.getTurn();

        // Detect bullet hits and rams first — robot receives these events
        // (onBulletHit, onHitByBullet) before onScannedRobot, so accumulators
        // must be updated before FireFeatures reads them on scan ticks.
        boolean anyAlive = !perspectives[0].isDead() || !perspectives[1].isDead();
        if (anyAlive) {
            damage.detectBulletHits(turn, perspectives);
            damage.detectRams(robots, perspectives);
        }

        // Inject state for each perspective: "us" is self, "them" is opponent
        for (Perspective us : perspectives) {
            if (!us.isDead()) {
                injectOwnState(us.wb(), robots[us.robotIndex()], tick, bfWidth, bfHeight);
                if (!us.peer().isDead())
                    scanner.tryScan(us, robots[us.robotIndex()], robots[us.peer().robotIndex()], tick);
            }
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

    /** Determine round winner and set ROUND_RESULT for both perspectives. */
    public void finalizeRound(Perspective[] perspectives) {
        IRobotSnapshot robot0 = perspectives[0].lastRobot();
        IRobotSnapshot robot1 = perspectives[1].lastRobot();
        if (robot0 == null || robot1 == null)
            return;

        boolean dead0 = (robot0.getState() == RobotState.DEAD);
        boolean dead1 = (robot1.getState() == RobotState.DEAD);

        double result0;
        if (dead0 && !dead1) {
            result0 = -1;
        } else if (dead1 && !dead0) {
            result0 = 1;
        } else {
            double diff = robot0.getEnergy() - robot1.getEnergy();
            result0 = diff > 0 ? 1 : (diff < 0 ? -1 : 0);
        }

        perspectives[0].wb().setFeature(Feature.ROUND_RESULT, result0);
        perspectives[1].wb().setFeature(Feature.ROUND_RESULT, -result0);
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

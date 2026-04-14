package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.util.RoboMath;
import robocode.BattleRules;
import robocode.Rules;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;

import java.awt.geom.Arc2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Replays TurnSnapshot data through synthesized robot events.
 * Stateful processor: construct with two Whiteboards, then call
 * {@link #processTurn} per tick or {@link #replay} for a full recording.
 */
public final class Player {

    private static final int ROBOT_WIDTH = 36;
    private static final int ROBOT_HEIGHT = 36;

    private final Whiteboard wbA;
    private final Whiteboard wbB;

    private int lastRound = -1;
    private double prevRadarA = Double.NaN;
    private double prevRadarB = Double.NaN;
    private final Map<Integer, BulletState> prevBulletStates = new HashMap<Integer, BulletState>();

    public Player(Whiteboard wbA, Whiteboard wbB) {
        this.wbA = wbA;
        this.wbB = wbB;
    }

    /**
     * Process one turn snapshot. Handles round transitions, own state injection,
     * scan synthesis, and bullet event processing for both perspectives.
     *
     * @return true if this was the first tick of a new round
     */
    public boolean processTurn(int roundIndex, ITurnSnapshot turn,
                               int bfWidth, int bfHeight,
                               double gunCoolingRate, int numRounds) {
        IRobotSnapshot[] robots = turn.getRobots();
        if (robots.length < 2) {
            return false;
        }

        boolean newRound = (roundIndex != lastRound);
        if (newRound) {
            wbA.onRoundStart(roundIndex, bfWidth, bfHeight, gunCoolingRate, numRounds);
            wbB.onRoundStart(roundIndex, bfWidth, bfHeight, gunCoolingRate, numRounds);
            prevRadarA = Double.NaN;
            prevRadarB = Double.NaN;
            prevBulletStates.clear();
            lastRound = roundIndex;
        }

        IRobotSnapshot robotA = robots[0];
        IRobotSnapshot robotB = robots[1];
        int indexA = robotA.getContestantIndex();
        int indexB = robotB.getContestantIndex();

        wbA.setTick(turn.getTurn());
        wbB.setTick(turn.getTurn());

        wbA.setOurState(
                robotA.getX(), robotA.getY(),
                robotA.getBodyHeading(), robotA.getGunHeading(), robotA.getRadarHeading(),
                robotA.getVelocity(), robotA.getEnergy(), robotA.getGunHeat());

        wbB.setOurState(
                robotB.getX(), robotB.getY(),
                robotB.getBodyHeading(), robotB.getGunHeading(), robotB.getRadarHeading(),
                robotB.getVelocity(), robotB.getEnergy(), robotB.getGunHeat());

        // Bullet event synthesis
        processBulletEvents(turn, indexA, indexB);

        // Scan synthesis
        boolean isFirstTick = (turn.getTurn() == 0);

        if (robotB.getState() != RobotState.DEAD) {
            if (isFirstTick || radarSweepIntersects(
                    robotA.getX(), robotA.getY(), prevRadarA,
                    robotA.getRadarHeading(),
                    robotB.getX(), robotB.getY())) {
                wbA.setOpponentScan(
                        robotB.getX(), robotB.getY(),
                        robotB.getBodyHeading(), robotB.getVelocity(), robotB.getEnergy());
            }
        }

        if (robotA.getState() != RobotState.DEAD) {
            if (isFirstTick || radarSweepIntersects(
                    robotB.getX(), robotB.getY(), prevRadarB,
                    robotB.getRadarHeading(),
                    robotA.getX(), robotA.getY())) {
                wbB.setOpponentScan(
                        robotA.getX(), robotA.getY(),
                        robotA.getBodyHeading(), robotA.getVelocity(), robotA.getEnergy());
            }
        }

        prevRadarA = robotA.getRadarHeading();
        prevRadarB = robotB.getRadarHeading();

        return newRound;
    }

    private void processBulletEvents(ITurnSnapshot turn, int indexA, int indexB) {
        IBulletSnapshot[] bullets = turn.getBullets();
        if (bullets == null) return;

        Map<Integer, BulletState> currentStates = new HashMap<Integer, BulletState>();
        for (IBulletSnapshot b : bullets) {
            BulletState state = b.getState();
            if (state == BulletState.INACTIVE || state == BulletState.EXPLODED) {
                continue;
            }

            int bulletId = b.getBulletId();
            currentStates.put(bulletId, state);
            int owner = b.getOwnerIndex();
            int victim = b.getVictimIndex();
            double power = b.getPower();
            BulletState prev = prevBulletStates.get(bulletId);

            if (prev == null) {
                if (owner == indexA) {
                    wbA.incrementOurShotsFired();
                } else if (owner == indexB) {
                    wbB.incrementOurShotsFired();
                }
            }

            if (state == BulletState.HIT_VICTIM && prev != BulletState.HIT_VICTIM) {
                double damage = Rules.getBulletDamage(power);

                if (owner == indexA && victim == indexB) {
                    wbA.setWeHitOpponentThisTick(true);
                    wbA.addDamageDealt(damage);
                    wbA.incrementOurBulletHitCount();
                    wbB.addDamageReceived(damage);
                    wbB.incrementOpponentBulletHitCount();
                } else if (owner == indexB && victim == indexA) {
                    wbB.setWeHitOpponentThisTick(true);
                    wbB.addDamageDealt(damage);
                    wbB.incrementOurBulletHitCount();
                    wbA.addDamageReceived(damage);
                    wbA.incrementOpponentBulletHitCount();
                }
            }
        }

        prevBulletStates.clear();
        prevBulletStates.putAll(currentStates);
    }

    /**
     * Replays all turns from a Loader through {@link #processTurn}.
     */
    public void replay(Loader loader) {
        try {
            loader.forEachTurn(new Loader.TurnConsumer() {
                public void accept(int roundIndex, ITurnSnapshot turn) {
                    BattleRules rules = loader.getRecordInfo().battleRules;
                    processTurn(roundIndex, turn,
                            rules.getBattlefieldWidth(), rules.getBattlefieldHeight(),
                            rules.getGunCoolingRate(), loader.getRecordInfo().roundsCount);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to read recording: " + loader.getPath(), e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Failed to deserialize recording: " + loader.getPath(), e);
        }
    }

    /**
     * Determines if a radar sweep from prevRadarHeading to currRadarHeading
     * intersects the opponent's 36x36 bounding box.
     * Replicates the engine's RobotPeer.scan() logic using Arc2D intersection.
     */
    static boolean radarSweepIntersects(
            double ourX, double ourY,
            double prevRadarHeading, double currRadarHeading,
            double opponentX, double opponentY) {

        if (Double.isNaN(prevRadarHeading)) {
            return false;
        }

        double scanRadians = currRadarHeading - prevRadarHeading;

        // Normalize scan angle to [-PI, PI] — same as engine
        if (scanRadians < -Math.PI) {
            scanRadians = 2 * Math.PI + scanRadians;
        } else if (scanRadians > Math.PI) {
            scanRadians = scanRadians - 2 * Math.PI;
        }

        if (Math.abs(scanRadians) < 1e-10) {
            return false; // No sweep
        }

        // Convert robocode heading to Java2D angle:
        // Robocode: 0=North, clockwise
        // Java2D Arc2D: 0=East, counterclockwise
        // offset by -PI/2, then negate for Arc2D
        double startAngle = prevRadarHeading - Math.PI / 2;
        startAngle = RoboMath.normalAbsoluteAngle(startAngle);

        // Arc2D uses degrees
        double startDegrees = Math.toDegrees(startAngle);
        double extentDegrees = Math.toDegrees(scanRadians);

        double radius = Rules.RADAR_SCAN_RADIUS;
        Arc2D.Double scanArc = new Arc2D.Double(
                ourX - radius, ourY - radius,
                2 * radius, 2 * radius,
                startDegrees, extentDegrees,
                Arc2D.PIE);

        Rectangle2D.Double opponentBBox = new Rectangle2D.Double(
                opponentX - ROBOT_WIDTH / 2.0,
                opponentY - ROBOT_HEIGHT / 2.0,
                ROBOT_WIDTH, ROBOT_HEIGHT);

        return intersects(scanArc, opponentBBox);
    }

    /**
     * Same intersection logic as the engine's RobotPeer.intersects().
     */
    private static boolean intersects(Arc2D arc, Rectangle2D rect) {
        return rect.intersectsLine(
                arc.getCenterX(), arc.getCenterY(),
                arc.getStartPoint().getX(), arc.getStartPoint().getY())
                || arc.intersects(rect);
    }

    /**
     * Returns the robot names from the recording (index 0 and 1).
     * Reads the recording if not already read.
     */
    public static String[] getRobotNames(Loader loader) {
        final String[][] names = new String[1][];
        try {
            loader.forEachTurn(new Loader.TurnConsumer() {
                public void accept(int roundIndex, ITurnSnapshot turn) {
                    if (names[0] == null) {
                        IRobotSnapshot[] robots = turn.getRobots();
                        names[0] = new String[robots.length];
                        for (int i = 0; i < robots.length; i++) {
                            names[0][i] = robots[i].getShortName();
                        }
                    }
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to read recording: " + loader.getPath(), e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Failed to deserialize recording: " + loader.getPath(), e);
        }
        return names[0];
    }
}

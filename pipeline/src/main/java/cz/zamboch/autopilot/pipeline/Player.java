package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.util.RoboMath;
import robocode.BattleRules;
import robocode.Rules;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;

import java.awt.geom.Arc2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;

/**
 * Replays TurnSnapshot data through synthesized robot events.
 * Phase B: extracts own-robot state per tick for both perspectives.
 * Phase C: synthesizes ScannedRobotEvent via radar arc intersection.
 */
public class Player {

    private static final int ROBOT_WIDTH = 36;
    private static final int ROBOT_HEIGHT = 36;

    /**
     * Replays all turns from a Loader, feeding own-robot state into two Whiteboards
     * (one per robot perspective in a 1v1 battle).
     */
    public void replay(Loader loader, Whiteboard whiteboardA, Whiteboard whiteboardB) {
        try {
            loader.forEachTurn(new Loader.TurnConsumer() {
                private int lastRound = -1;
                private double prevRadarHeadingA = Double.NaN;
                private double prevRadarHeadingB = Double.NaN;

                public void accept(int roundIndex, ITurnSnapshot turn) {
                    IRobotSnapshot[] robots = turn.getRobots();
                    if (robots.length < 2) {
                        return;
                    }

                    // New round: initialize whiteboards
                    if (roundIndex != lastRound) {
                        BattleRules rules = loader.getRecordInfo().battleRules;
                        int bfWidth = rules.getBattlefieldWidth();
                        int bfHeight = rules.getBattlefieldHeight();
                        double gunCoolingRate = rules.getGunCoolingRate();
                        int numRounds = loader.getRecordInfo().roundsCount;

                        whiteboardA.onRoundStart(roundIndex, bfWidth, bfHeight, gunCoolingRate, numRounds);
                        whiteboardB.onRoundStart(roundIndex, bfWidth, bfHeight, gunCoolingRate, numRounds);
                        prevRadarHeadingA = Double.NaN;
                        prevRadarHeadingB = Double.NaN;
                        lastRound = roundIndex;
                    }

                    IRobotSnapshot robotA = robots[0];
                    IRobotSnapshot robotB = robots[1];

                    // Set tick
                    whiteboardA.setTick(turn.getTurn());
                    whiteboardB.setTick(turn.getTurn());

                    // Feed own-robot state from god-view snapshot
                    whiteboardA.setOurState(
                            robotA.getX(), robotA.getY(),
                            robotA.getBodyHeading(), robotA.getGunHeading(), robotA.getRadarHeading(),
                            robotA.getVelocity(), robotA.getEnergy(), robotA.getGunHeat());

                    whiteboardB.setOurState(
                            robotB.getX(), robotB.getY(),
                            robotB.getBodyHeading(), robotB.getGunHeading(), robotB.getRadarHeading(),
                            robotB.getVelocity(), robotB.getEnergy(), robotB.getGunHeat());

                    // === Scan synthesis ===
                    boolean isFirstTick = (turn.getTurn() == 0);

                    // Robot A scanning for robot B
                    if (robotB.getState() != RobotState.DEAD) {
                        if (isFirstTick || radarSweepIntersects(
                                robotA.getX(), robotA.getY(), prevRadarHeadingA,
                                robotA.getRadarHeading(),
                                robotB.getX(), robotB.getY())) {
                            whiteboardA.setOpponentScan(
                                    robotB.getX(), robotB.getY(),
                                    robotB.getBodyHeading(), robotB.getVelocity(), robotB.getEnergy());
                        }
                    }

                    // Robot B scanning for robot A
                    if (robotA.getState() != RobotState.DEAD) {
                        if (isFirstTick || radarSweepIntersects(
                                robotB.getX(), robotB.getY(), prevRadarHeadingB,
                                robotB.getRadarHeading(),
                                robotA.getX(), robotA.getY())) {
                            whiteboardB.setOpponentScan(
                                    robotA.getX(), robotA.getY(),
                                    robotA.getBodyHeading(), robotA.getVelocity(), robotA.getEnergy());
                        }
                    }

                    // Remember radar headings for next tick's sweep calculation
                    prevRadarHeadingA = robotA.getRadarHeading();
                    prevRadarHeadingB = robotB.getRadarHeading();

                    // TODO Phase F: bullet events, energy drop detection
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
     */
    public static String[] getRobotNames(Loader loader) {
        if (loader.getRecordInfo() == null) {
            throw new IllegalStateException("Loader must be read (forEachTurn) before calling getRobotNames");
        }
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

package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Whiteboard;
import robocode.control.snapshot.IRobotSnapshot;

/**
 * Represents one robot's viewpoint in a 1v1 battle.
 * <p>
 * From this perspective, the owning robot is "us" (with a {@link Whiteboard}
 * tracking our state and opponent features) and the other robot is "them"
 * (accessed via {@link #peer()}).
 * <p>
 * Two perspectives are always created as a linked pair via {@link #createPair}.
 */
public final class Perspective {

    private final Whiteboard wb;
    private final int robotIndex; // 0 or 1
    private boolean ours;
    private boolean dead;
    private CsvWriter csv; // nullable — only when CSV output is enabled
    private IRobotSnapshot lastRobot;
    private double prevRadarHeading = Double.NaN;
    private Perspective peer;

    private Perspective(int robotIndex, Whiteboard wb) {
        this.robotIndex = robotIndex;
        this.wb = wb;
    }

    /**
     * Create a linked pair of perspectives for a 1v1 battle.
     * Index 0 and 1 correspond to the robot array indices in turn snapshots.
     */
    public static Perspective[] createPair(Whiteboard wb0, Whiteboard wb1) {
        Perspective a = new Perspective(0, wb0);
        Perspective b = new Perspective(1, wb1);
        a.peer = b;
        b.peer = a;
        return new Perspective[] { a, b };
    }

    /** Reset per-round mutable state. */
    public void resetRound() {
        dead = false;
        prevRadarHeading = Double.NaN;
    }

    // --- Accessors ---

    public Whiteboard wb() {
        return wb;
    }

    public int robotIndex() {
        return robotIndex;
    }

    public boolean isOurs() {
        return ours;
    }

    public void setOurs(boolean ours) {
        this.ours = ours;
    }

    public boolean isDead() {
        return dead;
    }

    public void setDead(boolean dead) {
        this.dead = dead;
    }

    public CsvWriter csv() {
        return csv;
    }

    public void setCsv(CsvWriter csv) {
        this.csv = csv;
    }

    public IRobotSnapshot lastRobot() {
        return lastRobot;
    }

    public void setLastRobot(IRobotSnapshot lastRobot) {
        this.lastRobot = lastRobot;
    }

    public double prevRadarHeading() {
        return prevRadarHeading;
    }

    public void setPrevRadarHeading(double heading) {
        this.prevRadarHeading = heading;
    }

    public Perspective peer() {
        return peer;
    }
}

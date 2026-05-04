package cz.zamboch.autopilot.core.movement;

/**
 * A candidate position reachable from the current robot state at a
 * future tick, with associated metadata for scoring.
 */
public final class CandidatePosition {

    /** Absolute position. */
    public final double x, y;

    /** Guess factor at this position relative to wave origin. */
    public final double gf;

    /** Tick offset at which this position is reached. */
    public final int reachTick;

    /** Computed danger score (filled in by scorer). */
    public double danger;

    public CandidatePosition(double x, double y, double gf, int reachTick) {
        this.x = x;
        this.y = y;
        this.gf = gf;
        this.reachTick = reachTick;
    }
}

package cz.zamboch.autopilot.core.movement;

/**
 * A candidate position reachable from the current robot state at a
 * future tick, with associated metadata for scoring.
 *
 * <p>Mutable — can be reused via {@link #set} to avoid per-tick allocation.
 * Pre-allocate a buffer of these on the Whiteboard and write into them.
 */
public final class CandidatePosition {

    /** Absolute position. */
    public double x, y;

    /** Guess factor at this position relative to wave origin. */
    public double gf;

    /** Tick offset at which this position is reached. */
    public int reachTick;

    /** Computed danger score (filled in by scorer). */
    public double danger;

    public CandidatePosition() {}

    public CandidatePosition(double x, double y, double gf, int reachTick) {
        this.x = x;
        this.y = y;
        this.gf = gf;
        this.reachTick = reachTick;
    }

    /** Overwrite all fields. Returns {@code this} for chaining. */
    public CandidatePosition set(double x, double y, double gf, int reachTick) {
        this.x = x;
        this.y = y;
        this.gf = gf;
        this.reachTick = reachTick;
        this.danger = 0;
        return this;
    }
}

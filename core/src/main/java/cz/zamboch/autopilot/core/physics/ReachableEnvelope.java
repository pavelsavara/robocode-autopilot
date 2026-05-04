package cz.zamboch.autopilot.core.physics;

import cz.zamboch.autopilot.core.movement.CandidatePosition;

/**
 * Pre-computed reachable positions at tick+N from a given initial velocity.
 * Stores relative (dx, dy) offsets from the starting position (heading = 0).
 * At runtime: rotate by current heading, translate to current position,
 * clamp to walls.
 *
 * Phase 1: stub — pre-computed envelope data not yet generated.
 */
public final class ReachableEnvelope {

    private ReachableEnvelope() {}

    /**
     * Get candidate positions reachable from the given state at tick+horizon.
     * Phase 1 stub: returns empty array.
     *
     * @param current starting robot state
     * @param horizon ticks into the future
     * @param bfW battlefield width
     * @param bfH battlefield height
     * @return array of reachable candidate positions
     */
    public static CandidatePosition[] getCandidates(
            RobotState current, int horizon, int bfW, int bfH) {
        // Phase 1: no pre-computed envelopes available
        return new CandidatePosition[0];
    }
}

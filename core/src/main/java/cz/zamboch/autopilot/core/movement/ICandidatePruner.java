package cz.zamboch.autopilot.core.movement;

import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Fast pre-selection of top-K candidate positions from a larger set.
 * Used by PathPlanner to reduce the number of candidates scored by
 * expensive danger functions.
 */
public interface ICandidatePruner {

    /**
     * Score candidates cheaply and return indices of the top-K.
     *
     * @param candidates array of candidate positions
     * @param k maximum number to return
     * @param wb current state
     * @return indices into candidates array
     */
    int[] prune(CandidatePosition[] candidates, int k, Whiteboard wb);
}

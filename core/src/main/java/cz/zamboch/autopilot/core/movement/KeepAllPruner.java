package cz.zamboch.autopilot.core.movement;

import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Phase 1-2 default pruner: returns all indices unchanged.
 * VCS danger is cheap enough to score all ~280 candidates.
 */
public final class KeepAllPruner implements ICandidatePruner {

    @Override
    public int[] prune(CandidatePosition[] candidates, int k, Whiteboard wb) {
        int n = Math.min(candidates.length, k);
        // Return all indices (or first k if k < candidates.length)
        int count = candidates.length;
        int[] indices = new int[count];
        for (int i = 0; i < count; i++) {
            indices[i] = i;
        }
        return indices;
    }
}

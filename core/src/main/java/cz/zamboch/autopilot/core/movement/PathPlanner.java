package cz.zamboch.autopilot.core.movement;

import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.gun.Wave;
import cz.zamboch.autopilot.core.strategy.StrategyParams;

import java.util.List;

/**
 * Path planner: envelope lookup + prune + score + select.
 * Not used in Phase 1. Phases 2+ use this for wave-surf movement.
 */
public final class PathPlanner {

    private final IPositionDanger posDanger;
    private final IWaveDanger waveDanger;
    private final ICandidatePruner pruner;
    private final int bfW, bfH;

    public PathPlanner(IPositionDanger posDanger, IWaveDanger waveDanger,
                       ICandidatePruner pruner, int bfW, int bfH) {
        this.posDanger = posDanger;
        this.waveDanger = waveDanger;
        this.pruner = pruner;
        this.bfW = bfW;
        this.bfH = bfH;
    }

    /**
     * Find the best candidate position considering wave danger + position danger.
     * Stub for Phase 1 — returns null.
     *
     * @param wb current whiteboard state
     * @param params strategic parameters
     * @param activeWaves opponent waves currently in flight
     * @return best candidate, or null if no candidates available
     */
    public CandidatePosition bestCandidate(Whiteboard wb, StrategyParams params,
                                           List<Wave> activeWaves) {
        // Phase 1: not implemented — path planning is Phase 2+
        return null;
    }
}

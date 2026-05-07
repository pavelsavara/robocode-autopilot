package cz.zamboch.autopilot.core.movement;

import cz.zamboch.autopilot.core.WaveRecord;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.physics.ReachableEnvelope;
import cz.zamboch.autopilot.core.physics.RobotState;
import cz.zamboch.autopilot.core.strategy.StrategyParams;

import java.util.List;

/**
 * Path planner: envelope lookup → prune → score → select best candidate.
 *
 * <p>Uses pre-allocated buffers for zero per-tick allocation. The planner
 * is reused across ticks — call {@link #plan} each tick to get the best
 * candidate position to move toward.</p>
 *
 * <p>Scoring: each candidate gets
 * {@code danger = posWeight * positionDanger + waveWeight * waveDanger}.
 * The lowest-danger candidate wins.</p>
 */
public final class PathPlanner {

    /** Maximum candidates to evaluate per tick. */
    private static final int MAX_CANDIDATES = 30;

    /** Weight for position-based danger (walls, distance). */
    private static final double POS_WEIGHT = 0.3;

    /** Weight for wave-based danger (incoming bullets). */
    private static final double WAVE_WEIGHT = 0.7;

    private final IPositionDanger posDanger;
    private final IWaveDanger waveDanger;
    private final ICandidatePruner pruner;
    private final int bfW, bfH;

    /** Pre-allocated candidate buffer. */
    private final CandidatePosition[] candidates;

    /** Number of valid candidates from the last plan() call. */
    private int candidateCount;

    public PathPlanner(IPositionDanger posDanger, IWaveDanger waveDanger,
                       ICandidatePruner pruner, int bfW, int bfH) {
        this.posDanger = posDanger;
        this.waveDanger = waveDanger;
        this.pruner = pruner;
        this.bfW = bfW;
        this.bfH = bfH;
        this.candidates = new CandidatePosition[MAX_CANDIDATES];
        for (int i = 0; i < MAX_CANDIDATES; i++) {
            candidates[i] = new CandidatePosition();
        }
    }

    /**
     * Find the best candidate position considering wave danger + position danger.
     *
     * @param wb current whiteboard state
     * @param params strategic parameters
     * @return best candidate, or null if no candidates available
     */
    public CandidatePosition plan(Whiteboard wb, StrategyParams params) {
        RobotState current = RobotState.fromWhiteboard(wb);

        // Get jittered candidates from reachable envelope
        candidateCount = ReachableEnvelope.getCandidatesInto(
                current, bfW, bfH, candidates, MAX_CANDIDATES);

        if (candidateCount == 0) {
            return null;
        }

        // Get active opponent waves
        List<WaveRecord> waves = wb.getOpponentWaves();

        // Score each candidate
        CandidatePosition best = null;
        double bestDanger = Double.MAX_VALUE;

        for (int i = 0; i < candidateCount; i++) {
            CandidatePosition c = candidates[i];

            double pd = posDanger.danger(c.x, c.y, wb);
            double wd = waves.isEmpty() ? 0
                    : waveDanger.danger(c, waves, wb, params.randomWaveSelection);

            c.danger = POS_WEIGHT * pd + WAVE_WEIGHT * wd;

            if (c.danger < bestDanger) {
                bestDanger = c.danger;
                best = c;
            }
        }

        return best;
    }

    /** Number of candidates evaluated in the last plan() call. */
    public int getLastCandidateCount() {
        return candidateCount;
    }
}

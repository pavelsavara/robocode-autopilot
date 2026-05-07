package cz.zamboch.autopilot.core.movement;

import cz.zamboch.autopilot.core.WaveRecord;
import cz.zamboch.autopilot.core.Whiteboard;

import java.util.List;

/**
 * Scores wave-based danger for a candidate position against
 * incoming opponent waves.
 */
public interface IWaveDanger {

    /**
     * Danger of a candidate against a single incoming wave.
     *
     * @return danger in [0, 1]: 0 = safe, 1 = maximum danger
     */
    double danger(CandidatePosition candidate, WaveRecord wave, Whiteboard wb);

    /**
     * Multi-wave danger: combined danger weighted by bullet damage and urgency.
     * When randomWaveSelection is true, randomly pick one wave to dodge
     * (proportional to damage × urgency) for anti-exploitation.
     *
     * @return combined danger in [0, 1]
     */
    double danger(CandidatePosition candidate, List<WaveRecord> waves,
                  Whiteboard wb, boolean randomWaveSelection);
}

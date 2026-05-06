package cz.zamboch.autopilot.core.movement;

import cz.zamboch.autopilot.core.WaveRecord;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.util.RoboMath;

import java.util.List;

/**
 * Phase 1 wave danger: assumes bots aim roughly at head-on (GF=0)
 * with a Gaussian falloff. No ML model — just a prior that being
 * near GF=0 is dangerous.
 *
 * <p>Multi-wave scoring uses damage-weighted combination: a power-3.0
 * bullet contributes 40× the danger weight of a power-0.1 bullet.</p>
 */
public final class UniformWaveDanger implements IWaveDanger {

    /** Gaussian std for GF danger — peaks at GF=0, decays toward ±1. */
    private static final double GF_STD = 0.4;
    private static final double GF_NORM = 1.0 / (GF_STD * Math.sqrt(2 * Math.PI));

    @Override
    public double danger(CandidatePosition candidate, WaveRecord wave, Whiteboard wb) {
        // Compute GF of this candidate relative to the wave
        double fireBearing = Math.atan2(
                wb.getOurX() - wave.originX,
                wb.getOurY() - wave.originY);
        double candBearing = Math.atan2(
                candidate.x - wave.originX,
                candidate.y - wave.originY);
        double offset = RoboMath.normalRelativeAngle(candBearing - fireBearing);

        double mea = Math.asin(8.0 / wave.bulletSpeed);
        double gf = offset / mea;
        if (gf > 1.0) gf = 1.0;
        if (gf < -1.0) gf = -1.0;

        // Gaussian danger centered at GF=0
        double d = Math.exp(-0.5 * (gf / GF_STD) * (gf / GF_STD));
        return d; // [0, 1] — 1.0 at GF=0, ~0.04 at GF=±1
    }

    @Override
    public double danger(CandidatePosition candidate, List<WaveRecord> waves, Whiteboard wb) {
        if (waves.isEmpty()) {
            return 0;
        }
        // Damage-weighted combination
        double totalWeight = 0;
        double weightedDanger = 0;
        for (int i = 0; i < waves.size(); i++) {
            WaveRecord w = waves.get(i);
            double dmg = w.damage();
            double d = danger(candidate, w, wb);
            weightedDanger += d * dmg;
            totalWeight += dmg;
        }
        return totalWeight > 0 ? weightedDanger / totalWeight : 0;
    }
}

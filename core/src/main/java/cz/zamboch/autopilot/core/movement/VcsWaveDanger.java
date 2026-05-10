package cz.zamboch.autopilot.core.movement;

import cz.zamboch.autopilot.core.WaveRecord;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.util.RoboMath;

import java.util.List;
import java.util.Random;

/**
 * VCS-based wave danger: uses the opponent's actual GF histogram
 * (built from HitByBullet observations) instead of a Gaussian at GF=0.
 *
 * <p>Combines histogram data with a Gaussian prior for early-game
 * robustness (before we have enough observations).</p>
 *
 * <p>Urgency weighting: imminent waves dominate danger scoring.
 * Optional random wave selection for anti-exploitation movement.</p>
 */
public final class VcsWaveDanger implements IWaveDanger {

    /** Gaussian prior std — used until VCS has enough observations. */
    private static final double PRIOR_STD = 0.4;
    /** Wider prior std — used when very few observations (less directional bias). */
    private static final double WIDE_PRIOR_STD = 0.8;
    /** Threshold: below this many observations, use the wider prior. */
    private static final int WIDE_PRIOR_THRESHOLD = 8;
    /** Prior equivalent to this many observations at GF=0.
     *  Lower value = VCS data dominates sooner (faster convergence). */
    private static final double PRIOR_WEIGHT = 3.0;

    private final Random rng = new Random();

    @Override
    public double danger(CandidatePosition candidate, WaveRecord wave, Whiteboard wb) {
        double fireBearing;
        if (!Double.isNaN(wave.fireBearing)) {
            fireBearing = wave.fireBearing;
        } else {
            fireBearing = Math.atan2(
                    wb.getOurX() - wave.originX,
                    wb.getOurY() - wave.originY);
        }

        double candBearing = Math.atan2(
                candidate.x - wave.originX,
                candidate.y - wave.originY);
        double offset = RoboMath.normalRelativeAngle(candBearing - fireBearing);
        double mea = Math.asin(Math.min(1.0, 8.0 / wave.bulletSpeed));
        double gf = mea > 0 ? offset / mea : 0;
        gf = Math.max(-1.0, Math.min(1.0, gf));

        int bin = Whiteboard.gfToBin(gf);

        // Determine segment from wave's fire-time context (not current tick)
        int latDir = wave.fireLateralDir;
        if (latDir == 0) latDir = 1;
        int segment = Whiteboard.vcsSegment(wave.fireDistance, latDir);
        int[] hist = wb.getMoveVcsSegment(segment);

        // Total observations in this segment
        int totalObs = 0;
        for (int v : hist) totalObs += v;

        // Combine histogram observation with Gaussian prior.
        // Use wider prior when few observations — less directional bias.
        double histValue = hist[bin];
        double sigma = totalObs < WIDE_PRIOR_THRESHOLD ? WIDE_PRIOR_STD : PRIOR_STD;
        double priorValue = PRIOR_WEIGHT * Math.exp(-0.5 * (gf / sigma) * (gf / sigma));

        double danger = (histValue + priorValue) / (totalObs + PRIOR_WEIGHT);

        return Math.min(1.0, danger);
    }

    private static double gaussian(double gf) {
        return Math.exp(-0.5 * (gf / PRIOR_STD) * (gf / PRIOR_STD));
    }

    @Override
    public double danger(CandidatePosition candidate, List<WaveRecord> waves,
                         Whiteboard wb, boolean randomWaveSelection) {
        if (waves.isEmpty()) {
            return 0;
        }

        long tick = wb.getTick();

        if (randomWaveSelection && waves.size() > 1) {
            return dangerRandomWave(candidate, waves, wb, tick);
        }

        // Urgency-weighted combination
        double totalWeight = 0;
        double weightedDanger = 0;
        for (int i = 0; i < waves.size(); i++) {
            WaveRecord w = waves.get(i);
            double dist = Math.hypot(wb.getOurX() - w.originX, wb.getOurY() - w.originY);
            double remaining = dist - w.radius(tick);
            double ticksUntilBreak = w.bulletSpeed > 0 ? remaining / w.bulletSpeed : 1;
            double urgency = 1.0 / Math.max(1.0, ticksUntilBreak);

            double dmg = w.damage();
            double weight = dmg * urgency;
            double d = danger(candidate, w, wb);
            weightedDanger += d * weight;
            totalWeight += weight;
        }
        return totalWeight > 0 ? weightedDanger / totalWeight : 0;
    }

    /** Randomly select ONE wave to dodge, proportional to damage × urgency. */
    private double dangerRandomWave(CandidatePosition candidate, List<WaveRecord> waves,
                                     Whiteboard wb, long tick) {
        double totalWeight = 0;
        for (int i = 0; i < waves.size(); i++) {
            WaveRecord w = waves.get(i);
            double dist = Math.hypot(wb.getOurX() - w.originX, wb.getOurY() - w.originY);
            double remaining = dist - w.radius(tick);
            double ticksUntilBreak = w.bulletSpeed > 0 ? remaining / w.bulletSpeed : 1;
            double urgency = 1.0 / Math.max(1.0, ticksUntilBreak);
            totalWeight += w.damage() * urgency;
        }
        if (totalWeight <= 0) return 0;

        double r = rng.nextDouble() * totalWeight;
        double cumulative = 0;
        for (int i = 0; i < waves.size(); i++) {
            WaveRecord w = waves.get(i);
            double dist = Math.hypot(wb.getOurX() - w.originX, wb.getOurY() - w.originY);
            double remaining = dist - w.radius(tick);
            double ticksUntilBreak = w.bulletSpeed > 0 ? remaining / w.bulletSpeed : 1;
            double urgency = 1.0 / Math.max(1.0, ticksUntilBreak);
            cumulative += w.damage() * urgency;
            if (cumulative >= r) {
                return danger(candidate, w, wb);
            }
        }
        return danger(candidate, waves.get(waves.size() - 1), wb);
    }
}

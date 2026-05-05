package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.WaveRecord;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.physics.ReachableEnvelope;
import cz.zamboch.autopilot.core.physics.RobotState;

import java.util.List;

/**
 * Features derived from the pre-computed reachable envelope. Measures
 * physical movement freedom: wall constraint, distance control range,
 * and GF dodge range against incoming waves.
 *
 * <p>Zero per-tick allocation — uses {@link ReachableEnvelope#scanEnvelope}
 * which iterates the raw byte[] data in a single pass.</p>
 *
 * <p>Not final — the pipeline subclass adds CSV output.</p>
 */
public class EnvelopeFeatures implements IInGameFeatures {

    private static final Feature[] OUTPUTS = {
            Feature.ENVELOPE_FILL_RATIO,
            Feature.REACHABLE_DISTANCE_MIN,
            Feature.REACHABLE_DISTANCE_MAX,
            Feature.REACHABLE_GF_RANGE
    };

    private static final Feature[] DEPS = {
            Feature.OUR_X, Feature.OUR_Y,
            Feature.OUR_HEADING, Feature.OUR_VELOCITY,
            Feature.OPPONENT_X, Feature.OPPONENT_Y,
            Feature.DISTANCE
    };

    /** Scratch buffer for scanEnvelope output. Reused per tick. */
    private final double[] scanOut = new double[6];

    public Feature[] getOutputFeatures() { return OUTPUTS; }
    public Feature[] getDependencies() { return DEPS; }

    public void process(Whiteboard wb) {
        if (!wb.isScanAvailableThisTick()
                || !wb.hasFeature(Feature.OUR_X)
                || !wb.hasFeature(Feature.DISTANCE)) {
            return;
        }

        RobotState current = RobotState.at(
                wb.getFeature(Feature.OUR_X),
                wb.getFeature(Feature.OUR_Y),
                wb.getFeature(Feature.OUR_HEADING),
                wb.getFeature(Feature.OUR_VELOCITY));

        int bfW = wb.getBattlefieldWidth();
        int bfH = wb.getBattlefieldHeight();
        double oppX = wb.getFeature(Feature.OPPONENT_X);
        double oppY = wb.getFeature(Feature.OPPONENT_Y);

        // Find nearest opponent wave for GF computation
        List<WaveRecord> waves = wb.getOpponentWaves();
        double waveOriginX = Double.NaN;
        double waveOriginY = Double.NaN;
        double waveBulletSpeed = 0;
        double fireBearing = 0;

        if (!waves.isEmpty()) {
            WaveRecord nearest = null;
            double nearestRemaining = Double.MAX_VALUE;
            long tick = wb.getTick();
            for (int i = 0; i < waves.size(); i++) {
                WaveRecord w = waves.get(i);
                double dist = Math.hypot(current.x - w.originX, current.y - w.originY);
                double remaining = dist - w.radius(tick);
                if (remaining > 0 && remaining < nearestRemaining) {
                    nearestRemaining = remaining;
                    nearest = w;
                }
            }
            if (nearest != null) {
                waveOriginX = nearest.originX;
                waveOriginY = nearest.originY;
                waveBulletSpeed = nearest.bulletSpeed;
                fireBearing = Math.atan2(
                        current.x - nearest.originX,
                        current.y - nearest.originY);
            }
        }

        // Single-pass zero-allocation scan
        ReachableEnvelope.scanEnvelope(current, bfW, bfH,
                oppX, oppY,
                waveOriginX, waveOriginY,
                waveBulletSpeed, fireBearing,
                scanOut);

        double surviving = scanOut[4];
        double total = scanOut[5];
        wb.setFeature(Feature.ENVELOPE_FILL_RATIO,
                total > 0 ? surviving / total : 0);
        wb.setFeature(Feature.REACHABLE_DISTANCE_MIN, scanOut[0]);
        wb.setFeature(Feature.REACHABLE_DISTANCE_MAX, scanOut[1]);

        double gfRange = Double.isNaN(waveOriginX) ? 0 : scanOut[3] - scanOut[2];
        wb.setFeature(Feature.REACHABLE_GF_RANGE, Math.max(0, gfRange));
    }
}

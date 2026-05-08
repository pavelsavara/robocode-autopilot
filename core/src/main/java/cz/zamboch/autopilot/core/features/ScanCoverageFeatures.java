package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.util.RingBuffer;
import cz.zamboch.autopilot.core.util.RoboMath;

/**
 * Scan-coverage features for in-game use. Tracks radar sweep geometry
 * and scan frequency. Ported from pipeline ScanCoverageOfflineFeatures.
 *
 * <p>Runs every tick (not gated on scan) because some outputs are meaningful
 * even when no scan happened on the current tick.</p>
 */
public final class ScanCoverageFeatures implements IInGameFeatures {

    private static final int RADAR_LOCK_WINDOW = 5;
    private static final long RADAR_LOCK_MAX_GAP = 2;

    private static final Feature[] OUTPUTS = {
            Feature.TICKS_BETWEEN_SCANS,
            Feature.SCAN_COVERAGE_20,
            Feature.SCAN_COVERAGE_50,
            Feature.SCAN_ARC_WIDTH,
            Feature.RADAR_LOCKED,
            Feature.RADAR_TURN_DIRECTION
    };

    private static final Feature[] DEPS = {};

    @Override
    public Feature[] getOutputFeatures() { return OUTPUTS; }

    @Override
    public Feature[] getDependencies() { return DEPS; }

    @Override
    public void process(Whiteboard wb) {
        long tick = wb.getTick();

        // Radar arc width and turn direction (every tick).
        double prevRadar = wb.getPrevOurRadarHeading();
        double currRadar = wb.getOurRadarHeading();
        if (!Double.isNaN(prevRadar)) {
            double diff = RoboMath.normalRelativeAngle(currRadar - prevRadar);
            wb.setFeature(Feature.SCAN_ARC_WIDTH, Math.abs(diff));
            int dir = diff > 1e-9 ? 1 : (diff < -1e-9 ? -1 : 0);
            wb.setFeature(Feature.RADAR_TURN_DIRECTION, dir);
        } else {
            wb.setFeature(Feature.SCAN_ARC_WIDTH, 0.0);
            wb.setFeature(Feature.RADAR_TURN_DIRECTION, 0);
        }
        wb.setPrevOurRadarHeading(currRadar);

        // Update scan-tick history.
        if (wb.isScanAvailableThisTick()) {
            wb.getScanTickHistory50().add(tick);
        }

        // Ticks between most recent two scans.
        RingBuffer<Long> hist = wb.getScanTickHistory50();
        if (hist.size() >= 2) {
            long mostRecent = hist.get(0);
            long prev = hist.get(1);
            wb.setFeature(Feature.TICKS_BETWEEN_SCANS, mostRecent - prev);
        }

        // Scan coverage over recent windows.
        wb.setFeature(Feature.SCAN_COVERAGE_20, coverage(hist, tick, 20));
        wb.setFeature(Feature.SCAN_COVERAGE_50, coverage(hist, tick, 50));

        // Radar locked: last RADAR_LOCK_WINDOW gaps all <= RADAR_LOCK_MAX_GAP.
        boolean locked = false;
        if (hist.size() >= RADAR_LOCK_WINDOW + 1) {
            locked = true;
            for (int i = 0; i < RADAR_LOCK_WINDOW; i++) {
                long gap = hist.get(i) - hist.get(i + 1);
                if (gap > RADAR_LOCK_MAX_GAP) {
                    locked = false;
                    break;
                }
            }
        }
        wb.setFeature(Feature.RADAR_LOCKED, locked ? 1.0 : 0.0);
    }

    /** Fraction of last {@code window} ticks that contain a scan. */
    private static double coverage(RingBuffer<Long> hist, long now, int window) {
        if (hist.isEmpty() || window <= 0) return 0.0;
        long lower = now - window + 1;
        int count = 0;
        for (int i = 0; i < hist.size(); i++) {
            long t = hist.get(i);
            if (t < lower) break;
            if (t <= now) count++;
        }
        return (double) count / window;
    }
}

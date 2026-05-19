package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Computes ticks since last scan from TICK and LAST_SCAN_TICK features.
 */
public final class TimingFeatures implements IInGameFeatures {
    private static final Feature[] DEPS = { Feature.TICK, Feature.LAST_SCAN_TICK };
    private static final Feature[] OUTPUTS = { Feature.TICKS_SINCE_SCAN };

    public Feature[] getDependencies() {
        return DEPS;
    }

    public Feature[] getOutputFeatures() {
        return OUTPUTS;
    }

    public FileType getFileType() {
        return FileType.TICKS;
    }

    public void process(Whiteboard wb) {
        double tick = wb.getFeature(Feature.TICK);
        double lastScanTick = wb.getFeature(Feature.LAST_SCAN_TICK);
        if (Double.isNaN(tick) || Double.isNaN(lastScanTick)) {
            return;
        }
        wb.setFeature(Feature.TICKS_SINCE_SCAN, tick - lastScanTick);
    }
}

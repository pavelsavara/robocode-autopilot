package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Computes the gap between the current and previous scan rows.
 */
public final class TimingFeatures implements IInGameFeatures {
     private static final Feature[] DEPS = { Feature.SCAN_TICK };
    private static final Feature[] OUTPUTS = { Feature.TICKS_SINCE_SCAN };

    public Feature[] getDependencies() {
        return DEPS;
    }

    public Feature[] getOutputFeatures() {
        return OUTPUTS;
    }

    public FileType getFileType() {
        return FileType.SCAN;
    }

    public void process(Whiteboard wb) {
        if (!wb.hasCurrentScan()) {
            return;
        }
        double scanTick = wb.getFeature(Feature.SCAN_TICK);
        double previousScanTick = wb.getPreviousScanFeature(Feature.SCAN_TICK);
        if (!Double.isNaN(scanTick) && !Double.isNaN(previousScanTick)) {
            wb.setCurrentScanFeature(Feature.TICKS_SINCE_SCAN, scanTick - previousScanTick);
        }
    }
}

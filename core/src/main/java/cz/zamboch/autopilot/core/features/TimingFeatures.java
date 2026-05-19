package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Timing features: tick number, gun heat, ticks since last scan.
 */
public final class TimingFeatures implements IInGameFeatures {
    private static final Feature[] DEPS = {};
    private static final Feature[] OUTPUTS = {
            Feature.TICK,
            Feature.GUN_HEAT,
            Feature.TICKS_SINCE_SCAN
    };

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
        wb.setFeature(Feature.TICK, wb.getTick());
        wb.setFeature(Feature.GUN_HEAT, wb.getGunHeat());

        long lastScanTick = wb.getLastScanTick();
        if (lastScanTick >= 0) {
            wb.setFeature(Feature.TICKS_SINCE_SCAN, wb.getTick() - lastScanTick);
        } else {
            wb.setFeature(Feature.TICKS_SINCE_SCAN, Double.NaN);
        }
    }
}

package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Computes timing features: gun heat, ticks since last scan.
 * No dependencies on other feature processors.
 */
public class TimingFeatures implements IInGameFeatures {

    private static final Feature[] OUTPUTS = {
            Feature.OUR_GUN_HEAT,
            Feature.TICKS_SINCE_SCAN
    };

    private static final Feature[] DEPS = {};

    public Feature[] getOutputFeatures() {
        return OUTPUTS;
    }

    public Feature[] getDependencies() {
        return DEPS;
    }

    public void process(Whiteboard wb) {
        // Gun heat is always available (own state)
        wb.setFeature(Feature.OUR_GUN_HEAT, wb.getOurGunHeat());

        // Ticks since last scan
        long lastScan = wb.getLastScanTick();
        if (lastScan >= 0) {
            wb.setFeature(Feature.TICKS_SINCE_SCAN, wb.getTick() - lastScan);
        } else {
            // Never scanned yet — use a large value
            wb.setFeature(Feature.TICKS_SINCE_SCAN, wb.getTick() + 1);
        }
    }
}

package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

/** Copies consumed damage-window accumulators into the current scan row. */
public final class AccumulatorFeatures implements IInGameFeatures {
    private static final Feature[] DEPS = {
            Feature.THEIR_FIRE_POWER
    };
        private static final Feature[] OUTPUTS = {};

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
        wb.copyDamageAccumulatorsToCurrentScanRow();
        wb.clearDamageAccumulatorFeatures();
    }
}
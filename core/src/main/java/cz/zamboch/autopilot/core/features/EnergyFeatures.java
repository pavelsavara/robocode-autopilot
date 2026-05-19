package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Energy features: our energy, opponent energy.
 */
public final class EnergyFeatures implements IInGameFeatures {
    private static final Feature[] DEPS = {};
    private static final Feature[] OUTPUTS = {
            Feature.OUR_ENERGY,
            Feature.OPPONENT_ENERGY
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
        wb.setFeature(Feature.OUR_ENERGY, wb.getOurEnergy());

        if (wb.hasScanData()) {
            wb.setFeature(Feature.OPPONENT_ENERGY, wb.getScanOppEnergy());
        } else if (wb.getLastScan() != null) {
            wb.setFeature(Feature.OPPONENT_ENERGY, wb.getLastScan().getEnergy());
        }
    }
}

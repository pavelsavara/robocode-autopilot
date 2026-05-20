package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Detects opponent fire via scan-to-scan energy drop.
 * If the opponent's energy decreased by 0.1–3.0 between consecutive scans,
 * records the drop as OPPONENT_FIRE_POWER (the valid bullet power range).
 * Uses PREV_SCAN_OPPONENT_ENERGY stored in the Whiteboard as inter-tick state.
 */
public final class FireFeatures implements IInGameFeatures {
    private static final Feature[] DEPS = {
            Feature.TICK, Feature.LAST_SCAN_TICK, Feature.OPPONENT_ENERGY
    };
    private static final Feature[] OUTPUTS = {
            Feature.OPPONENT_FIRE_POWER, Feature.PREV_SCAN_OPPONENT_ENERGY
    };

    public Feature[] getDependencies() {
        return DEPS;
    }

    public Feature[] getOutputFeatures() {
        return OUTPUTS;
    }

    public FileType getFileType() {
        return FileType.WAVES;
    }

    public void process(Whiteboard wb) {
        double tick = wb.getFeature(Feature.TICK);
        double lastScanTick = wb.getFeature(Feature.LAST_SCAN_TICK);

        // Only compute on ticks where a new scan occurred
        if (Double.isNaN(tick) || Double.isNaN(lastScanTick) || tick != lastScanTick) {
            return;
        }

        double currentEnergy = wb.getFeature(Feature.OPPONENT_ENERGY);
        double prevEnergy = wb.getFeature(Feature.PREV_SCAN_OPPONENT_ENERGY);

        if (!Double.isNaN(prevEnergy)) {
            double drop = prevEnergy - currentEnergy;
            if (drop >= 0.1 && drop <= 3.0) {
                wb.setFeature(Feature.OPPONENT_FIRE_POWER, drop);
            } else {
                wb.setFeature(Feature.OPPONENT_FIRE_POWER, Double.NaN);
            }
        }

        wb.setFeature(Feature.PREV_SCAN_OPPONENT_ENERGY, currentEnergy);
    }
}

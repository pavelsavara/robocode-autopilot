package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Computes the absolute bearing to the opponent from our heading + relative
 * bearing.
 */
public final class SpatialFeatures implements IInGameFeatures {
    private static final Feature[] DEPS = { Feature.OUR_HEADING, Feature.BEARING_RADIANS };
    private static final Feature[] OUTPUTS = { Feature.OPPONENT_BEARING_ABSOLUTE };

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
        double bearing = wb.getFeature(Feature.BEARING_RADIANS);
        double heading = wb.getFeature(Feature.OUR_HEADING);
        if (Double.isNaN(bearing) || Double.isNaN(heading)) {
            return;
        }
        wb.setFeature(Feature.OPPONENT_BEARING_ABSOLUTE, heading + bearing);
    }
}

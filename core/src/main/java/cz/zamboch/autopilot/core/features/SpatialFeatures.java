package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;
import robocode.ScannedRobotEvent;

/**
 * Spatial features derived from ScannedRobotEvent:
 * distance, bearing, absolute bearing to opponent.
 */
public final class SpatialFeatures implements IInGameFeatures {
    private static final Feature[] DEPS = {};
    private static final Feature[] OUTPUTS = {
            Feature.DISTANCE,
            Feature.BEARING_RADIANS,
            Feature.OUR_X,
            Feature.OUR_Y,
            Feature.OPPONENT_BEARING_ABSOLUTE
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
        wb.setFeature(Feature.OUR_X, wb.getOurX());
        wb.setFeature(Feature.OUR_Y, wb.getOurY());

        if (wb.hasScanData()) {
            // Pipeline path: scan data injected by Player
            wb.setFeature(Feature.DISTANCE, wb.getScanDistance());
            double bearingRad = Math.toRadians(wb.getScanBearingDegrees());
            wb.setFeature(Feature.BEARING_RADIANS, bearingRad);
            double absoluteBearing = wb.getOurHeading() + bearingRad;
            wb.setFeature(Feature.OPPONENT_BEARING_ABSOLUTE, absoluteBearing);
        } else {
            // Live robot path: ScannedRobotEvent
            ScannedRobotEvent scan = wb.getLastScan();
            if (scan == null) {
                return;
            }
            wb.setFeature(Feature.DISTANCE, scan.getDistance());
            wb.setFeature(Feature.BEARING_RADIANS, Math.toRadians(scan.getBearing()));
            double absoluteBearing = wb.getOurHeading() + Math.toRadians(scan.getBearing());
            wb.setFeature(Feature.OPPONENT_BEARING_ABSOLUTE, absoluteBearing);
        }
    }
}

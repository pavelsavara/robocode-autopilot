package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.Whiteboard;

/** Computes derived values for the current scan row. */
public final class ScanFeatures implements IInGameFeatures {
    private static final Feature[] DEPS = {
            Feature.TICK,
            Feature.SCAN_TICK,
            Feature.OUR_X,
            Feature.OUR_Y,
            Feature.OUR_HEADING,
            Feature.DISTANCE,
            Feature.BEARING_RADIANS,
            Feature.OPPONENT_HEADING,
            Feature.OPPONENT_VELOCITY,
            Feature.OPPONENT_ID
    };
    private static final Feature[] OUTPUTS = {
            Feature.OPPONENT_ID_HASH,
            Feature.OPPONENT_BEARING_ABSOLUTE,
            Feature.OPPONENT_X,
            Feature.OPPONENT_Y,
            Feature.OPPONENT_LATERAL_VELOCITY,
            Feature.OPPONENT_ADVANCING_VELOCITY,
            Feature.TICKS_SINCE_SCAN
    };

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
        computeIdentity(wb);
        computeTiming(wb);
        computeSpatial(wb);
        computeMovement(wb);
    }

    private void computeIdentity(Whiteboard wb) {
        String name = wb.getStringFeature(Feature.OPPONENT_ID);
        if (name == null) {
            return;
        }
        int sp = name.indexOf(' ');
        String botId = (sp < 0) ? name : name.substring(0, sp);
        wb.setCurrentScanFeature(Feature.OPPONENT_ID_HASH, RoboMath.fnv1a32(botId));
    }

    private void computeTiming(Whiteboard wb) {
        double scanTick = wb.getFeature(Feature.SCAN_TICK);
        double previousScanTick = wb.getPreviousScanFeature(Feature.SCAN_TICK);
        if (!Double.isNaN(scanTick) && !Double.isNaN(previousScanTick)) {
            wb.setCurrentScanFeature(Feature.TICKS_SINCE_SCAN, scanTick - previousScanTick);
        }
    }

    private void computeSpatial(Whiteboard wb) {
        double bearing = wb.getFeature(Feature.BEARING_RADIANS);
        double heading = wb.getFeature(Feature.OUR_HEADING);
        if (Double.isNaN(bearing) || Double.isNaN(heading)) {
            return;
        }
        double absBearing = heading + bearing;
        wb.setCurrentScanFeature(Feature.OPPONENT_BEARING_ABSOLUTE, absBearing);

        double ourX = wb.getFeature(Feature.OUR_X);
        double ourY = wb.getFeature(Feature.OUR_Y);
        double distance = wb.getFeature(Feature.DISTANCE);
        if (Double.isNaN(ourX) || Double.isNaN(ourY) || Double.isNaN(distance)) {
            return;
        }
        wb.setCurrentScanFeature(Feature.OPPONENT_X, ourX + distance * Math.sin(absBearing));
        wb.setCurrentScanFeature(Feature.OPPONENT_Y, ourY + distance * Math.cos(absBearing));
    }

    private void computeMovement(Whiteboard wb) {
        double oppVelocity = wb.getFeature(Feature.OPPONENT_VELOCITY);
        double oppHeading = wb.getFeature(Feature.OPPONENT_HEADING);
        double absoluteBearing = wb.getFeature(Feature.OPPONENT_BEARING_ABSOLUTE);
        if (Double.isNaN(oppVelocity) || Double.isNaN(oppHeading) || Double.isNaN(absoluteBearing)) {
            return;
        }
        double bearingFromOpponent = absoluteBearing + Math.PI;
        double relativeHeading = oppHeading - bearingFromOpponent;
        wb.setCurrentScanFeature(Feature.OPPONENT_LATERAL_VELOCITY, oppVelocity * Math.sin(relativeHeading));
        wb.setCurrentScanFeature(Feature.OPPONENT_ADVANCING_VELOCITY, oppVelocity * Math.cos(relativeHeading));
    }
}
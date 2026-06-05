package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

/** Computes opponent position and movement values for the current scan row. */
public final class SpatialFeatures implements IInGameFeatures {
    private static final Feature[] DEPS = {
            Feature.OUR_HEADING,
            Feature.BEARING_RADIANS,
            Feature.OUR_X,
            Feature.OUR_Y,
            Feature.DISTANCE,
            Feature.OPPONENT_HEADING,
            Feature.OPPONENT_VELOCITY
    };
    private static final Feature[] OUTPUTS = { Feature.OPPONENT_BEARING_ABSOLUTE,
            Feature.OPPONENT_X,
            Feature.OPPONENT_Y,
            Feature.OPPONENT_LATERAL_VELOCITY,
            Feature.OPPONENT_ADVANCING_VELOCITY
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

        double oppVelocity = wb.getFeature(Feature.OPPONENT_VELOCITY);
        double oppHeading = wb.getFeature(Feature.OPPONENT_HEADING);
        if (Double.isNaN(oppVelocity) || Double.isNaN(oppHeading)) {
            return;
        }
        double bearingFromOpponent = absBearing + Math.PI;
        double relativeHeading = oppHeading - bearingFromOpponent;
        wb.setCurrentScanFeature(Feature.OPPONENT_LATERAL_VELOCITY, oppVelocity * Math.sin(relativeHeading));
        wb.setCurrentScanFeature(Feature.OPPONENT_ADVANCING_VELOCITY, oppVelocity * Math.cos(relativeHeading));
    }
}

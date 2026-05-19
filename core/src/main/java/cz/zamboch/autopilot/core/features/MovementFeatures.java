package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;
import robocode.ScannedRobotEvent;

/**
 * Movement features: velocities and headings for both robots.
 * Lateral/advancing velocity decomposition relative to bearing line.
 */
public final class MovementFeatures implements IInGameFeatures {
    private static final Feature[] DEPS = { Feature.OPPONENT_BEARING_ABSOLUTE };
    private static final Feature[] OUTPUTS = {
            Feature.OUR_VELOCITY,
            Feature.OUR_HEADING,
            Feature.OPPONENT_VELOCITY,
            Feature.OPPONENT_HEADING,
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
        return FileType.TICKS;
    }

    public void process(Whiteboard wb) {
        wb.setFeature(Feature.OUR_VELOCITY, wb.getOurVelocity());
        wb.setFeature(Feature.OUR_HEADING, wb.getOurHeading());

        double oppVelocity;
        double oppHeading;

        if (wb.hasScanData()) {
            // Pipeline path
            oppVelocity = wb.getScanOppVelocity();
            oppHeading = wb.getScanOppHeading(); // already radians from Player
        } else {
            // Live robot path
            ScannedRobotEvent scan = wb.getLastScan();
            if (scan == null) {
                return;
            }
            oppVelocity = scan.getVelocity();
            oppHeading = Math.toRadians(scan.getHeading());
        }

        wb.setFeature(Feature.OPPONENT_VELOCITY, oppVelocity);
        wb.setFeature(Feature.OPPONENT_HEADING, oppHeading);

        // Decompose opponent velocity into lateral (perpendicular to bearing line)
        // and advancing (along bearing line toward us) components
        double absoluteBearing = wb.getFeature(Feature.OPPONENT_BEARING_ABSOLUTE);
        // Bearing from opponent to us is absoluteBearing + PI
        double bearingFromOpponent = absoluteBearing + Math.PI;
        double relativeHeading = oppHeading - bearingFromOpponent;

        wb.setFeature(Feature.OPPONENT_LATERAL_VELOCITY, oppVelocity * Math.sin(relativeHeading));
        wb.setFeature(Feature.OPPONENT_ADVANCING_VELOCITY, oppVelocity * Math.cos(relativeHeading));
    }
}

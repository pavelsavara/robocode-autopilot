package cz.zamboch.trivial;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.IGunStrategy;
import cz.zamboch.autopilot.core.util.RoboMath;

/**
 * Random GF gun — picks a random guess factor and converts to a
 * fire angle. Baseline for comparison.
 */
public final class RandomGfGun implements IGunStrategy {

    @Override
    public double getFireAngle(Whiteboard wb) {
        double bearing = wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS);
        if (!wb.hasFeature(Feature.MEA_FOR_OUR_BULLET)) {
            return bearing;
        }
        double mea = wb.getFeature(Feature.MEA_FOR_OUR_BULLET);
        int latDir = (int) wb.getFeature(Feature.OPPONENT_LATERAL_DIRECTION);
        if (latDir == 0) latDir = 1;

        double gf = Math.random() * 2.0 - 1.0; // random GF in [-1, 1]
        double offset = gf * mea * latDir;
        return RoboMath.normalAbsoluteAngle(bearing + offset);
    }

    @Override
    public double getConfidence(Whiteboard wb) {
        return 0.1;
    }

    @Override
    public String getName() {
        return "random-gf";
    }
}

package cz.zamboch.autopilot.core.gun;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.IGunStrategy;
import cz.zamboch.autopilot.core.util.RoboMath;

import java.util.Random;

/**
 * Random GuessFactor gun — fires at a random angle within the opponent's
 * Maximum Escape Angle (MEA). Against wave-surfing opponents, this achieves
 * a flat GF distribution that is harder to exploit than deterministic guns.
 *
 * <p>Expected hit rate: ~8-10% at typical combat distances, which is
 * competitive against wave surfers that dodge deterministic patterns.</p>
 */
public final class RandomGfGun implements IGunStrategy {

    private final Random rng = new Random();

    @Override
    public double getFireAngle(Whiteboard wb) {
        double bearing = wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS);
        if (!wb.hasFeature(Feature.MEA_FOR_OUR_BULLET)) {
            return bearing;
        }

        double mea = wb.getFeature(Feature.MEA_FOR_OUR_BULLET);
        // Random GF in [-0.9, 0.9] — slightly inside MEA to account for
        // the fact that extreme GFs are rarely reachable
        double gf = (rng.nextDouble() * 1.8 - 0.9);

        int latDir = wb.hasFeature(Feature.OPPONENT_LATERAL_DIRECTION)
                ? (int) wb.getFeature(Feature.OPPONENT_LATERAL_DIRECTION) : 1;
        if (latDir == 0) latDir = 1;

        double offset = gf * mea * latDir;
        return RoboMath.normalAbsoluteAngle(bearing + offset);
    }

    @Override
    public double getConfidence(Whiteboard wb) {
        return 0.5;
    }

    @Override
    public String getName() {
        return "random-gf";
    }
}

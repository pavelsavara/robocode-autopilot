package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.util.RoboMath;

/**
 * Computes spatial features: distance, bearing to opponent, opponent wall distance.
 * These depend only on raw whiteboard state (no other features).
 */
public class SpatialFeatures implements IInGameFeatures {

    private static final Feature[] OUTPUTS = {
            Feature.DISTANCE,
            Feature.BEARING_TO_OPPONENT_ABS,
            Feature.OPPONENT_DIST_TO_WALL_MIN
    };

    private static final Feature[] DEPS = {};

    public Feature[] getOutputFeatures() {
        return OUTPUTS;
    }

    public Feature[] getDependencies() {
        return DEPS;
    }

    public void process(Whiteboard wb) {
        if (!wb.isScanAvailableThisTick()) {
            return;
        }

        double dx = wb.getOpponentX() - wb.getOurX();
        double dy = wb.getOpponentY() - wb.getOurY();

        // Distance
        double distance = Math.hypot(dx, dy);
        wb.setFeature(Feature.DISTANCE, distance);

        // Absolute bearing to opponent (robocode convention: atan2(dx, dy))
        double absBearing = Math.atan2(dx, dy);
        wb.setFeature(Feature.BEARING_TO_OPPONENT_ABS, RoboMath.normalAbsoluteAngle(absBearing));

        // Opponent distance to nearest wall
        double oppX = wb.getOpponentX();
        double oppY = wb.getOpponentY();
        int bfW = wb.getBattlefieldWidth();
        int bfH = wb.getBattlefieldHeight();

        double distToWallMin = Math.min(
                Math.min(oppX, bfW - oppX),
                Math.min(oppY, bfH - oppY));
        wb.setFeature(Feature.OPPONENT_DIST_TO_WALL_MIN, distToWallMin);
    }
}

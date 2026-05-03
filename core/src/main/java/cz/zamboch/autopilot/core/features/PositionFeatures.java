package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.util.RoboMath;

/**
 * Emits absolute position and state features for both robots.
 * All values already exist on the Whiteboard — this class just publishes them
 * as Feature enum entries for CSV output and ML training.
 * No dependencies on other features.
 */
public class PositionFeatures implements IInGameFeatures {

    private static final Feature[] OUTPUTS = {
            Feature.OUR_X,
            Feature.OUR_Y,
            Feature.OUR_HEADING,
            Feature.OUR_VELOCITY,
            Feature.OPPONENT_X,
            Feature.OPPONENT_Y,
            Feature.OPPONENT_HEADING
    };

    private static final Feature[] DEPS = {};

    public Feature[] getOutputFeatures() { return OUTPUTS; }

    public Feature[] getDependencies() { return DEPS; }

    public void process(Whiteboard wb) {
        // Our state is always available (from StatusEvent each tick)
        wb.setFeature(Feature.OUR_X, wb.getOurX());
        wb.setFeature(Feature.OUR_Y, wb.getOurY());
        wb.setFeature(Feature.OUR_HEADING, wb.getOurHeading());
        wb.setFeature(Feature.OUR_VELOCITY, wb.getOurVelocity());

        // Opponent state only available on scan ticks
        if (wb.isScanAvailableThisTick()) {
            wb.setFeature(Feature.OPPONENT_X, wb.getOpponentX());
            wb.setFeature(Feature.OPPONENT_Y, wb.getOpponentY());
            wb.setFeature(Feature.OPPONENT_HEADING,
                    RoboMath.normalAbsoluteAngle(wb.getOpponentHeading()));
        }
    }
}

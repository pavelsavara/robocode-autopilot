package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.GuessFactor;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Computes derived fire-time features from raw OUR_FIRE_* inputs.
 * Runs when OUR_FIRE_POWER is set (i.e., we fired last tick).
 * Computes: OUR_FIRE_BULLET_SPEED, OUR_FIRE_MEA, OUR_FIRE_DIRECTION.
 */
public final class WaveFeatures implements IInGameFeatures {
    private static final Feature[] DEPS = {
            Feature.OUR_FIRE_POWER,
            Feature.OUR_FIRE_LATERAL_VELOCITY
    };
    private static final Feature[] OUTPUTS = {
            Feature.OUR_FIRE_BULLET_SPEED,
            Feature.OUR_FIRE_MEA,
            Feature.OUR_FIRE_DIRECTION
    };

    public Feature[] getDependencies() {
        return DEPS;
    }

    public Feature[] getOutputFeatures() {
        return OUTPUTS;
    }

    public FileType getFileType() {
        return FileType.OUR_WAVES;
    }

    public void process(Whiteboard wb) {
        double power = wb.getFeature(Feature.OUR_FIRE_POWER);
        if (Double.isNaN(power)) {
            return;
        }
        double bulletSpeed = GuessFactor.bulletSpeed(power);
        double mea = GuessFactor.maxEscapeAngle(bulletSpeed);
        double latVel = wb.getFeature(Feature.OUR_FIRE_LATERAL_VELOCITY);
        int direction = Double.isNaN(latVel) ? 1 : GuessFactor.direction(latVel);

        wb.setFeature(Feature.OUR_FIRE_BULLET_SPEED, bulletSpeed);
        wb.setFeature(Feature.OUR_FIRE_MEA, mea);
        wb.setFeature(Feature.OUR_FIRE_DIRECTION, direction);
    }
}

package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.GuessFactor;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.VcsStore;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Computes gun aiming features (GUN_AIM_POWER, GUN_AIM_ANGLE) every tick
 * using GuessFactor/VCS logic. Also computes derived fire-time features
 * (OUR_FIRE_BULLET_SPEED, OUR_FIRE_MEA, OUR_FIRE_DIRECTION) when a wave
 * is created (OUR_FIRE_POWER is set).
 */
public final class OurWaveFeatures implements IInGameFeatures {
    private static final Feature[] DEPS = {
            Feature.DISTANCE,
            Feature.GUN_HEAT,
            Feature.OPPONENT_BEARING_ABSOLUTE,
            Feature.OPPONENT_LATERAL_VELOCITY,
            Feature.OUR_FIRE_POWER,
            Feature.OUR_FIRE_LATERAL_VELOCITY
    };
    private static final Feature[] OUTPUTS = {
            Feature.GUN_AIM_POWER,
            Feature.GUN_AIM_ANGLE,
            Feature.GUN_AIM_GF,
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
        return FileType.TICKS;
    }

    public void process(Whiteboard wb) {
        // --- Gun aiming (every tick) ---
        computeGunAim(wb);

        // --- Fire-time derived features (only when we fired) ---
        computeFireDerived(wb);
    }

    private void computeGunAim(Whiteboard wb) {
        double distance = wb.getFeature(Feature.DISTANCE);
        if (Double.isNaN(distance)) {
            return;
        }

        double gunHeat = wb.getFeature(Feature.GUN_HEAT);
        double absoluteBearing = wb.getFeature(Feature.OPPONENT_BEARING_ABSOLUTE);
        double latVel = wb.getFeature(Feature.OPPONENT_LATERAL_VELOCITY);

        // Power inversely proportional to distance
        double power = Math.min(3.0, Math.max(1.0, (400.0 - distance) / 100.0 + 1.0));
        if (gunHeat > 0) {
            power = 0;
        }

        // Compute aiming offset from VCS
        double bulletSpeed = GuessFactor.bulletSpeed(power > 0 ? power : 2.0);
        double mea = GuessFactor.maxEscapeAngle(bulletSpeed);
        int direction = Double.isNaN(latVel) ? 1 : GuessFactor.direction(latVel);

        double offset = 0;
        double aimGf = 0;
        VcsStore vcs = wb.getVcsStore();
        if (vcs != null) {
            int distSeg = GuessFactor.distanceSegment(distance);
            int latVelSeg = GuessFactor.lateralVelocitySegment(
                    Double.isNaN(latVel) ? 0 : latVel);
            int bestBin = vcs.getBestBin(distSeg, latVelSeg);
            double bestGf = GuessFactor.binIndexToGf(bestBin, GuessFactor.NUM_BINS);
            offset = bestGf * mea * direction;
            aimGf = bestGf;
        }

        double aimAngle = absoluteBearing + offset;
        wb.setFeature(Feature.GUN_AIM_POWER, power);
        wb.setFeature(Feature.GUN_AIM_ANGLE, aimAngle);
        wb.setFeature(Feature.GUN_AIM_GF, aimGf);
    }

    private void computeFireDerived(Whiteboard wb) {
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

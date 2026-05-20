package cz.zamboch.autopilot.core.strategy;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.GuessFactor;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.VcsStore;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * GuessFactor gun strategy. Aims at the peak VCS bin for the current
 * distance/lateralVelocity segment. Falls back to head-on (GF=0) when
 * no data is available.
 * <p>
 * Stateless — reads VcsStore and features from Whiteboard.
 */
public final class GFGunStrategy implements IGunStrategy {

    private final Whiteboard wb;

    public GFGunStrategy(Whiteboard wb) {
        this.wb = wb;
    }

    @Override
    public void getFireCommand(FireCommand out) {
        double distance = wb.getFeature(Feature.DISTANCE);
        if (Double.isNaN(distance)) {
            out.set(Double.NaN, 0);
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
        VcsStore vcs = wb.getVcsStore();
        if (vcs != null) {
            int distSeg = GuessFactor.distanceSegment(distance);
            int latVelSeg = GuessFactor.lateralVelocitySegment(
                    Double.isNaN(latVel) ? 0 : latVel);
            int bestBin = vcs.getBestBin(distSeg, latVelSeg);
            double bestGf = GuessFactor.binIndexToGf(bestBin, GuessFactor.NUM_BINS);
            offset = bestGf * mea * direction;
        }

        double aimAngle = absoluteBearing + offset;
        out.set(aimAngle, power);
    }

    @Override
    public String getName() {
        return "GFGun";
    }
}

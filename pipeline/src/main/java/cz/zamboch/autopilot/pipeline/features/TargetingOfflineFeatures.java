package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.util.RoboMath;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import cz.zamboch.autopilot.pipeline.IOfflineFeatures;

/**
 * Tier 1 targeting + GuessFactor features (per-tick, TICKS file).
 *
 * Computes the two classic targeting baselines (linear and circular) plus
 * GF-space coordinates at standard bullet powers. Stateless.
 *
 * Uses {@link Whiteboard#getOurGunHeading()}, {@link Whiteboard#getOpponentX()}/Y,
 * and the spatial features. Falls back on a default 2.0 power for our_bullet_speed
 * when we have not yet fired, so targeting baselines are still meaningful for ML.
 */
public final class TargetingOfflineFeatures implements IOfflineFeatures {

    private static final double DEFAULT_FIRE_POWER = 2.0;
    private static final double MAX_VELOCITY = 8.0;
    private static final double ROBOT_HALF_SIZE = 18.0;

    private static final Feature[] OUTPUTS = {
            Feature.LINEAR_TARGET_ANGLE,
            Feature.LINEAR_TARGET_OFFSET,
            Feature.CIRCULAR_TARGET_ANGLE,
            Feature.CIRCULAR_TARGET_OFFSET,
            Feature.GF_BEARING_OFFSET,
            Feature.GF_CURRENT_AT_POWER_1,
            Feature.GF_CURRENT_AT_POWER_1_5,
            Feature.GF_CURRENT_AT_POWER_2,
            Feature.OPPONENT_GUESS_FACTOR
    };

    private static final Feature[] DEPS = {
            Feature.DISTANCE,
            Feature.BEARING_TO_OPPONENT_ABS,
            Feature.OPPONENT_VELOCITY,
            Feature.OPPONENT_LATERAL_DIRECTION,
            Feature.OUR_LATERAL_VELOCITY
    };

    public Feature[] getOutputFeatures() { return OUTPUTS; }
    public Feature[] getDependencies() { return DEPS; }

    public void process(Whiteboard wb) {
        if (!wb.isScanAvailableThisTick()
                || !wb.hasFeature(Feature.BEARING_TO_OPPONENT_ABS)
                || !wb.hasFeature(Feature.OPPONENT_VELOCITY)) {
            return;
        }

        double bearing = wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS);
        double oppVel = wb.getFeature(Feature.OPPONENT_VELOCITY);
        double oppHeading = wb.getOpponentHeading();

        double ourPower = wb.getLastOurFireTick() >= 0
                ? wb.getLastOurFirePower()
                : DEFAULT_FIRE_POWER;
        double ourSpeed = 20.0 - 3.0 * ourPower;

        // Linear target — exact non-iterative formula via law of sines.
        double sinArg = oppVel / ourSpeed * Math.sin(oppHeading - bearing);
        // Clamp asin domain (numerical safety; oppVel <= 8 < ourSpeed for power<=3 so should be safe)
        if (sinArg > 1.0) sinArg = 1.0;
        else if (sinArg < -1.0) sinArg = -1.0;
        double linOffset = Math.asin(sinArg);
        double linAngle = RoboMath.normalAbsoluteAngle(bearing + linOffset);
        wb.setFeature(Feature.LINEAR_TARGET_ANGLE, linAngle);
        wb.setFeature(Feature.LINEAR_TARGET_OFFSET, linOffset);

        // Circular target — iterative simulation, default heading-delta = 0 if unknown.
        double headingDelta = wb.hasFeature(Feature.OPPONENT_HEADING_DELTA)
                ? wb.getFeature(Feature.OPPONENT_HEADING_DELTA)
                : 0.0;
        double circAngle = circularTargetAngle(
                wb.getOurX(), wb.getOurY(),
                wb.getOpponentX(), wb.getOpponentY(),
                oppHeading, oppVel, headingDelta,
                ourSpeed,
                wb.getBattlefieldWidth(), wb.getBattlefieldHeight());
        wb.setFeature(Feature.CIRCULAR_TARGET_ANGLE, circAngle);
        wb.setFeature(Feature.CIRCULAR_TARGET_OFFSET,
                RoboMath.normalRelativeAngle(circAngle - bearing));

        // GF-bearing offset and current-position GF at standard powers.
        double gunHeading = wb.getOurGunHeading();
        double bearingOffset = RoboMath.normalRelativeAngle(bearing - gunHeading);
        wb.setFeature(Feature.GF_BEARING_OFFSET, bearingOffset);

        int latDir = wb.hasFeature(Feature.OPPONENT_LATERAL_DIRECTION)
                ? (int) wb.getFeature(Feature.OPPONENT_LATERAL_DIRECTION)
                : 0;
        wb.setFeature(Feature.GF_CURRENT_AT_POWER_1,
                gfAt(bearingOffset, latDir, 1.0));
        wb.setFeature(Feature.GF_CURRENT_AT_POWER_1_5,
                gfAt(bearingOffset, latDir, 1.5));
        wb.setFeature(Feature.GF_CURRENT_AT_POWER_2,
                gfAt(bearingOffset, latDir, 2.0));

        // Where WE sit in opponent's GF space — proxy via our lateral velocity / MAX_VELOCITY.
        double ourLatVel = wb.hasFeature(Feature.OUR_LATERAL_VELOCITY)
                ? wb.getFeature(Feature.OUR_LATERAL_VELOCITY)
                : 0.0;
        double oppGf = ourLatVel / MAX_VELOCITY;
        if (oppGf > 1.0) oppGf = 1.0;
        else if (oppGf < -1.0) oppGf = -1.0;
        wb.setFeature(Feature.OPPONENT_GUESS_FACTOR, oppGf);
    }

    private static double gfAt(double bearingOffset, int latDir, double power) {
        double bulletSpeed = 20.0 - 3.0 * power;
        double mea = Math.asin(8.0 / bulletSpeed);
        double effectiveDir = latDir == 0 ? 1 : latDir;
        double gf = (bearingOffset * effectiveDir) / mea;
        if (gf > 1.0) gf = 1.0;
        else if (gf < -1.0) gf = -1.0;
        return gf;
    }

    /**
     * Iterative circular targeting: project the opponent forward along their current
     * heading-delta arc until our bullet (travelling at ourSpeed from our position)
     * would intercept. Battlefield-clamps to ROBOT_HALF_SIZE.
     */
    private static double circularTargetAngle(
            double ourX, double ourY,
            double oppX, double oppY,
            double oppHeading, double oppVel, double headingDelta,
            double ourSpeed,
            int bfW, int bfH) {
        double px = oppX, py = oppY, ph = oppHeading;
        double t = 0;
        // Cap iterations to avoid pathological non-convergence (e.g. zero bullet speed).
        int maxIter = 256;
        while (++t * ourSpeed < Math.hypot(ourX - px, ourY - py) && --maxIter > 0) {
            px += Math.sin(ph) * oppVel;
            py += Math.cos(ph) * oppVel;
            ph += headingDelta;
            if (px < ROBOT_HALF_SIZE) px = ROBOT_HALF_SIZE;
            else if (px > bfW - ROBOT_HALF_SIZE) px = bfW - ROBOT_HALF_SIZE;
            if (py < ROBOT_HALF_SIZE) py = ROBOT_HALF_SIZE;
            else if (py > bfH - ROBOT_HALF_SIZE) py = bfH - ROBOT_HALF_SIZE;
        }
        return RoboMath.normalAbsoluteAngle(Math.atan2(px - ourX, py - ourY));
    }

    public FileType getFileType() { return FileType.TICKS; }

    public void writeColumnNames(CsvRowWriter row) {
        row.writeHeaders(
                "linear_target_angle", "linear_target_offset",
                "circular_target_angle", "circular_target_offset",
                "gf_bearing_offset",
                "gf_current_at_power_1", "gf_current_at_power_1_5", "gf_current_at_power_2",
                "opponent_guess_factor");
    }

    public void writeRowValues(CsvRowWriter row, Whiteboard wb) {
        row.writeDouble(wb, Feature.LINEAR_TARGET_ANGLE, "%.6f");
        row.writeDouble(wb, Feature.LINEAR_TARGET_OFFSET, "%.6f");
        row.writeDouble(wb, Feature.CIRCULAR_TARGET_ANGLE, "%.6f");
        row.writeDouble(wb, Feature.CIRCULAR_TARGET_OFFSET, "%.6f");
        row.writeDouble(wb, Feature.GF_BEARING_OFFSET, "%.6f");
        row.writeDouble(wb, Feature.GF_CURRENT_AT_POWER_1, "%.4f");
        row.writeDouble(wb, Feature.GF_CURRENT_AT_POWER_1_5, "%.4f");
        row.writeDouble(wb, Feature.GF_CURRENT_AT_POWER_2, "%.4f");
        row.writeDouble(wb, Feature.OPPONENT_GUESS_FACTOR, "%.4f");
    }
}

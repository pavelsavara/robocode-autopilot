package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.util.PrimitiveRollingBuffer;

/**
 * In-game computation of features that were previously pipeline-only.
 *
 * <p>These 23 features are required by the distilled GBM models but were only
 * computed by offline pipeline feature classes (MovementSegmentation, WaveOffline,
 * BattlefieldGeometry, TargetingGeometry, StateNormalization, OpponentPrediction,
 * MovementHistory). Without this class, the model receives NaN for ~29% of its
 * inputs, causing R²=−0.2 in-game.</p>
 *
 * <p>Stateless: all inter-tick state lives in {@link Whiteboard}.</p>
 */
public final class MlDerivedFeatures implements IInGameFeatures {

    private static final double DEFAULT_FIRE_POWER = 2.0;
    private static final double ROBOT_HALF_SIZE = 18.0;
    private static final double VELOCITY_CHANGE_THRESHOLD = 1.0;

    private static final Feature[] OUTPUTS = {
            // State normalisation
            Feature.ENERGY_RATIO,
            Feature.OUR_LATERAL_VELOCITY,
            Feature.OUR_DIST_TO_WALL_MIN,
            // Battlefield geometry
            Feature.OPPONENT_CENTER_DISTANCE,
            Feature.OPPONENT_CORNER_PROXIMITY,
            // Targeting geometry
            Feature.OPPONENT_ANGULAR_VELOCITY,
            Feature.OPPONENT_MAX_TURN_RATE,
            Feature.DISTANCE_NORM,
            // Movement segmentation
            Feature.OPPONENT_LATERAL_DIRECTION,
            Feature.OPPONENT_VELOCITY_DELTA,
            Feature.OPPONENT_IS_DECELERATING,
            Feature.OPPONENT_TIME_SINCE_DIRECTION_CHANGE,
            // Wave / fire timing
            Feature.OUR_BULLET_SPEED,
            Feature.OUR_BULLET_TRAVEL_TIME,
            Feature.MEA_FOR_OUR_BULLET,
            Feature.TICKS_SINCE_WE_FIRED,
            Feature.OUR_WAVE_DISTANCE,
            Feature.OUR_WAVE_REMAINING,
            // Opponent prediction
            Feature.OPPONENT_WALL_AHEAD_DISTANCE,
            Feature.OPPONENT_INFERRED_GUN_HEAT,
            // Movement history (rolling averages)
            Feature.OPPONENT_AVG_LATERAL_VELOCITY_10,
            Feature.OPPONENT_AVG_LATERAL_VELOCITY_30,
            Feature.OPPONENT_HEADING_DELTA_VARIABILITY_10,
            Feature.OPPONENT_VELOCITY_VARIABILITY_10,
            Feature.OPPONENT_TIME_SINCE_VELOCITY_CHANGE,
            Feature.OPPONENT_DISTANCE_SINCE_DIRECTION_CHANGE
    };

    private static final Feature[] DEPS = {
            Feature.BEARING_TO_OPPONENT_ABS,
            Feature.DISTANCE,
            Feature.OPPONENT_LATERAL_VELOCITY,
            Feature.OPPONENT_VELOCITY,
            Feature.OPPONENT_HEADING_DELTA
    };

    public Feature[] getOutputFeatures() { return OUTPUTS; }
    public Feature[] getDependencies() { return DEPS; }

    public void process(Whiteboard wb) {
        if (!wb.isScanAvailableThisTick()) {
            return;
        }

        processStateNormalisation(wb);
        processBattlefieldGeometry(wb);
        processTargetingGeometry(wb);
        processMovementSegmentation(wb);
        processWaveFeatures(wb);
        processOpponentPrediction(wb);
        processMovementHistory(wb);
    }

    private void processStateNormalisation(Whiteboard wb) {
        // Energy ratio
        double ourEnergy = wb.getOurEnergy();
        double oppEnergy = wb.getOpponentEnergy();
        double total = ourEnergy + oppEnergy;
        wb.setFeature(Feature.ENERGY_RATIO, total > 0 ? ourEnergy / total : 0.5);

        // Our lateral velocity (if not already set by another class)
        if (!wb.hasFeature(Feature.OUR_LATERAL_VELOCITY)
                && wb.hasFeature(Feature.BEARING_TO_OPPONENT_ABS)) {
            double absBearing = wb.getFeature(Feature.BEARING_TO_OPPONENT_ABS);
            double ourHeading = wb.getOurHeading();
            double ourVelocity = wb.getOurVelocity();
            double relHeading = normalRelativeAngle(ourHeading - absBearing);
            wb.setFeature(Feature.OUR_LATERAL_VELOCITY, ourVelocity * Math.sin(relHeading));
        }

        // Our distance to nearest wall
        if (!wb.hasFeature(Feature.OUR_DIST_TO_WALL_MIN)) {
            double ourX = wb.getOurX();
            double ourY = wb.getOurY();
            int bfW = wb.getBattlefieldWidth();
            int bfH = wb.getBattlefieldHeight();
            double distN = bfH - ourY - ROBOT_HALF_SIZE;
            double distS = ourY - ROBOT_HALF_SIZE;
            double distE = bfW - ourX - ROBOT_HALF_SIZE;
            double distW = ourX - ROBOT_HALF_SIZE;
            wb.setFeature(Feature.OUR_DIST_TO_WALL_MIN,
                    Math.min(Math.min(distN, distS), Math.min(distE, distW)));
        }
    }

    private void processBattlefieldGeometry(Whiteboard wb) {
        double oppX = wb.getOpponentX();
        double oppY = wb.getOpponentY();
        int bfW = wb.getBattlefieldWidth();
        int bfH = wb.getBattlefieldHeight();

        // Center distance
        wb.setFeature(Feature.OPPONENT_CENTER_DISTANCE,
                Math.hypot(oppX - bfW / 2.0, oppY - bfH / 2.0));

        // Corner proximity (min distance to any corner, with robot offset)
        double ci = ROBOT_HALF_SIZE;
        double d1 = Math.hypot(oppX - ci, oppY - ci);
        double d2 = Math.hypot(oppX - ci, oppY - (bfH - ci));
        double d3 = Math.hypot(oppX - (bfW - ci), oppY - ci);
        double d4 = Math.hypot(oppX - (bfW - ci), oppY - (bfH - ci));
        wb.setFeature(Feature.OPPONENT_CORNER_PROXIMITY,
                Math.min(Math.min(d1, d2), Math.min(d3, d4)));
    }

    private void processTargetingGeometry(Whiteboard wb) {
        if (!wb.hasFeature(Feature.DISTANCE) || !wb.hasFeature(Feature.OPPONENT_LATERAL_VELOCITY)
                || !wb.hasFeature(Feature.OPPONENT_VELOCITY)) {
            return;
        }
        double distance = wb.getFeature(Feature.DISTANCE);
        double latVel = wb.getFeature(Feature.OPPONENT_LATERAL_VELOCITY);
        double vel = wb.getFeature(Feature.OPPONENT_VELOCITY);

        // Angular velocity
        wb.setFeature(Feature.OPPONENT_ANGULAR_VELOCITY,
                distance > 0 ? latVel / distance : 0);

        // Max turn rate (robocode formula)
        wb.setFeature(Feature.OPPONENT_MAX_TURN_RATE,
                Math.toRadians(10.0 - 0.75 * Math.abs(vel)));

        // Normalised distance
        int bfW = wb.getBattlefieldWidth();
        int bfH = wb.getBattlefieldHeight();
        double diagonal = Math.hypot(bfW, bfH);
        wb.setFeature(Feature.DISTANCE_NORM, diagonal > 0 ? distance / diagonal : 0);
    }

    private void processMovementSegmentation(Whiteboard wb) {
        if (!wb.hasFeature(Feature.OPPONENT_LATERAL_VELOCITY)
                || !wb.hasFeature(Feature.OPPONENT_VELOCITY)) {
            return;
        }
        double latVel = wb.getFeature(Feature.OPPONENT_LATERAL_VELOCITY);
        double vel = wb.getFeature(Feature.OPPONENT_VELOCITY);

        // Lateral direction
        int lateralDirection;
        if (latVel > 0) {
            lateralDirection = 1;
        } else if (latVel < 0) {
            lateralDirection = -1;
        } else {
            lateralDirection = 0;
        }
        wb.setFeature(Feature.OPPONENT_LATERAL_DIRECTION, lateralDirection);

        // Velocity delta (acceleration)
        double prevVel = wb.getPrevOpponentVelocity();
        long prevScanTick = wb.getPrevScanTick();
        if (!Double.isNaN(prevVel) && prevScanTick >= 0) {
            long deltaTicks = wb.getTick() - prevScanTick;
            if (deltaTicks > 0) {
                wb.setFeature(Feature.OPPONENT_VELOCITY_DELTA,
                        (vel - prevVel) / deltaTicks);
            }
        }

        // Is decelerating
        boolean isDecelerating = !Double.isNaN(prevVel) && Math.abs(vel) < Math.abs(prevVel);
        wb.setFeature(Feature.OPPONENT_IS_DECELERATING, isDecelerating ? 1.0 : 0.0);

        // Time since direction change
        int prevDir = wb.getPrevLateralDirection();
        long counter = wb.getTicksSinceDirectionChange();
        if (lateralDirection != 0 && prevDir != 0 && lateralDirection != prevDir) {
            counter = 0;
        } else {
            counter++;
        }
        wb.setFeature(Feature.OPPONENT_TIME_SINCE_DIRECTION_CHANGE, counter);
        wb.setPrevLateralDirection(lateralDirection);
        wb.setTicksSinceDirectionChange(counter);
    }

    private void processWaveFeatures(Whiteboard wb) {
        if (!wb.hasFeature(Feature.DISTANCE)) {
            return;
        }
        double distance = wb.getFeature(Feature.DISTANCE);
        long tick = wb.getTick();

        // Our bullet properties (use last fired power if known; else default)
        double ourPower = wb.getLastOurFireTick() >= 0
                ? wb.getLastOurFirePower()
                : DEFAULT_FIRE_POWER;
        double ourSpeed = 20.0 - 3.0 * ourPower;
        wb.setFeature(Feature.OUR_BULLET_SPEED, ourSpeed);
        wb.setFeature(Feature.OUR_BULLET_TRAVEL_TIME, distance / ourSpeed);
        wb.setFeature(Feature.MEA_FOR_OUR_BULLET, Math.asin(8.0 / ourSpeed));

        // Our wave timing (only when we have fired)
        if (wb.getLastOurFireTick() >= 0) {
            long ticksSinceUs = tick - wb.getLastOurFireTick();
            wb.setFeature(Feature.TICKS_SINCE_WE_FIRED, ticksSinceUs);
            double ourWaveDist = ourSpeed * ticksSinceUs;
            wb.setFeature(Feature.OUR_WAVE_DISTANCE, ourWaveDist);
            wb.setFeature(Feature.OUR_WAVE_REMAINING, distance - ourWaveDist);
        }
    }

    private void processOpponentPrediction(Whiteboard wb) {
        // Wall ahead distance: ray-cast from opponent along heading to wall
        double oppX = wb.getOpponentX();
        double oppY = wb.getOpponentY();
        double oppHeading = wb.getOpponentHeading();
        int bfW = wb.getBattlefieldWidth();
        int bfH = wb.getBattlefieldHeight();

        double dx = Math.sin(oppHeading);
        double dy = Math.cos(oppHeading);

        double distToWall = Double.MAX_VALUE;
        if (dx > 0) {
            distToWall = Math.min(distToWall, (bfW - ROBOT_HALF_SIZE - oppX) / dx);
        } else if (dx < 0) {
            distToWall = Math.min(distToWall, (ROBOT_HALF_SIZE - oppX) / dx);
        }
        if (dy > 0) {
            distToWall = Math.min(distToWall, (bfH - ROBOT_HALF_SIZE - oppY) / dy);
        } else if (dy < 0) {
            distToWall = Math.min(distToWall, (ROBOT_HALF_SIZE - oppY) / dy);
        }
        wb.setFeature(Feature.OPPONENT_WALL_AHEAD_DISTANCE,
                distToWall == Double.MAX_VALUE ? 0 : distToWall);

        // Inferred gun heat — reads fire state from Whiteboard (set by EnergyFeatures)
        double coolingRate = wb.getGunCoolingRate();
        long lastFireTick = wb.getLastOpponentFireTick();
        if (lastFireTick >= 0) {
            long elapsed = wb.getTick() - lastFireTick;
            double heatFromFire = 1.0 + wb.getLastOpponentFirePower() / 5.0;
            wb.setFeature(Feature.OPPONENT_INFERRED_GUN_HEAT,
                    Math.max(0, heatFromFire - elapsed * coolingRate));
        } else {
            // No fire detected yet — initial gun heat cools from 3.0 (robocode default)
            wb.setFeature(Feature.OPPONENT_INFERRED_GUN_HEAT,
                    Math.max(0, 3.0 - wb.getTick() * coolingRate));
        }
    }

    private void processMovementHistory(Whiteboard wb) {
        if (!wb.hasFeature(Feature.OPPONENT_LATERAL_VELOCITY)
                || !wb.hasFeature(Feature.OPPONENT_VELOCITY)) {
            return;
        }
        double latVel = wb.getFeature(Feature.OPPONENT_LATERAL_VELOCITY);
        double vel = wb.getFeature(Feature.OPPONENT_VELOCITY);

        // Push into rolling buffers (O(1) add with running sum/sumSq updates)
        wb.getLatVelHistory30().add(latVel);
        wb.getVelHistory30().add(vel);
        if (wb.hasFeature(Feature.OPPONENT_HEADING_DELTA)) {
            wb.getHeadingDeltaHistory30().add(wb.getFeature(Feature.OPPONENT_HEADING_DELTA));
        }

        // O(1) rolling means and std-devs via PrimitiveRollingBuffer
        PrimitiveRollingBuffer latBuf = wb.getLatVelHistory30();
        wb.setFeature(Feature.OPPONENT_AVG_LATERAL_VELOCITY_10, latBuf.mean(10));
        wb.setFeature(Feature.OPPONENT_AVG_LATERAL_VELOCITY_30, latBuf.mean(30));
        wb.setFeature(Feature.OPPONENT_VELOCITY_VARIABILITY_10, wb.getVelHistory30().std(10));
        wb.setFeature(Feature.OPPONENT_HEADING_DELTA_VARIABILITY_10,
                wb.getHeadingDeltaHistory30().std(10));

        // Time since velocity change
        double prevSig = wb.getLastSignificantOpponentVelocity();
        long lastChangeTick = wb.getLastVelocityChangeTick();
        long tick = wb.getTick();
        if (Double.isNaN(prevSig)) {
            wb.setLastVelocityChange(tick, vel);
            wb.setFeature(Feature.OPPONENT_TIME_SINCE_VELOCITY_CHANGE, 0);
        } else if (Math.abs(vel - prevSig) >= VELOCITY_CHANGE_THRESHOLD) {
            wb.setLastVelocityChange(tick, vel);
            wb.setFeature(Feature.OPPONENT_TIME_SINCE_VELOCITY_CHANGE, 0);
        } else {
            wb.setFeature(Feature.OPPONENT_TIME_SINCE_VELOCITY_CHANGE,
                    tick - lastChangeTick);
        }

        // Distance since direction change
        long ticksSinceDir = wb.hasFeature(Feature.OPPONENT_TIME_SINCE_DIRECTION_CHANGE)
                ? (long) wb.getFeature(Feature.OPPONENT_TIME_SINCE_DIRECTION_CHANGE)
                : -1;
        int latDir = (int) wb.getFeature(Feature.OPPONENT_LATERAL_DIRECTION);
        double accum;
        if (ticksSinceDir == 0 && latDir != 0) {
            accum = Math.abs(vel);
        } else {
            accum = wb.getDistanceSinceDirChange() + Math.abs(vel);
        }
        wb.setDistanceSinceDirChange(accum);
        wb.setFeature(Feature.OPPONENT_DISTANCE_SINCE_DIRECTION_CHANGE, accum);
    }

    private static double normalRelativeAngle(double angle) {
        while (angle > Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }
}

package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.GuessFactor;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.ModelSelector;
import cz.zamboch.autopilot.core.OurWaveColumn;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.VcsStore;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Computes gun aiming features and manages the lifecycle of outgoing bullet
 * waves using the Whiteboard's pre-allocated our-wave ring buffer.
 * <p>
 * On each process() call:
 * <ol>
 * <li>Compute GUN_AIM_* every tick.</li>
 * <li>Compute fire-time derived features when OUR_FIRE_POWER is staged.</li>
 * <li>If OUR_FIRE_POWER is set (we fired last tick), allocate a ring slot,
 * copy fire features from staging, mark ACTIVE, then clear staging.
 * Also creates K virtual bullet slots with evenly-spaced AIM_GFs.</li>
 * <li>Resolve any active wave slots that have reached the opponent's current
 * position → update VcsStore (real only) and set OUR_BREAK_* staging
 * features.</li>
 * </ol>
 */
public final class OurWaveTracker implements IInGameFeatures {

    /** Number of virtual bullets created per real fire event. */
    public static final int VIRTUAL_BULLET_COUNT = 10;

    /** Half-width of robot bounding box for geometric hit detection (px). */
    static final double BOT_HALF_WIDTH = 18.0;

    private static final Feature[] DEPS = {
            Feature.DISTANCE,
            Feature.GUN_HEAT,
            Feature.OUR_X,
            Feature.OUR_Y,
            Feature.OUR_HEADING,
            Feature.OUR_VELOCITY,
            Feature.OPPONENT_BEARING_ABSOLUTE,
            Feature.OPPONENT_LATERAL_VELOCITY,
            Feature.OUR_FIRE_POWER,
            Feature.OUR_FIRE_X,
            Feature.OUR_FIRE_Y,
            Feature.OUR_FIRE_TICK,
            Feature.OUR_FIRE_BEARING_ABSOLUTE,
            Feature.OUR_FIRE_DISTANCE,
            Feature.OUR_FIRE_LATERAL_VELOCITY,
            Feature.OUR_FIRE_BULLET_ID,
            Feature.OUR_FIRE_AIM_GF,
            Feature.OUR_FIRE_IS_REAL,
            Feature.OUR_AIM_X,
            Feature.OUR_AIM_Y,
            Feature.OUR_AIM_OPPONENT_X,
            Feature.OUR_AIM_OPPONENT_Y,
            Feature.OUR_AIM_DISTANCE,
            Feature.OUR_AIM_LATERAL_VELOCITY,
            Feature.OUR_AIM_BEARING_ABSOLUTE,
            Feature.OUR_AIM_LAG1_GF,
            Feature.OPPONENT_X,
            Feature.OPPONENT_Y,
            Feature.TICK
    };
    private static final Feature[] OUTPUTS = {
            Feature.GUN_AIM_POWER,
            Feature.GUN_AIM_ANGLE,
            Feature.GUN_AIM_GF,
            Feature.OUR_FIRE_BULLET_SPEED,
            Feature.OUR_FIRE_MEA,
            Feature.OUR_FIRE_DIRECTION,
            Feature.OUR_BREAK_TICK,
            Feature.OUR_BREAK_GF,
            Feature.OUR_BREAK_BEARING_OFFSET,
            Feature.OUR_BREAK_OPPONENT_X,
            Feature.OUR_BREAK_OPPONENT_Y,
            Feature.OUR_BREAK_HIT
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
        computeGunAim(wb);
        computeFireDerived(wb);
        createWaveIfFired(wb);
        resolveWaves(wb);
    }

    private void computeGunAim(Whiteboard wb) {
        double distance = wb.getFeature(Feature.DISTANCE);
        if (Double.isNaN(distance)) {
            return;
        }

        double gunHeat = wb.getFeature(Feature.GUN_HEAT);
        double absoluteBearing = wb.getFeature(Feature.OPPONENT_BEARING_ABSOLUTE);
        double latVel = wb.getFeature(Feature.OPPONENT_LATERAL_VELOCITY);

        double power = Math.min(3.0, Math.max(1.0, (400.0 - distance) / 100.0 + 1.0));
        if (gunHeat > 0) {
            power = 0;
        }

        double bulletSpeed = GuessFactor.bulletSpeed(power > 0 ? power : 2.0);
        double mea = GuessFactor.preciseMaxEscapeAngle(bulletSpeed, distance);
        int direction = Double.isNaN(latVel) ? 1 : GuessFactor.direction(latVel);

        double offset = 0;
        double aimGf = 0;

        // Lag-1 dodge context: developing GF of the most-recent in-flight real
        // wave, evaluated against the opponent position at aim time (the tick
        // before this potential fire, T-1) — the freshest position the gun could
        // react to. The VcsStore bins this raw GF into a lag-1 slice.
        double tick = wb.getFeature(Feature.TICK);
        double aimOppX = wb.getScanFeatureAtOrBeforeTick(Feature.OPPONENT_X, tick - 1.0);
        double aimOppY = wb.getScanFeatureAtOrBeforeTick(Feature.OPPONENT_Y, tick - 1.0);
        double lag1Gf = computeLag1Gf(wb, aimOppX, aimOppY);

        ModelSelector selector = wb.getModelSelector();
        if (selector != null) {
            aimGf = selector.predictForAim(distance, Double.isNaN(latVel) ? 0 : latVel, lag1Gf, mea, absoluteBearing);
            offset = aimGf * mea * direction;
        } else {
            VcsStore vcs = wb.getVcsStore();
            if (vcs != null) {
                int distSeg = GuessFactor.distanceSegment(distance);
                int latVelSeg = GuessFactor.lateralVelocitySegment(Double.isNaN(latVel) ? 0 : latVel);
                int window = GuessFactor.gfBoxWindowBins(distance, mea, GuessFactor.NUM_BINS, absoluteBearing);
                int bestBin = vcs.getBestBinBoxed(distSeg, latVelSeg, lag1Gf, window);
                double bestGf = GuessFactor.binIndexToGf(bestBin, GuessFactor.NUM_BINS);
                offset = bestGf * mea * direction;
                aimGf = bestGf;
            }
        }

        double aimAngle = preAimBaseline(wb, absoluteBearing) + offset;
        wb.setFeature(Feature.GUN_AIM_POWER, power);
        wb.setFeature(Feature.GUN_AIM_ANGLE, aimAngle);
        wb.setFeature(Feature.GUN_AIM_GF, aimGf);
    }

    /**
     * GF=0 baseline absolute bearing for gun aiming, pre-aimed for the fire tick.
     * The gun is commanded at tick T-1 but the bullet leaves from our position at
     * the fire tick T. Predict our T position from current (T-1) kinematics — this
     * is deterministic because we own our movement — and take the bearing from
     * there to the last-known opponent position. The GF offset is applied on top.
     * <p>
     * No opponent motion prediction is needed: the GF itself already encodes the
     * opponent's movement during bullet flight, and the training side anchors GF
     * to the same aim-time opponent reference (see {@link #gfBaseline}). When our
     * kinematics or the opponent position are unknown (early ticks / radar gaps)
     * this falls back to {@code currentBearing}, so behaviour is unchanged without
     * data, and a stationary robot (velocity 0) is a no-op.
     */
    private static double preAimBaseline(Whiteboard wb, double currentBearing) {
        double ourX = wb.getFeature(Feature.OUR_X);
        double ourY = wb.getFeature(Feature.OUR_Y);
        double ourHeading = wb.getFeature(Feature.OUR_HEADING);
        double ourVel = wb.getFeature(Feature.OUR_VELOCITY);
        double oppX = wb.getFeature(Feature.OPPONENT_X);
        double oppY = wb.getFeature(Feature.OPPONENT_Y);
        if (Double.isNaN(ourX) || Double.isNaN(ourY) || Double.isNaN(ourHeading)
                || Double.isNaN(ourVel) || Double.isNaN(oppX) || Double.isNaN(oppY)) {
            return currentBearing;
        }
        double predX = ourX + ourVel * Math.sin(ourHeading);
        double predY = ourY + ourVel * Math.cos(ourHeading);
        return Math.atan2(oppX - predX, oppY - predY);
    }

    /**
     * Lag-1 dodge-context developing guess factor: the GF of the most-recent
     * still-active real wave (the one with the highest fire tick) evaluated
     * against the opponent position at {@code (oppX, oppY)}. Returns NaN when no
     * active real wave exists or the position is unknown (the VcsStore bins NaN
     * into the center slice). Public+static so the robot side can reproduce the
     * exact same value when staging OUR_AIM_LAG1_GF.
     */
    public static double computeLag1Gf(Whiteboard wb, double oppX, double oppY) {
        if (Double.isNaN(oppX) || Double.isNaN(oppY)) {
            return Double.NaN;
        }
        int bestSlot = -1;
        long bestTick = Long.MIN_VALUE;
        for (int slot = 0; slot < Whiteboard.OUR_WAVE_CAPACITY; slot++) {
            if (wb.getOurWaveState(slot) != Whiteboard.WAVE_ACTIVE) {
                continue;
            }
            if (wb.getOurWave(slot, OurWaveColumn.IS_REAL) != 1.0) {
                continue;
            }
            long ft = (long) wb.getOurWave(slot, OurWaveColumn.FIRE_TICK);
            if (ft > bestTick) {
                bestTick = ft;
                bestSlot = slot;
            }
        }
        if (bestSlot < 0) {
            return Double.NaN;
        }
        double fireX = wb.getOurWave(bestSlot, OurWaveColumn.FIRE_X);
        double fireY = wb.getOurWave(bestSlot, OurWaveColumn.FIRE_Y);
        double fireBearing = gfBaseline(wb, bestSlot, fireX, fireY);
        double mea = wb.getOurWave(bestSlot, OurWaveColumn.FIRE_MEA);
        int direction = aimDirection(wb, bestSlot);
        return GuessFactor.developingGuessFactor(
                fireX, fireY, fireBearing, mea, direction, oppX, oppY);
    }

    /**
     * GF sign direction for a wave slot from the aim-time lateral velocity (the
     * value the gun keyed its GF sign on), so the realized GF sign matches the
     * intended aim GF even when the opponent's lateral motion reverses between the
     * aim tick (T-1) and the fire tick (T). Falls back to the recorded
     * FIRE_DIRECTION when the aim-time lateral velocity is unavailable.
     */
    static int aimDirection(Whiteboard wb, int slot) {
        double aimLatVel = wb.getOurWave(slot, OurWaveColumn.AIM_LATERAL_VELOCITY);
        if (Double.isNaN(aimLatVel)) {
            return (int) wb.getOurWave(slot, OurWaveColumn.FIRE_DIRECTION);
        }
        return GuessFactor.direction(aimLatVel);
    }

    /**
     * GF=0 baseline bearing for a wave slot: from the fire origin (our position at
     * the fire tick) to the aim-time opponent reference position (AIM_OPPONENT_X/Y,
     * the last scan the gun could react to at T-1). This matches the pre-aim
     * baseline {@link #preAimBaseline} used to point the gun, so the realized GF a
     * wave resolves to is consistent with the GF the gun intended. Falls back to
     * the recorded FIRE_BEARING_ABSOLUTE when the aim-time opponent position is
     * unknown (e.g. synthetic waves staged directly in unit tests).
     */
    static double gfBaseline(Whiteboard wb, int slot, double fireX, double fireY) {
        double aimOppX = wb.getOurWave(slot, OurWaveColumn.AIM_OPPONENT_X);
        double aimOppY = wb.getOurWave(slot, OurWaveColumn.AIM_OPPONENT_Y);
        if (Double.isNaN(aimOppX) || Double.isNaN(aimOppY)) {
            return wb.getOurWave(slot, OurWaveColumn.FIRE_BEARING_ABSOLUTE);
        }
        return Math.atan2(aimOppX - fireX, aimOppY - fireY);
    }

    private void computeFireDerived(Whiteboard wb) {
        double power = wb.getFeature(Feature.OUR_FIRE_POWER);
        if (Double.isNaN(power)) {
            return;
        }
        double bulletSpeed = GuessFactor.bulletSpeed(power);
        double mea = GuessFactor.preciseMaxEscapeAngle(bulletSpeed, wb.getFeature(Feature.OUR_FIRE_DISTANCE));
        double latVel = wb.getFeature(Feature.OUR_FIRE_LATERAL_VELOCITY);
        int direction = Double.isNaN(latVel) ? 1 : GuessFactor.direction(latVel);

        wb.setFeature(Feature.OUR_FIRE_BULLET_SPEED, bulletSpeed);
        wb.setFeature(Feature.OUR_FIRE_MEA, mea);
        wb.setFeature(Feature.OUR_FIRE_DIRECTION, direction);
    }

    private void createWaveIfFired(Whiteboard wb) {
        double power = wb.getFeature(Feature.OUR_FIRE_POWER);
        if (Double.isNaN(power)) {
            return;
        }

        double fireX = wb.getFeature(Feature.OUR_FIRE_X);
        double fireY = wb.getFeature(Feature.OUR_FIRE_Y);
        double fireTick = wb.getFeature(Feature.OUR_FIRE_TICK);
        double bearing = wb.getFeature(Feature.OUR_FIRE_BEARING_ABSOLUTE);
        double bulletSpeed = wb.getFeature(Feature.OUR_FIRE_BULLET_SPEED);
        double direction = wb.getFeature(Feature.OUR_FIRE_DIRECTION);
        double distance = wb.getFeature(Feature.OUR_FIRE_DISTANCE);
        double latVel = wb.getFeature(Feature.OUR_FIRE_LATERAL_VELOCITY);
        double advVel = wb.getFeature(Feature.OUR_FIRE_ADVANCING_VELOCITY);
        double mea = wb.getFeature(Feature.OUR_FIRE_MEA);
        double bulletId = wb.getFeature(Feature.OUR_FIRE_BULLET_ID);
        double oppX = wb.getFeature(Feature.OUR_FIRE_OPPONENT_X);
        double oppY = wb.getFeature(Feature.OUR_FIRE_OPPONENT_Y);
        double aimGf = wb.getFeature(Feature.OUR_FIRE_AIM_GF);
        double aimX = wb.getFeature(Feature.OUR_AIM_X);
        double aimY = wb.getFeature(Feature.OUR_AIM_Y);
        double aimOppX = wb.getFeature(Feature.OUR_AIM_OPPONENT_X);
        double aimOppY = wb.getFeature(Feature.OUR_AIM_OPPONENT_Y);
        double aimDist = wb.getFeature(Feature.OUR_AIM_DISTANCE);
        double aimLatVel = wb.getFeature(Feature.OUR_AIM_LATERAL_VELOCITY);
        double aimBearing = wb.getFeature(Feature.OUR_AIM_BEARING_ABSOLUTE);
        double aimLag1Gf = wb.getFeature(Feature.OUR_AIM_LAG1_GF);

        if (Double.isNaN(fireX) || Double.isNaN(bearing) || Double.isNaN(bulletSpeed)) {
            return;
        }

        // Allocate real wave slot
        int slot = wb.allocateOurWave();
        fillFireColumns(wb, slot, power, fireX, fireY, fireTick, bearing,
                bulletSpeed, direction, distance, latVel, advVel, mea,
                bulletId, oppX, oppY);
        fillAimColumns(wb, slot, aimX, aimY, aimOppX, aimOppY, aimDist, aimLatVel, aimBearing, aimLag1Gf);
        wb.setOurWave(slot, OurWaveColumn.AIM_GF, Double.isNaN(aimGf) ? 0.0 : aimGf);
        wb.setOurWave(slot, OurWaveColumn.IS_REAL, 1.0);
        wb.setOurWave(slot, OurWaveColumn.WAVE_ID, waveId(fireTick, 0));
        wb.setOurWaveState(slot, Whiteboard.WAVE_ACTIVE);

        // Create virtual bullet slots
        for (int i = 0; i < VIRTUAL_BULLET_COUNT; i++) {
            double virtualGf = -1.0 + 2.0 * i / (VIRTUAL_BULLET_COUNT - 1);
            int vSlot = wb.allocateOurWave();
            fillFireColumns(wb, vSlot, power, fireX, fireY, fireTick, bearing,
                    bulletSpeed, direction, distance, latVel, advVel, mea,
                    0, oppX, oppY);
            fillAimColumns(wb, vSlot, aimX, aimY, aimOppX, aimOppY, aimDist, aimLatVel, aimBearing, aimLag1Gf);
            wb.setOurWave(vSlot, OurWaveColumn.AIM_GF, virtualGf);
            wb.setOurWave(vSlot, OurWaveColumn.IS_REAL, 0.0);
            wb.setOurWave(vSlot, OurWaveColumn.WAVE_ID, waveId(fireTick, i + 1));
            wb.setOurWaveState(vSlot, Whiteboard.WAVE_ACTIVE);
        }

        // Clear staging so we don't re-create next tick
        wb.setFeature(Feature.OUR_FIRE_POWER, Double.NaN);
        wb.setFeature(Feature.OUR_FIRE_X, Double.NaN);
        wb.setFeature(Feature.OUR_FIRE_Y, Double.NaN);
        wb.setFeature(Feature.OUR_FIRE_TICK, Double.NaN);
        wb.setFeature(Feature.OUR_FIRE_BEARING_ABSOLUTE, Double.NaN);
        wb.setFeature(Feature.OUR_FIRE_BULLET_SPEED, Double.NaN);
        wb.setFeature(Feature.OUR_FIRE_DIRECTION, Double.NaN);
        wb.setFeature(Feature.OUR_FIRE_DISTANCE, Double.NaN);
        wb.setFeature(Feature.OUR_FIRE_LATERAL_VELOCITY, Double.NaN);
        wb.setFeature(Feature.OUR_FIRE_BULLET_ID, Double.NaN);
        wb.setFeature(Feature.OUR_FIRE_MEA, Double.NaN);
        wb.setFeature(Feature.OUR_FIRE_ADVANCING_VELOCITY, Double.NaN);
        wb.setFeature(Feature.OUR_FIRE_OPPONENT_X, Double.NaN);
        wb.setFeature(Feature.OUR_FIRE_OPPONENT_Y, Double.NaN);
        wb.setFeature(Feature.OUR_FIRE_AIM_GF, Double.NaN);
        wb.setFeature(Feature.OUR_FIRE_IS_REAL, Double.NaN);
        wb.setFeature(Feature.OUR_AIM_X, Double.NaN);
        wb.setFeature(Feature.OUR_AIM_Y, Double.NaN);
        wb.setFeature(Feature.OUR_AIM_OPPONENT_X, Double.NaN);
        wb.setFeature(Feature.OUR_AIM_OPPONENT_Y, Double.NaN);
        wb.setFeature(Feature.OUR_AIM_DISTANCE, Double.NaN);
        wb.setFeature(Feature.OUR_AIM_LATERAL_VELOCITY, Double.NaN);
        wb.setFeature(Feature.OUR_AIM_BEARING_ABSOLUTE, Double.NaN);
        wb.setFeature(Feature.OUR_AIM_LAG1_GF, Double.NaN);
    }

    /**
     * Wave id encoding: {@code fireTick * 1000 + groupIndex}. groupIndex is 0 for
     * the real bullet and 1..VIRTUAL_BULLET_COUNT for virtual bullets. Stable and
     * identical across the live robot and observer shadow because fireTick matches.
     */
    private static double waveId(double fireTick, int groupIndex) {
        return (long) fireTick * 1000L + groupIndex;
    }

    private void fillFireColumns(Whiteboard wb, int slot,
            double power, double fireX, double fireY, double fireTick,
            double bearing, double bulletSpeed, double direction,
            double distance, double latVel, double advVel, double mea,
            double bulletId, double oppX, double oppY) {
        wb.setOurWave(slot, OurWaveColumn.FIRE_POWER, power);
        wb.setOurWave(slot, OurWaveColumn.FIRE_X, fireX);
        wb.setOurWave(slot, OurWaveColumn.FIRE_Y, fireY);
        wb.setOurWave(slot, OurWaveColumn.FIRE_TICK, fireTick);
        wb.setOurWave(slot, OurWaveColumn.FIRE_BEARING_ABSOLUTE, bearing);
        wb.setOurWave(slot, OurWaveColumn.FIRE_BULLET_SPEED, bulletSpeed);
        wb.setOurWave(slot, OurWaveColumn.FIRE_DIRECTION, direction);
        wb.setOurWave(slot, OurWaveColumn.FIRE_DISTANCE, distance);
        wb.setOurWave(slot, OurWaveColumn.FIRE_LATERAL_VELOCITY, latVel);
        wb.setOurWave(slot, OurWaveColumn.FIRE_ADVANCING_VELOCITY, advVel);
        wb.setOurWave(slot, OurWaveColumn.FIRE_MEA, mea);
        wb.setOurWave(slot, OurWaveColumn.FIRE_BULLET_ID, bulletId);
        wb.setOurWave(slot, OurWaveColumn.FIRE_OPPONENT_X, oppX);
        wb.setOurWave(slot, OurWaveColumn.FIRE_OPPONENT_Y, oppY);
    }

    private void fillAimColumns(Whiteboard wb, int slot,
            double aimX, double aimY, double aimOppX, double aimOppY,
            double aimDistance, double aimLatVel, double aimBearing, double aimLag1Gf) {
        wb.setOurWave(slot, OurWaveColumn.AIM_X, aimX);
        wb.setOurWave(slot, OurWaveColumn.AIM_Y, aimY);
        wb.setOurWave(slot, OurWaveColumn.AIM_OPPONENT_X, aimOppX);
        wb.setOurWave(slot, OurWaveColumn.AIM_OPPONENT_Y, aimOppY);
        wb.setOurWave(slot, OurWaveColumn.AIM_DISTANCE, aimDistance);
        wb.setOurWave(slot, OurWaveColumn.AIM_LATERAL_VELOCITY, aimLatVel);
        wb.setOurWave(slot, OurWaveColumn.AIM_BEARING_ABSOLUTE, aimBearing);
        wb.setOurWave(slot, OurWaveColumn.AIM_LAG1_GF, aimLag1Gf);
    }

    private void resolveWaves(Whiteboard wb) {
        double oppX = wb.getFeature(Feature.OPPONENT_X);
        double oppY = wb.getFeature(Feature.OPPONENT_Y);
        long currentTick = (long) wb.getFeature(Feature.TICK);

        if (Double.isNaN(oppX) || Double.isNaN(oppY)) {
            return;
        }

        ModelSelector selector = wb.getModelSelector();
        VcsStore vcs = wb.getVcsStore();

        for (int slot = 0; slot < Whiteboard.OUR_WAVE_CAPACITY; slot++) {
            if (wb.getOurWaveState(slot) != Whiteboard.WAVE_ACTIVE) {
                continue;
            }

            double fireX = wb.getOurWave(slot, OurWaveColumn.FIRE_X);
            double fireY = wb.getOurWave(slot, OurWaveColumn.FIRE_Y);
            long fireTick = (long) wb.getOurWave(slot, OurWaveColumn.FIRE_TICK);
            double bulletSpeed = wb.getOurWave(slot, OurWaveColumn.FIRE_BULLET_SPEED);

            // Check if wave has reached opponent
            double distTravelled = (currentTick - fireTick) * bulletSpeed;
            double dx = oppX - fireX;
            double dy = oppY - fireY;
            double distToTarget = Math.sqrt(dx * dx + dy * dy);

            if (distTravelled >= distToTarget) {
                // GF=0 baseline uses the pre-aim convention (fire origin -> aim-time
                // opponent reference) and the aim-time direction so the realized GF
                // the model learns matches the GF the gun intended at T-1.
                double fireBearing = gfBaseline(wb, slot, fireX, fireY);
                double mea = wb.getOurWave(slot, OurWaveColumn.FIRE_MEA);
                int direction = aimDirection(wb, slot);

                double actualBearing = Math.atan2(dx, dy);
                double angleOffset = RoboMath.normalRelativeAngle(actualBearing - fireBearing);
                double gf = GuessFactor.guessFactor(angleOffset, mea, direction);

                boolean isReal = wb.getOurWave(slot, OurWaveColumn.IS_REAL) == 1.0;

                // Update models only for real bullets
                if (isReal) {
                    if (selector != null) {
                        selector.update(wb, slot, gf);
                    } else if (vcs != null) {
                        vcs.update(wb, slot, gf);
                    }
                }

                // Write break columns to ring buffer
                wb.setOurWave(slot, OurWaveColumn.BREAK_TICK, currentTick);
                wb.setOurWave(slot, OurWaveColumn.BREAK_GF, gf);
                wb.setOurWave(slot, OurWaveColumn.BREAK_BEARING_OFFSET, angleOffset);
                wb.setOurWave(slot, OurWaveColumn.BREAK_OPPONENT_X, oppX);
                wb.setOurWave(slot, OurWaveColumn.BREAK_OPPONENT_Y, oppY);

                // For virtual bullets, compute geometric would-hit
                if (!isReal) {
                    double aimGf = wb.getOurWave(slot, OurWaveColumn.AIM_GF);
                    boolean wouldHit = computeWouldHit(
                            fireX, fireY, fireBearing, aimGf, mea, direction,
                            oppX, oppY);
                    wb.setOurWave(slot, OurWaveColumn.BREAK_HIT, wouldHit ? 1.0 : 0.0);
                }
                // For real bullets, BREAK_HIT was already set by markBulletHit (1.0) or stays 0

                // Set staging features for debug output and CsvWriter (only for real)
                if (isReal) {
                    wb.setFeature(Feature.OUR_BREAK_TICK, currentTick);
                    wb.setFeature(Feature.OUR_BREAK_GF, gf);
                    wb.setFeature(Feature.OUR_BREAK_BEARING_OFFSET, angleOffset);
                    wb.setFeature(Feature.OUR_BREAK_OPPONENT_X, oppX);
                    wb.setFeature(Feature.OUR_BREAK_OPPONENT_Y, oppY);
                    double hitVal = wb.getOurWave(slot, OurWaveColumn.BREAK_HIT);
                    wb.setFeature(Feature.OUR_BREAK_HIT, Double.isNaN(hitVal) ? 0 : hitVal);
                }

                wb.setOurWaveState(slot, Whiteboard.WAVE_RESOLVED);
            }
        }
    }

    /**
     * Compute whether a virtual bullet aimed at the given GF would have hit
     * the opponent at their actual position. Uses point-distance approximation
     * with robot half-width (18px).
     */
    public static boolean computeWouldHit(double fireX, double fireY,
            double fireBearing, double aimGf, double mea, double direction,
            double oppX, double oppY) {
        double aimBearing = fireBearing + aimGf * mea * direction;
        double dx = oppX - fireX;
        double dy = oppY - fireY;
        double distToTarget = Math.sqrt(dx * dx + dy * dy);
        // Bullet position at the distance where wave reaches opponent
        double bulletX = fireX + distToTarget * Math.sin(aimBearing);
        double bulletY = fireY + distToTarget * Math.cos(aimBearing);
        double missX = bulletX - oppX;
        double missY = bulletY - oppY;
        double missDistance = Math.sqrt(missX * missX + missY * missY);
        return missDistance < BOT_HALF_WIDTH;
    }
}

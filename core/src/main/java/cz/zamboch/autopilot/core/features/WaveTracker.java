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
 * Manages the lifecycle of outgoing bullet waves using the Whiteboard's
 * pre-allocated our-wave ring buffer.
 * <p>
 * On each process() call:
 * <ol>
 * <li>If OUR_FIRE_POWER is set (we fired last tick), allocate a ring slot,
 * copy fire features from staging, mark ACTIVE, then clear staging.
 * Also creates K virtual bullet slots with evenly-spaced AIM_GFs.</li>
 * <li>Resolve any active wave slots that have reached the opponent's current
 * position → update VcsStore (real only) and set OUR_BREAK_* staging
 * features.</li>
 * </ol>
 */
public final class WaveTracker implements IInGameFeatures {

    /** Number of virtual bullets created per real fire event. */
    public static final int VIRTUAL_BULLET_COUNT = 10;

    /** Half-width of robot bounding box for geometric hit detection (px). */
    static final double BOT_HALF_WIDTH = 18.0;

    private static final Feature[] DEPS = {
            Feature.OUR_FIRE_POWER,
            Feature.OUR_FIRE_X,
            Feature.OUR_FIRE_Y,
            Feature.OUR_FIRE_TICK,
            Feature.OUR_FIRE_BEARING_ABSOLUTE,
            Feature.OUR_FIRE_BULLET_SPEED,
            Feature.OUR_FIRE_DIRECTION,
            Feature.OUR_FIRE_DISTANCE,
            Feature.OUR_FIRE_LATERAL_VELOCITY,
            Feature.OUR_FIRE_BULLET_ID,
            Feature.OUR_FIRE_AIM_GF,
            Feature.OUR_FIRE_IS_REAL,
            Feature.OPPONENT_X,
            Feature.OPPONENT_Y,
            Feature.TICK
    };
    private static final Feature[] OUTPUTS = {
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
        createWaveIfFired(wb);
        resolveWaves(wb);
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

        if (Double.isNaN(fireX) || Double.isNaN(bearing) || Double.isNaN(bulletSpeed)) {
            return;
        }

        // Allocate real wave slot
        int slot = wb.allocateOurWave();
        fillFireColumns(wb, slot, power, fireX, fireY, fireTick, bearing,
                bulletSpeed, direction, distance, latVel, advVel, mea,
                bulletId, oppX, oppY);
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
                // Compute guess factor
                double fireBearing = wb.getOurWave(slot, OurWaveColumn.FIRE_BEARING_ABSOLUTE);
                double mea = wb.getOurWave(slot, OurWaveColumn.FIRE_MEA);
                int direction = (int) wb.getOurWave(slot, OurWaveColumn.FIRE_DIRECTION);

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

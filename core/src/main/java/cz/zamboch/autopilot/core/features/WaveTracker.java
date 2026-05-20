package cz.zamboch.autopilot.core.features;

import java.util.Iterator;
import java.util.List;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.GuessFactor;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.VcsStore;
import cz.zamboch.autopilot.core.Wave;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Manages the lifecycle of outgoing bullet waves stored on the Whiteboard.
 * <p>
 * On each process() call:
 * <ol>
 * <li>If OUR_FIRE_POWER is set (we fired last tick), create a new Wave from
 * the OUR_FIRE_* features, then clear the fire features.</li>
 * <li>Resolve any active waves that have reached the opponent's current
 * position → update VcsStore and set OUR_BREAK_* features.</li>
 * </ol>
 */
public final class WaveTracker implements IInGameFeatures {
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
        long fireTick = (long) wb.getFeature(Feature.OUR_FIRE_TICK);
        double bearing = wb.getFeature(Feature.OUR_FIRE_BEARING_ABSOLUTE);
        double bulletSpeed = wb.getFeature(Feature.OUR_FIRE_BULLET_SPEED);
        int direction = (int) wb.getFeature(Feature.OUR_FIRE_DIRECTION);
        double distance = wb.getFeature(Feature.OUR_FIRE_DISTANCE);
        double latVel = wb.getFeature(Feature.OUR_FIRE_LATERAL_VELOCITY);

        if (Double.isNaN(fireX) || Double.isNaN(bearing) || Double.isNaN(bulletSpeed)) {
            return;
        }

        int distSeg = GuessFactor.distanceSegment(distance);
        int latVelSeg = GuessFactor.lateralVelocitySegment(latVel);

        Wave wave = new Wave(fireX, fireY, fireTick, bearing, bulletSpeed,
                direction, distSeg, latVelSeg);
        wb.getActiveWaves().add(wave);

        // Clear fire features so we don't re-create next tick
        wb.setFeature(Feature.OUR_FIRE_POWER, Double.NaN);
        wb.setFeature(Feature.OUR_FIRE_X, Double.NaN);
        wb.setFeature(Feature.OUR_FIRE_Y, Double.NaN);
        wb.setFeature(Feature.OUR_FIRE_TICK, Double.NaN);
        wb.setFeature(Feature.OUR_FIRE_BEARING_ABSOLUTE, Double.NaN);
        wb.setFeature(Feature.OUR_FIRE_BULLET_SPEED, Double.NaN);
        wb.setFeature(Feature.OUR_FIRE_DIRECTION, Double.NaN);
        wb.setFeature(Feature.OUR_FIRE_DISTANCE, Double.NaN);
        wb.setFeature(Feature.OUR_FIRE_LATERAL_VELOCITY, Double.NaN);
    }

    private void resolveWaves(Whiteboard wb) {
        double oppX = wb.getFeature(Feature.OPPONENT_X);
        double oppY = wb.getFeature(Feature.OPPONENT_Y);
        long currentTick = (long) wb.getFeature(Feature.TICK);

        if (Double.isNaN(oppX) || Double.isNaN(oppY)) {
            return;
        }

        VcsStore vcs = wb.getVcsStore();
        List<Wave> waves = wb.getActiveWaves();
        Iterator<Wave> it = waves.iterator();

        while (it.hasNext()) {
            Wave wave = it.next();
            if (wave.hasReached(oppX, oppY, currentTick)) {
                double gf = wave.computeGuessFactor(oppX, oppY);
                int binIndex = GuessFactor.gfToBinIndex(gf, GuessFactor.NUM_BINS);

                // Update VCS
                if (vcs != null) {
                    vcs.increment(wave.distanceSegment, wave.latVelSegment, binIndex);
                }

                // Set break features for CSV output
                wb.setFeature(Feature.OUR_BREAK_TICK, currentTick);
                wb.setFeature(Feature.OUR_BREAK_GF, gf);
                double dx = oppX - wave.fireX;
                double dy = oppY - wave.fireY;
                double actualBearing = Math.atan2(dx, dy);
                double angleOffset = actualBearing - wave.fireBearing;
                wb.setFeature(Feature.OUR_BREAK_BEARING_OFFSET, angleOffset);
                wb.setFeature(Feature.OUR_BREAK_OPPONENT_X, oppX);
                wb.setFeature(Feature.OUR_BREAK_OPPONENT_Y, oppY);
                // Hit detection requires bullet event — set 0 by default,
                // caller overrides to 1 if bullet hit confirmed
                wb.setFeature(Feature.OUR_BREAK_HIT, 0);

                it.remove();
            }
        }
    }
}

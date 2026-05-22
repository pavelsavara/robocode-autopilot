package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.GuessFactor;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.TheirWaveColumn;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Manages the lifecycle of incoming opponent bullet waves using the
 * Whiteboard's
 * their-wave ring buffer.
 * <p>
 * On each process() call:
 * <ol>
 * <li>If THEIR_FIRE_POWER is set (opponent fired), allocate a ring slot,
 * snapshot fire-time geometry, mark ACTIVE, then clear staging.</li>
 * <li>Resolve any active their-wave slots whose bullet has reached our current
 * position → set THEIR_BREAK_* staging features.</li>
 * </ol>
 * <p>
 * Depends on FireFeatures having already computed THEIR_FIRE_POWER, and
 * SpatialFeatures having computed OPPONENT_X/Y.
 */
public final class TheirWaveTracker implements IInGameFeatures {
    private static final Feature[] DEPS = {
            Feature.THEIR_FIRE_POWER,
            Feature.OPPONENT_X,
            Feature.OPPONENT_Y,
            Feature.OUR_X,
            Feature.OUR_Y,
            Feature.TICK
    };
    private static final Feature[] OUTPUTS = {
            Feature.THEIR_FIRE_TICK,
            Feature.THEIR_FIRE_X,
            Feature.THEIR_FIRE_Y,
            Feature.THEIR_BULLET_SPEED,
            Feature.THEIR_FIRE_BEARING,
            Feature.THEIR_FIRE_DISTANCE,
            Feature.THEIR_FIRE_OUR_X,
            Feature.THEIR_FIRE_OUR_Y,
            Feature.THEIR_BREAK_TICK,
            Feature.THEIR_BREAK_OUR_X,
            Feature.THEIR_BREAK_OUR_Y,
            Feature.THEIR_BREAK_GF,
            Feature.THEIR_BREAK_BEARING_OFFSET,
            Feature.THEIR_HIT_US
    };

    public Feature[] getDependencies() {
        return DEPS;
    }

    public Feature[] getOutputFeatures() {
        return OUTPUTS;
    }

    public FileType getFileType() {
        return FileType.THEIR_WAVES;
    }

    public void process(Whiteboard wb) {
        createWaveIfFired(wb);
        resolveWaves(wb);
    }

    private void createWaveIfFired(Whiteboard wb) {
        double power = wb.getFeature(Feature.THEIR_FIRE_POWER);
        if (Double.isNaN(power)) {
            return;
        }

        double oppX = wb.getFeature(Feature.OPPONENT_X);
        double oppY = wb.getFeature(Feature.OPPONENT_Y);
        double ourX = wb.getFeature(Feature.OUR_X);
        double ourY = wb.getFeature(Feature.OUR_Y);
        double tick = wb.getFeature(Feature.TICK);

        if (Double.isNaN(oppX) || Double.isNaN(ourX) || Double.isNaN(tick)) {
            return;
        }

        double dx = ourX - oppX;
        double dy = ourY - oppY;
        double distance = Math.sqrt(dx * dx + dy * dy);
        double bearing = Math.atan2(dx, dy); // bearing from opponent to us
        double bulletSpeed = 20.0 - 3.0 * power;

        // Allocate ring buffer slot and write fire-time columns
        int slot = wb.allocateTheirWave();
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_POWER, power);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_TICK, tick);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_X, oppX);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_Y, oppY);
        wb.setTheirWave(slot, TheirWaveColumn.BULLET_SPEED, bulletSpeed);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_BEARING, bearing);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_DISTANCE, distance);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_OUR_X, ourX);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_OUR_Y, ourY);
        wb.setTheirWaveState(slot, Whiteboard.WAVE_ACTIVE);

        // Also write fire-time features to staging for CsvWriter
        wb.setFeature(Feature.THEIR_FIRE_TICK, tick);
        wb.setFeature(Feature.THEIR_FIRE_X, oppX);
        wb.setFeature(Feature.THEIR_FIRE_Y, oppY);
        wb.setFeature(Feature.THEIR_BULLET_SPEED, bulletSpeed);
        wb.setFeature(Feature.THEIR_FIRE_BEARING, bearing);
        wb.setFeature(Feature.THEIR_FIRE_DISTANCE, distance);
        wb.setFeature(Feature.THEIR_FIRE_OUR_X, ourX);
        wb.setFeature(Feature.THEIR_FIRE_OUR_Y, ourY);

        // Clear fire power staging so we don't re-create next tick
        wb.setFeature(Feature.THEIR_FIRE_POWER, Double.NaN);
    }

    private void resolveWaves(Whiteboard wb) {
        double ourX = wb.getFeature(Feature.OUR_X);
        double ourY = wb.getFeature(Feature.OUR_Y);
        double tick = wb.getFeature(Feature.TICK);

        if (Double.isNaN(ourX) || Double.isNaN(ourY) || Double.isNaN(tick)) {
            return;
        }

        long currentTick = (long) tick;

        for (int slot = 0; slot < Whiteboard.THEIR_WAVE_CAPACITY; slot++) {
            if (wb.getTheirWaveState(slot) != Whiteboard.WAVE_ACTIVE) {
                continue;
            }

            double fireX = wb.getTheirWave(slot, TheirWaveColumn.FIRE_X);
            double fireY = wb.getTheirWave(slot, TheirWaveColumn.FIRE_Y);
            long fireTick = (long) wb.getTheirWave(slot, TheirWaveColumn.FIRE_TICK);
            double bulletSpeed = wb.getTheirWave(slot, TheirWaveColumn.BULLET_SPEED);

            // Check if wave has reached us
            double distTravelled = (currentTick - fireTick) * bulletSpeed;
            double dx = ourX - fireX;
            double dy = ourY - fireY;
            double distToUs = Math.sqrt(dx * dx + dy * dy);

            if (distTravelled >= distToUs) {
                // Compute bearing offset and guess factor from their perspective
                double fireBearing = wb.getTheirWave(slot, TheirWaveColumn.FIRE_BEARING);
                double actualBearing = Math.atan2(dx, dy);
                double bearingOffset = RoboMath.normalRelativeAngle(actualBearing - fireBearing);

                // GF from their perspective: use MEA based on fire distance
                double fireDistance = wb.getTheirWave(slot, TheirWaveColumn.FIRE_DISTANCE);
                double mea = GuessFactor.maxEscapeAngle(bulletSpeed);
                double gf = (mea != 0) ? bearingOffset / mea : 0;
                gf = Math.max(-1.0, Math.min(1.0, gf));

                // Write break columns to ring buffer
                wb.setTheirWave(slot, TheirWaveColumn.BREAK_TICK, currentTick);
                wb.setTheirWave(slot, TheirWaveColumn.BREAK_OUR_X, ourX);
                wb.setTheirWave(slot, TheirWaveColumn.BREAK_OUR_Y, ourY);
                wb.setTheirWave(slot, TheirWaveColumn.BREAK_GF, gf);
                wb.setTheirWave(slot, TheirWaveColumn.BREAK_BEARING_OFFSET, bearingOffset);
                // HIT_US was set by markTheirBulletHitUs or stays 0

                // Set staging features for CsvWriter
                wb.setFeature(Feature.THEIR_BREAK_TICK, currentTick);
                wb.setFeature(Feature.THEIR_BREAK_OUR_X, ourX);
                wb.setFeature(Feature.THEIR_BREAK_OUR_Y, ourY);
                wb.setFeature(Feature.THEIR_BREAK_GF, gf);
                wb.setFeature(Feature.THEIR_BREAK_BEARING_OFFSET, bearingOffset);
                double hitVal = wb.getTheirWave(slot, TheirWaveColumn.HIT_US);
                wb.setFeature(Feature.THEIR_HIT_US, Double.isNaN(hitVal) ? 0 : hitVal);

                wb.setTheirWaveState(slot, Whiteboard.WAVE_RESOLVED);
            }
        }
    }
}

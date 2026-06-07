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
 * <li>Detect opponent fire from scan-to-scan energy drop and stage
 * THEIR_FIRE_POWER.</li>
 * <li>If THEIR_FIRE_POWER is set, allocate a ring slot, snapshot fire-time
 * geometry, mark ACTIVE, then clear staging.</li>
 * <li>Copy the consumed damage-window accumulators into the current scan row
 * and reset the live accumulator window.</li>
 * <li>Resolve any active their-wave slots whose bullet has reached our current
 * position → set THEIR_BREAK_* staging features.</li>
 * </ol>
 * <p>
 * Depends on ScanFeatures having computed previous-scan energy and opponent
 * geometry, and WallHitEstimator having staged opponent wall-hit damage.
 */
public final class TheirWaveTracker implements IInGameFeatures {
    private static final double MIN_FIRE_POWER = 0.1;
    private static final double MAX_FIRE_POWER = 3.0;
    private static final double FIRE_POWER_EPSILON = 1e-9;

    /** Robocode default gun cooling rate; gun heat falls this much each tick. */
    private static final double GUN_COOLING_RATE = 0.1;

    private static final double HIT_MATCH_TOLERANCE = 45.0;

    private static final Feature[] DEPS = {
            Feature.SCAN_TICK,
            Feature.OPPONENT_ENERGY,
            Feature.PREV_SCAN_OPPONENT_ENERGY,
            Feature.OPPONENT_WALL_HIT_DAMAGE,
            Feature.OPPONENT_X,
            Feature.OPPONENT_Y,
            Feature.OUR_X,
            Feature.OUR_Y,
            Feature.TICK
    };
    private static final Feature[] OUTPUTS = {
            Feature.THEIR_FIRE_POWER,
            Feature.THEIR_GUN_HEAT,
            Feature.THEIR_INACTIVITY_ZAP_ACTIVE,
            Feature.THEIR_ENERGY_DROP_ADJUSTED,
            Feature.THEIR_FIRE_TICK,
            Feature.THEIR_FIRE_X,
            Feature.THEIR_FIRE_Y,
            Feature.THEIR_BULLET_SPEED,
            Feature.THEIR_FIRE_BEARING,
            Feature.THEIR_FIRE_DISTANCE,
            Feature.THEIR_FIRE_OUR_X,
            Feature.THEIR_FIRE_OUR_Y,
            Feature.THEIR_AIM_X,
            Feature.THEIR_AIM_Y,
            Feature.THEIR_AIM_OUR_X,
            Feature.THEIR_AIM_OUR_Y,
            Feature.THEIR_AIM_DISTANCE,
            Feature.THEIR_AIM_BEARING,
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
        detectFirePower(wb);
        createWaveIfFired(wb);
        copyAndResetDamageAccumulators(wb);
        resolveWaves(wb);
    }

    private void copyAndResetDamageAccumulators(Whiteboard wb) {
        if (!wb.hasCurrentScan()) {
            return;
        }
        wb.copyDamageAccumulatorsToCurrentScanRow();
        wb.clearDamageAccumulatorFeatures();
    }

    private void detectFirePower(Whiteboard wb) {
        if (!wb.hasCurrentScan()) {
            return;
        }
        double tick = wb.getFeature(Feature.SCAN_TICK);

        double currentEnergy = wb.getFeature(Feature.OPPONENT_ENERGY);
        double prevEnergy = wb.getFeature(Feature.PREV_SCAN_OPPONENT_ENERGY);

        if (Double.isNaN(prevEnergy)) {
            return;
        }

        double prevScanTick = wb.getPreviousScanFeature(Feature.SCAN_TICK);
        double deltaTick = (Double.isNaN(prevScanTick) || Double.isNaN(tick))
                ? 1.0
                : tick - prevScanTick;

        double prevGunHeat = wb.getPreviousScanFeature(Feature.THEIR_GUN_HEAT);
        if (Double.isNaN(prevGunHeat)) {
            prevGunHeat = 0.0;
        }
        double gunHeat = Math.max(0.0, prevGunHeat - GUN_COOLING_RATE * deltaTick);

        double drop = prevEnergy - currentEnergy;

        double bulletDmg = nonNan(wb.getFeature(Feature.OUR_BULLET_DAMAGE_TO_OPPONENT));
        double bulletGain = nonNan(wb.getFeature(Feature.OPPONENT_BULLET_ENERGY_GAIN));
        double ramDmg = nonNan(wb.getFeature(Feature.RAM_DAMAGE_TO_OPPONENT));
        double wallDmg = nonNan(wb.getFeature(Feature.OPPONENT_WALL_HIT_DAMAGE));

        // Adjusted scan-to-scan energy drop: strip the energy we can attribute to
        // bullet/ram/wall damage so what remains is the opponent's fire spend.
        // Inactivity-zap attribution is deliberately NOT modeled: competitive
        // opponents fire continuously and never reach the engine's 450-tick idle
        // threshold, so a zap term only ever steals genuine fire signal.
        double fireDrop = drop - bulletDmg - ramDmg - wallDmg + bulletGain;

        boolean gunReady = gunHeat <= FIRE_POWER_EPSILON;

        if (gunReady
                && fireDrop >= MIN_FIRE_POWER - FIRE_POWER_EPSILON
                && fireDrop <= MAX_FIRE_POWER + FIRE_POWER_EPSILON) {
            double firePower = Math.max(MIN_FIRE_POWER, Math.min(MAX_FIRE_POWER, fireDrop));
            wb.setFeature(Feature.THEIR_FIRE_POWER, firePower);
            gunHeat = 1.0 + firePower / 5.0;
        } else {
            wb.setFeature(Feature.THEIR_FIRE_POWER, Double.NaN);
        }

        wb.setCurrentScanFeature(Feature.THEIR_GUN_HEAT, gunHeat);
        wb.setCurrentScanFeature(Feature.THEIR_INACTIVITY_ZAP_ACTIVE, 0.0);
        wb.setCurrentScanFeature(Feature.THEIR_ENERGY_DROP_ADJUSTED, fireDrop);
    }

    /**
     * Mark that an opponent bullet hit us, preferring bullet identity and
     * trajectory geometry over the legacy power-only match.
     */
    public static void markBulletHitUs(Whiteboard wb, int bulletId, double power,
            double bulletX, double bulletY, double bulletHeading, long tick) {
        int slot = uniqueActiveWaveWithBulletId(wb, bulletId);
        if (slot < 0) {
            slot = nearestActiveWave(wb, power, bulletX, bulletY, bulletHeading, tick);
        }
        if (slot >= 0) {
            wb.setTheirWave(slot, TheirWaveColumn.BULLET_ID, bulletId);
            wb.setTheirWave(slot, TheirWaveColumn.HIT_US, 1.0);
            return;
        }
        markBulletHitUs(wb, power);
    }

    /** Attach a known engine bullet id to the matching active incoming wave. */
    public static void assignBulletId(Whiteboard wb, int bulletId, double power,
            double bulletX, double bulletY, double bulletHeading, long tick) {
        if (bulletId == 0) {
            return;
        }
        int slot = nearestActiveWave(wb, power, bulletX, bulletY, bulletHeading, tick);
        if (slot >= 0 && Double.isNaN(wb.getTheirWave(slot, TheirWaveColumn.BULLET_ID))) {
            wb.setTheirWave(slot, TheirWaveColumn.BULLET_ID, bulletId);
        }
    }

    /** Legacy fallback for idless/ambiguous hits. */
    public static void markBulletHitUs(Whiteboard wb, double power) {
        for (int i = 0; i < Whiteboard.THEIR_WAVE_CAPACITY; i++) {
            if (wb.getTheirWaveState(i) == Whiteboard.WAVE_ACTIVE
                    && Math.abs(wb.getTheirWave(i, TheirWaveColumn.FIRE_POWER) - power) < 0.001) {
                wb.setTheirWave(i, TheirWaveColumn.HIT_US, 1.0);
                return;
            }
        }
    }

    private static int uniqueActiveWaveWithBulletId(Whiteboard wb, int bulletId) {
        if (bulletId == 0) {
            return -1;
        }
        int found = -1;
        for (int i = 0; i < Whiteboard.THEIR_WAVE_CAPACITY; i++) {
            if (wb.getTheirWaveState(i) == Whiteboard.WAVE_ACTIVE
                    && (int) wb.getTheirWave(i, TheirWaveColumn.BULLET_ID) == bulletId) {
                if (found >= 0) {
                    return -1;
                }
                found = i;
            }
        }
        return found;
    }

    private static int nearestActiveWave(Whiteboard wb, double power, double bulletX, double bulletY,
            double bulletHeading, long tick) {
        if (Double.isNaN(bulletX) || Double.isNaN(bulletY) || Double.isNaN(bulletHeading)) {
            return -1;
        }
        int bestSlot = -1;
        double bestError = Double.POSITIVE_INFINITY;
        for (int i = 0; i < Whiteboard.THEIR_WAVE_CAPACITY; i++) {
            if (wb.getTheirWaveState(i) != Whiteboard.WAVE_ACTIVE
                    || Math.abs(wb.getTheirWave(i, TheirWaveColumn.FIRE_POWER) - power) >= 0.001) {
                continue;
            }
            double fireTick = wb.getTheirWave(i, TheirWaveColumn.FIRE_TICK);
            double bulletSpeed = wb.getTheirWave(i, TheirWaveColumn.BULLET_SPEED);
            if (Double.isNaN(fireTick) || Double.isNaN(bulletSpeed)) {
                continue;
            }
            double travelled = (tick - fireTick) * bulletSpeed;
            double expectedX = wb.getTheirWave(i, TheirWaveColumn.FIRE_X) + Math.sin(bulletHeading) * travelled;
            double expectedY = wb.getTheirWave(i, TheirWaveColumn.FIRE_Y) + Math.cos(bulletHeading) * travelled;
            double error = Math.hypot(expectedX - bulletX, expectedY - bulletY);
            if (error < bestError) {
                bestError = error;
                bestSlot = i;
            }
        }
        return bestError <= HIT_MATCH_TOLERANCE ? bestSlot : -1;
    }

    private void createWaveIfFired(Whiteboard wb) {
        double power = wb.getFeature(Feature.THEIR_FIRE_POWER);
        if (Double.isNaN(power)) {
            return;
        }

        // The fire detector observes opponent fire from a scan-to-scan energy
        // drop observed at the CURRENT tick D. By Robocode timing, the opponent's
        // fire code ran at tick D-1: the bullet is created (and energy deducted)
        // at loadCommands of tick D from the opponent's position at the end of
        // D-1, then advances one step before D's status is published. So the true
        // muzzle is the opponent's position at tick D-1, and the true fire tick is
        // D-1. Using the current-tick position would mis-place the wave origin by
        // one tick of opponent movement (~6-8 px). Validated against god-view
        // ground truth (back-projected IBulletSnapshot muzzle) to an exact match.
        // Prefer strict T-1 (true muzzle). If T-1 was a no-scan tick, fall back
        // to the CURRENT-tick scan rather than walking the ring back:
        // * THEIR_FIRE_POWER is only set when OPPONENT_ENERGY is
        // present THIS tick (a scan just landed), so current OPPONENT_X/Y
        // is guaranteed populated AND identical between live and observer
        // (both decode from the same engine snapshot).
        // * Walking the ring back across multiple no-scan ticks would diverge
        // between live and observer whenever the reconstructed scan arc
        // timing differs by +-1 tick from the live engine -- that asymmetry
        // pollutes ~20 wave columns for the wave's entire lifetime
        // (Layer 0 regression: 19 -> 11882 mismatches on BeepBoop).
        // Cost: ~6-8 px muzzle position error on waves where T-1 was missed
        // (one tick of opponent movement at max velocity).
        double fireTick = wb.getFeature(Feature.TICK) - 1.0;
        double oppX = wb.getScanFeatureAtOrBeforeTick(Feature.OPPONENT_X, fireTick);
        double oppY = wb.getScanFeatureAtOrBeforeTick(Feature.OPPONENT_Y, fireTick);
        if (Double.isNaN(oppX) || Double.isNaN(oppY)) {
            oppX = wb.getFeature(Feature.OPPONENT_X);
            oppY = wb.getFeature(Feature.OPPONENT_Y);
        }
        double ourX = wb.getPreviousTickFeature(Feature.OUR_X);
        double ourY = wb.getPreviousTickFeature(Feature.OUR_Y);

        if (Double.isNaN(oppX) || Double.isNaN(ourX) || Double.isNaN(fireTick)) {
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
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_TICK, fireTick);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_X, oppX);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_Y, oppY);
        wb.setTheirWave(slot, TheirWaveColumn.BULLET_SPEED, bulletSpeed);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_BEARING, bearing);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_DISTANCE, distance);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_OUR_X, ourX);
        wb.setTheirWave(slot, TheirWaveColumn.FIRE_OUR_Y, ourY);
        wb.setTheirWaveState(slot, Whiteboard.WAVE_ACTIVE);

        // Aim-time geometry: the opponent aimed reacting to the world state one
        // tick before its fire tick (T-1 = D-2, two ticks before our detection).
        // Attribute the aiming decision to that tick. The opponent (firer) was the
        // most recently scanned one at or before the aim tick — walk the ring back
        // across any radar-lock gap so this is never NaN. Our own position at the
        // aim tick is always known.
        double aimTick = wb.getFeature(Feature.TICK) - 2.0;
        double aimOppX = wb.getScanFeatureAtOrBeforeTick(Feature.OPPONENT_X, aimTick);
        double aimOppY = wb.getScanFeatureAtOrBeforeTick(Feature.OPPONENT_Y, aimTick);
        double aimOurX = wb.getFeatureNTicksAgo(Feature.OUR_X, 2);
        double aimOurY = wb.getFeatureNTicksAgo(Feature.OUR_Y, 2);
        double aimDx = aimOurX - aimOppX;
        double aimDy = aimOurY - aimOppY;
        double aimDistance = Math.sqrt(aimDx * aimDx + aimDy * aimDy);
        double aimBearing = Math.atan2(aimDx, aimDy);
        wb.setTheirWave(slot, TheirWaveColumn.AIM_X, aimOppX);
        wb.setTheirWave(slot, TheirWaveColumn.AIM_Y, aimOppY);
        wb.setTheirWave(slot, TheirWaveColumn.AIM_OUR_X, aimOurX);
        wb.setTheirWave(slot, TheirWaveColumn.AIM_OUR_Y, aimOurY);
        wb.setTheirWave(slot, TheirWaveColumn.AIM_DISTANCE, aimDistance);
        wb.setTheirWave(slot, TheirWaveColumn.AIM_BEARING, aimBearing);

        // Also write fire-time features to staging for CsvWriter
        wb.setFeature(Feature.THEIR_FIRE_TICK, fireTick);
        wb.setFeature(Feature.THEIR_FIRE_X, oppX);
        wb.setFeature(Feature.THEIR_FIRE_Y, oppY);
        wb.setFeature(Feature.THEIR_BULLET_SPEED, bulletSpeed);
        wb.setFeature(Feature.THEIR_FIRE_BEARING, bearing);
        wb.setFeature(Feature.THEIR_FIRE_DISTANCE, distance);
        wb.setFeature(Feature.THEIR_FIRE_OUR_X, ourX);
        wb.setFeature(Feature.THEIR_FIRE_OUR_Y, ourY);
        wb.setFeature(Feature.THEIR_AIM_X, aimOppX);
        wb.setFeature(Feature.THEIR_AIM_Y, aimOppY);
        wb.setFeature(Feature.THEIR_AIM_OUR_X, aimOurX);
        wb.setFeature(Feature.THEIR_AIM_OUR_Y, aimOurY);
        wb.setFeature(Feature.THEIR_AIM_DISTANCE, aimDistance);
        wb.setFeature(Feature.THEIR_AIM_BEARING, aimBearing);

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

    private static double nonNan(double value) {
        return Double.isNaN(value) ? 0.0 : value;
    }
}

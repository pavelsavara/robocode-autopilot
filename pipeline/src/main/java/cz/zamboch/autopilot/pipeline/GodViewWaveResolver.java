package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.GuessFactor;
import cz.zamboch.autopilot.core.ModelSelector;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.VcsStore;
import cz.zamboch.autopilot.core.Wave;
import cz.zamboch.autopilot.core.Whiteboard;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Pipeline-side wave resolver using god-view (engine ground truth).
 * <p>
 * Adapted from the original {@code WaveResolver} to work with
 * {@link ObserverContext}
 * instead of {@code Perspective}. Detects when each perspective's robot fires a
 * bullet
 * (via IBulletSnapshot), creates a Wave with fire-time features, and resolves
 * it using
 * the true opponent position from the engine snapshot.
 * <p>
 * Sets OUR_FIRE_* at fire time and OUR_BREAK_* at resolution time on the
 * observer's Whiteboard. Also sets THEIR_* features on the peer's Whiteboard.
 * <p>
 * Unlike the robot-side {@code OurWaveTracker} which relies on stale scan data,
 * this uses exact opponent coordinates every tick for ground-truth resolution.
 */
final class GodViewWaveResolver {

    /** Per-perspective wave tracking state. */
    private final PerPerspective[] persp = { new PerPerspective(), new PerPerspective() };

    /** Per-perspective flag: true if a new fire was detected this tick. */
    private final boolean[] firedThisTick = { false, false };

    /** Per-perspective real-wave resolutions produced by the last processTick. */
    private final List<Layer4WaveBreak>[] resolvedThisTick = newBreakLists();

    /** Set of bullet IDs already used to create waves. */
    private final Set<Integer> knownBulletIds = new HashSet<>();

    /** Set of bullet IDs that hit the victim (for OUR_BREAK_HIT). */
    private final Set<Integer> hitBulletIds = new HashSet<>();

    /** Per-perspective per-round counters for hit-rate tracking. */
    private final int[] roundFired = { 0, 0 };
    private final int[] roundHits = { 0, 0 };

    void resetRound() {
        for (PerPerspective pp : persp) {
            pp.activeWaves.clear();
            pp.pendingFire = false;
        }
        for (List<Layer4WaveBreak> breaks : resolvedThisTick) {
            breaks.clear();
        }
        knownBulletIds.clear();
        hitBulletIds.clear();
        roundFired[0] = 0;
        roundFired[1] = 0;
        roundHits[0] = 0;
        roundHits[1] = 0;
    }

    /** Get hit rate for a perspective in the current round. */
    double getRoundHitRate(int perspIndex) {
        return roundFired[perspIndex] > 0 ? (double) roundHits[perspIndex] / roundFired[perspIndex] : Double.NaN;
    }

    /**
     * Returns true if a new fire was detected for the given perspective during the
     * last processTick call.
     */
    boolean firedThisTick(int perspIndex) {
        return firedThisTick[perspIndex];
    }

    /**
     * True muzzle heading (radians) of the most recent fire detected for the given
     * perspective. This is the engine bullet's actual flight direction — the one
     * piece of fire-time information the blindfolded robot cannot infer from an
     * energy drop (it must assume a head-on bearing instead).
     */
    double getLastFiredTrueHeading(int perspIndex) {
        TrackedWave tw = persp[perspIndex].lastFiredWave;
        return tw != null ? tw.trueHeading : Double.NaN;
    }

    List<Layer4WaveBreak> resolvedWavesThisTick(int perspIndex) {
        return Collections.unmodifiableList(resolvedThisTick[perspIndex]);
    }

    /**
     * Main per-tick call. Detects new bullets (fire), resolves existing waves,
     * and sets features on whiteboards.
     *
     * @return for each perspective index, true if a wave was resolved this tick
     */
    boolean[] processTick(ObserverContext[] observers, IRobotSnapshot[] robots, ITurnSnapshot turn) {
        boolean[] resolved = { false, false };
        firedThisTick[0] = false;
        firedThisTick[1] = false;
        resolvedThisTick[0].clear();
        resolvedThisTick[1].clear();

        detectNewBullets(turn, observers, robots);
        detectHitBullets(turn);

        for (ObserverContext ctx : observers) {
            if (ctx.isDead())
                continue;
            PerPerspective pp = persp[ctx.perspectiveIndex()];

            // If a fire was detected, set OUR_FIRE_* features on the god-view whiteboard
            if (pp.pendingFire) {
                setFireFeatures(ctx.godWb(), pp.lastFiredWave);
                pp.pendingFire = false;
            }

            // Resolve waves using true opponent position
            if (!ctx.peerContext().isDead()) {
                double oppX = robots[ctx.peerContext().perspectiveIndex()].getX();
                double oppY = robots[ctx.peerContext().perspectiveIndex()].getY();
                long tick = (long) ctx.godWb().getFeature(Feature.TICK);

                resolved[ctx.perspectiveIndex()] = resolveWaves(ctx.godWb(), ctx.peerContext().godWb(),
                        pp, ctx.perspectiveIndex(), oppX, oppY, tick);
            }
        }

        return resolved;
    }

    private void detectNewBullets(ITurnSnapshot turn, ObserverContext[] observers,
            IRobotSnapshot[] robots) {
        IBulletSnapshot[] bullets = turn.getBullets();
        if (bullets == null)
            return;

        for (IBulletSnapshot bullet : bullets) {
            if (bullet.getState() == BulletState.INACTIVE)
                continue;
            int id = bullet.getBulletId();
            if (!knownBulletIds.add(id))
                continue;

            // New bullet — attribute to the perspective where this is "our" bullet
            int owner = bullet.getOwnerIndex();
            for (ObserverContext ctx : observers) {
                if (owner == ctx.perspectiveIndex() && !ctx.peerContext().isDead()) {
                    createWave(ctx, bullet, robots);
                }
            }
        }
    }

    private void detectHitBullets(ITurnSnapshot turn) {
        IBulletSnapshot[] bullets = turn.getBullets();
        if (bullets == null)
            return;

        for (IBulletSnapshot bullet : bullets) {
            if (bullet.getState() == BulletState.HIT_VICTIM) {
                hitBulletIds.add(bullet.getBulletId());
            }
        }
    }

    private void createWave(ObserverContext ctx, IBulletSnapshot bullet, IRobotSnapshot[] robots) {
        IRobotSnapshot opponent = robots[ctx.peerContext().perspectiveIndex()];

        double power = bullet.getPower();
        double bulletSpeed = GuessFactor.bulletSpeed(power);

        // Back-project the bullet to its muzzle (the true fire origin).
        // A bullet is created at the robot's body position on the tick the fire
        // command is loaded, then advanced exactly one step (v = 20 - 3*power along
        // its heading) before it first appears in any snapshot. By that tick the
        // robot body has already moved a full tick away from where it fired, so
        // reading self.getX()/getY() here would report the post-fire body position
        // — overstating the origin by up to one tick of robot movement (the
        // residual Layer 3 positionMAE). Subtracting the single bullet step from the
        // bullet's own coordinates recovers the exact muzzle, matching the
        // robot-side OUR_FIRE_X/Y (which is captured at the fire tick).
        double bulletHeading = bullet.getHeading();
        double fireX = bullet.getX() - bulletSpeed * Math.sin(bulletHeading);
        double fireY = bullet.getY() - bulletSpeed * Math.cos(bulletHeading);

        // Absolute bearing from us to opponent at fire time
        double dx = opponent.getX() - fireX;
        double dy = opponent.getY() - fireY;
        double absoluteBearing = Math.atan2(dx, dy);
        double distance = Math.sqrt(dx * dx + dy * dy);

        // Lateral/advancing velocity of the opponent in the CORE sign convention
        // (ScanFeatures: relative to bearing-from-opponent = absBearing + PI), so the
        // recorded OUR_FIRE_LATERAL_VELOCITY / DIRECTION match the live robot.
        double oppHeading = opponent.getBodyHeading();
        double oppVel = opponent.getVelocity();
        double relativeHeading = oppHeading - (absoluteBearing + Math.PI);
        double latVel = oppVel * Math.sin(relativeHeading);

        int direction = GuessFactor.direction(latVel);
        int distSeg = GuessFactor.distanceSegment(distance);
        int latVelSeg = GuessFactor.lateralVelocitySegment(latVel);

        // True fire tick. The bullet first appears in a snapshot one tick AFTER the
        // fire command ran: it is created at loadCommands of the detection tick from
        // the body position at the end of the previous tick (the muzzle we
        // back-projected above), then advanced one step before this snapshot. So the
        // tick the robot actually fired — and the tick the back-projected muzzle
        // belongs to — is the detection tick minus one. Using the detection tick
        // would (a) overstate the fire tick by one relative to the robot-side
        // OUR_FIRE_TICK (captured at the true fire tick) and (b) undercount
        // distanceTravelled by one bullet step in resolveWaves, resolving every wave
        // one tick late.
        long fireTick = (long) ctx.godWb().getFeature(Feature.TICK) - 1L;

        Wave wave = new Wave(fireX, fireY, fireTick, absoluteBearing,
                bulletSpeed, direction, distSeg, latVelSeg, 0, distance);

        double advVel = oppVel * Math.cos(relativeHeading);

        // Aim-time positions: two ticks back from the detection tick D (= one tick
        // before the fire tick D-1). The god-view tick ring is seeded from the
        // robot-side ring in Phase 1.5. The firer's own position at the aim tick is
        // always known; the target (opponent) position is the most recently scanned
        // one at or before the aim tick — walk the ring back across any radar-lock
        // gap so it is never NaN, matching the live robot's last-known lookup.
        // Captured here and stored on the TrackedWave so both OUR_AIM_* (firer's wb)
        // and THEIR_AIM_* (target's wb, written at break) read consistent values.
        Whiteboard firerWb = ctx.godWb();
        double aimFirerX = firerWb.getFeatureNTicksAgo(Feature.OUR_X, 2);
        double aimFirerY = firerWb.getFeatureNTicksAgo(Feature.OUR_Y, 2);
        double aimTick = firerWb.getFeature(Feature.TICK) - 2.0;
        double aimTargetX = firerWb.getScanFeatureAtOrBeforeTick(Feature.OPPONENT_X, aimTick);
        double aimTargetY = firerWb.getScanFeatureAtOrBeforeTick(Feature.OPPONENT_Y, aimTick);
        double aimLatVel = firerWb.getScanFeatureAtOrBeforeTick(Feature.OPPONENT_LATERAL_VELOCITY, aimTick);

        PerPerspective pp = persp[ctx.perspectiveIndex()];
        // Lag-1 dodge context: developing GF of the most-recent still-active real
        // wave (last element of activeWaves) at the opponent's aim-time position.
        if (!pp.activeWaves.isEmpty()) {
            TrackedWave prevTw = pp.activeWaves.get(pp.activeWaves.size() - 1);
            Wave prev = prevTw.wave;
            double prevBase = gfBaseline(prevTw.aimTargetX, prevTw.aimTargetY,
                    prev.fireX, prev.fireY, prev.fireBearing);
            int prevDir = Double.isNaN(prevTw.aimLatVel)
                    ? prev.direction : GuessFactor.direction(prevTw.aimLatVel);
            wave.lag1Gf = prev.computeGuessFactor(aimTargetX, aimTargetY, prevBase, prevDir);
        }
        pp.activeWaves.add(new TrackedWave(wave, bullet.getBulletId(),
                distance, latVel, advVel, power,
                opponent.getX(), opponent.getY(), bulletHeading,
                aimFirerX, aimFirerY, aimTargetX, aimTargetY, aimLatVel));
        pp.lastFiredWave = pp.activeWaves.get(pp.activeWaves.size() - 1);
        pp.pendingFire = true;
        firedThisTick[ctx.perspectiveIndex()] = true;
    }

    private void setFireFeatures(Whiteboard wb, TrackedWave tw) {
        Wave w = tw.wave;
        wb.setFeature(Feature.OUR_FIRE_POWER, tw.power);
        wb.setFeature(Feature.OUR_FIRE_X, w.fireX);
        wb.setFeature(Feature.OUR_FIRE_Y, w.fireY);
        wb.setFeature(Feature.OUR_FIRE_TICK, w.fireTick);
        wb.setFeature(Feature.OUR_FIRE_BULLET_ID, tw.bulletId);
        wb.setFeature(Feature.OUR_FIRE_BEARING_ABSOLUTE, w.fireBearing);
        wb.setFeature(Feature.OUR_FIRE_DISTANCE, tw.fireDistance);
        wb.setFeature(Feature.OUR_FIRE_LATERAL_VELOCITY, tw.fireLateralVelocity);
        wb.setFeature(Feature.OUR_FIRE_ADVANCING_VELOCITY, tw.fireAdvancingVelocity);
        wb.setFeature(Feature.OUR_FIRE_BULLET_SPEED, w.bulletSpeed);
        wb.setFeature(Feature.OUR_FIRE_MEA, w.mea);
        wb.setFeature(Feature.OUR_FIRE_DIRECTION, w.direction);
        wb.setFeature(Feature.OUR_FIRE_OPPONENT_X, tw.fireOpponentX);
        wb.setFeature(Feature.OUR_FIRE_OPPONENT_Y, tw.fireOpponentY);
        wb.setFeature(Feature.OUR_FIRE_IS_REAL, 1.0);

        // Aim-time geometry: the gun was aimed one tick before the fire tick. The
        // bullet is detected at the current tick D, fired at D-1, so the gun was
        // aimed reacting to the world state at D-2 — two ticks back in the god-view
        // tick ring (which is seeded from the robot-side ring each tick and holds
        // seeded from the robot-side ring each tick). Captured at createWave (detection
        // tick),
        // stored on the TrackedWave, because setFireFeatures is also re-invoked at
        // break time when the ring no longer holds the fire tick.
        double aimDx = tw.aimTargetX - tw.aimFirerX;
        double aimDy = tw.aimTargetY - tw.aimFirerY;
        double aimDistance = Math.sqrt(aimDx * aimDx + aimDy * aimDy);
        double aimBearing = Math.atan2(aimDx, aimDy);
        wb.setFeature(Feature.OUR_AIM_X, tw.aimFirerX);
        wb.setFeature(Feature.OUR_AIM_Y, tw.aimFirerY);
        wb.setFeature(Feature.OUR_AIM_OPPONENT_X, tw.aimTargetX);
        wb.setFeature(Feature.OUR_AIM_OPPONENT_Y, tw.aimTargetY);
        wb.setFeature(Feature.OUR_AIM_DISTANCE, aimDistance);
        wb.setFeature(Feature.OUR_AIM_LATERAL_VELOCITY, tw.aimLatVel);
        wb.setFeature(Feature.OUR_AIM_BEARING_ABSOLUTE, aimBearing);
        wb.setFeature(Feature.OUR_AIM_LAG1_GF, w.lag1Gf);

        // Compute aim GF in the AIM-time frame (distance/latVel the gun keyed its
        // prediction on at T-1), matching the live robot's computeGunAim so the
        // reconstructed OUR_FIRE_AIM_GF equals what the live gun intended.
        ModelSelector selector = wb.getModelSelector();
        double aimGf = 0.0;
        if (selector != null) {
            aimGf = selector.predictForAim(aimDistance, tw.aimLatVel, w.lag1Gf, w.mea, aimBearing);
        } else {
            VcsStore vcs = wb.getVcsStore();
            if (vcs != null) {
                int distSeg = Double.isNaN(aimDistance)
                        ? w.distanceSegment : GuessFactor.distanceSegment(aimDistance);
                int latVelSeg = Double.isNaN(tw.aimLatVel)
                        ? w.latVelSegment : GuessFactor.lateralVelocitySegment(tw.aimLatVel);
                int window = GuessFactor.gfBoxWindowBins(aimDistance, w.mea, GuessFactor.NUM_BINS, aimBearing);
                int bestBin = vcs.getBestBinBoxed(distSeg, latVelSeg, w.lag1Gf, window);
                aimGf = GuessFactor.binIndexToGf(bestBin, GuessFactor.NUM_BINS);
            }
        }
        wb.setFeature(Feature.OUR_FIRE_AIM_GF, aimGf);
    }

    private boolean resolveWaves(Whiteboard wb, Whiteboard peerWb, PerPerspective pp, int perspIndex,
            double oppX, double oppY, long currentTick) {
        boolean anyResolved = false;
        Iterator<TrackedWave> it = pp.activeWaves.iterator();

        while (it.hasNext()) {
            TrackedWave tw = it.next();
            if (tw.wave.hasReached(oppX, oppY, currentTick)) {
                double baseBearing = gfBaseline(tw.aimTargetX, tw.aimTargetY,
                        tw.wave.fireX, tw.wave.fireY, tw.wave.fireBearing);
                int dir = Double.isNaN(tw.aimLatVel)
                        ? tw.wave.direction : GuessFactor.direction(tw.aimLatVel);
                double gf = tw.wave.computeGuessFactor(oppX, oppY, baseBearing, dir);
                int binIndex = GuessFactor.gfToBinIndex(gf, GuessFactor.NUM_BINS);

                // Train in the aim-time frame (segments the gun predicted on), so
                // the promoted model matches the live online model.
                double aimDist = Math.hypot(tw.aimTargetX - tw.aimFirerX,
                        tw.aimTargetY - tw.aimFirerY);
                int distSeg = Double.isNaN(aimDist)
                        ? tw.wave.distanceSegment : GuessFactor.distanceSegment(aimDist);
                int latVelSeg = Double.isNaN(tw.aimLatVel)
                        ? tw.wave.latVelSegment : GuessFactor.lateralVelocitySegment(tw.aimLatVel);

                // Update VCS store (common to both paths)
                VcsStore vcs = wb.getVcsStore();
                if (vcs != null) {
                    vcs.increment(distSeg, latVelSeg, tw.wave.lag1Gf, binIndex);
                }

                // Update model selector if present
                ModelSelector selector = wb.getModelSelector();
                if (selector != null) {
                    selector.recordPipelineUpdate(distSeg, latVelSeg,
                            tw.wave.lag1Gf, gf);
                }

                wb.setFeature(Feature.OUR_BREAK_TICK, currentTick);
                wb.setFeature(Feature.OUR_BREAK_GF, gf);

                double dx = oppX - tw.wave.fireX;
                double dy = oppY - tw.wave.fireY;
                double actualBearing = Math.atan2(dx, dy);
                double angleOffset = RoboMath.normalRelativeAngle(actualBearing - baseBearing);
                wb.setFeature(Feature.OUR_BREAK_BEARING_OFFSET, angleOffset);
                wb.setFeature(Feature.OUR_BREAK_OPPONENT_X, oppX);
                wb.setFeature(Feature.OUR_BREAK_OPPONENT_Y, oppY);

                boolean hit = hitBulletIds.contains(tw.bulletId);
                wb.setFeature(Feature.OUR_BREAK_HIT, hit ? 1.0 : 0.0);

                // Track hit rate
                roundFired[perspIndex]++;
                if (hit)
                    roundHits[perspIndex]++;
                resolvedThisTick[perspIndex].add(new Layer4WaveBreak(tw.bulletId, gf, currentTick));

                // Also re-set fire features so CSV row has both fire + break —
                // UNLESS a new fire was detected for this perspective on the SAME
                // tick. The fresh fire's OUR_FIRE_* (tick/x/y/power) was just written
                // at the top of processTick; re-setting them here to the resolving
                // (older) wave would clobber the new fire, causing the Phase 5 GV
                // recorder to log the real new fire at the resolving wave's older
                // tick/position (a duplicate GV fire) while dropping the new fire's
                // true tick entirely (which then surfaces as a robot-side phantom).
                if (!firedThisTick[perspIndex]) {
                    setFireFeatures(wb, tw);
                }

                // Set THEIR_* features on the peer's whiteboard (their perspective)
                setTheirWaveFeatures(peerWb, tw, oppX, oppY, currentTick, gf, angleOffset, hit);

                anyResolved = true;
                it.remove();
            }
        }
        return anyResolved;
    }

    @SuppressWarnings("unchecked")
    private static List<Layer4WaveBreak>[] newBreakLists() {
        return new List[] { new ArrayList<Layer4WaveBreak>(), new ArrayList<Layer4WaveBreak>() };
    }

    /**
     * Set their-wave fire + break features on the target's whiteboard.
     * Called when OUR wave resolves — this is THEIR incoming wave from the target's
     * perspective.
     */
    private void setTheirWaveFeatures(Whiteboard peerWb, TrackedWave tw,
            double targetX, double targetY, long breakTick, double gf, double bearingOffset, boolean hit) {
        Wave w = tw.wave;

        // Fire-time features (from the firer to the target)
        peerWb.setFeature(Feature.THEIR_FIRE_POWER, tw.power);
        peerWb.setFeature(Feature.THEIR_FIRE_TICK, w.fireTick);
        peerWb.setFeature(Feature.THEIR_FIRE_X, w.fireX);
        peerWb.setFeature(Feature.THEIR_FIRE_Y, w.fireY);
        peerWb.setFeature(Feature.THEIR_BULLET_SPEED, w.bulletSpeed);
        peerWb.setFeature(Feature.THEIR_FIRE_BEARING, w.fireBearing);
        peerWb.setFeature(Feature.THEIR_FIRE_DISTANCE, tw.fireDistance);
        peerWb.setFeature(Feature.THEIR_FIRE_OUR_X, tw.fireOpponentX);
        peerWb.setFeature(Feature.THEIR_FIRE_OUR_Y, tw.fireOpponentY);

        // Aim-time features (one tick before the fire tick). From the target's
        // perspective "they" is the firer: THEIR_AIM_X/Y is the firer's position
        // at aim time, THEIR_AIM_OUR_X/Y is the target's own position at aim time.
        double aimDx = tw.aimTargetX - tw.aimFirerX;
        double aimDy = tw.aimTargetY - tw.aimFirerY;
        double aimDistance = Math.sqrt(aimDx * aimDx + aimDy * aimDy);
        double aimBearing = Math.atan2(aimDx, aimDy);
        peerWb.setFeature(Feature.THEIR_AIM_X, tw.aimFirerX);
        peerWb.setFeature(Feature.THEIR_AIM_Y, tw.aimFirerY);
        peerWb.setFeature(Feature.THEIR_AIM_OUR_X, tw.aimTargetX);
        peerWb.setFeature(Feature.THEIR_AIM_OUR_Y, tw.aimTargetY);
        peerWb.setFeature(Feature.THEIR_AIM_DISTANCE, aimDistance);
        peerWb.setFeature(Feature.THEIR_AIM_BEARING, aimBearing);
        // Our (target's) lateral velocity in the firer's frame at aim time — the GF
        // sign. Same quantity the core TheirWaveTracker captures, so their_break_gf
        // stays consistent between observer and god-view.
        peerWb.setFeature(Feature.THEIR_AIM_LATERAL_VELOCITY, tw.aimLatVel);

        // Break-time features
        peerWb.setFeature(Feature.THEIR_BREAK_TICK, breakTick);
        peerWb.setFeature(Feature.THEIR_BREAK_OUR_X, targetX);
        peerWb.setFeature(Feature.THEIR_BREAK_OUR_Y, targetY);
        peerWb.setFeature(Feature.THEIR_BREAK_GF, gf);
        peerWb.setFeature(Feature.THEIR_BREAK_BEARING_OFFSET, bearingOffset);
        peerWb.setFeature(Feature.THEIR_HIT_US, hit ? 1.0 : 0.0);
    }

    /**
     * GF=0 baseline bearing (pre-aim convention): from the fire origin to the
     * aim-time opponent reference position, matching the live robot's pre-aim and
     * {@code OurWaveTracker.gfBaseline}. Falls back to {@code fallbackBearing} (the
     * recorded fire bearing) when the aim-time opponent position is unknown.
     */
    private static double gfBaseline(double aimOppX, double aimOppY,
            double fireX, double fireY, double fallbackBearing) {
        if (Double.isNaN(aimOppX) || Double.isNaN(aimOppY)) {
            return fallbackBearing;
        }
        return Math.atan2(aimOppX - fireX, aimOppY - fireY);
    }

    // --- Internal types ---

    private static final class PerPerspective {
        final List<TrackedWave> activeWaves = new ArrayList<>();
        TrackedWave lastFiredWave;
        boolean pendingFire;
    }

    private static final class TrackedWave {
        final Wave wave;
        final int bulletId;
        final double fireDistance;
        final double fireLateralVelocity;
        final double fireAdvancingVelocity;
        final double power;
        final double fireOpponentX;
        final double fireOpponentY;
        /** True muzzle heading (radians) from the engine bullet snapshot. */
        final double trueHeading;
        /** Aim-time positions (one tick before fire): firer + target. */
        final double aimFirerX;
        final double aimFirerY;
        final double aimTargetX;
        final double aimTargetY;
        /** Opponent lateral velocity at aim time (the gun's GF prediction frame). */
        final double aimLatVel;

        TrackedWave(Wave wave, int bulletId, double distance, double latVel,
                double advVel, double power, double oppX, double oppY, double trueHeading,
                double aimFirerX, double aimFirerY, double aimTargetX, double aimTargetY,
                double aimLatVel) {
            this.wave = wave;
            this.bulletId = bulletId;
            this.fireDistance = distance;
            this.fireLateralVelocity = latVel;
            this.fireAdvancingVelocity = advVel;
            this.power = power;
            this.fireOpponentX = oppX;
            this.fireOpponentY = oppY;
            this.trueHeading = trueHeading;
            this.aimFirerX = aimFirerX;
            this.aimFirerY = aimFirerY;
            this.aimTargetX = aimTargetX;
            this.aimTargetY = aimTargetY;
            this.aimLatVel = aimLatVel;
        }
    }
}

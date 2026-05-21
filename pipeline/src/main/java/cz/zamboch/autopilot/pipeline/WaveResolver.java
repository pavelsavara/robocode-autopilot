package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.GuessFactor;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.VcsStore;
import cz.zamboch.autopilot.core.Wave;
import cz.zamboch.autopilot.core.Whiteboard;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Pipeline-side wave resolver using god-view (engine ground truth).
 * <p>
 * Detects when each perspective's robot fires a bullet (via IBulletSnapshot),
 * creates a Wave with fire-time features, and resolves it using the true
 * opponent position from the engine snapshot. Sets OUR_FIRE_* at fire time
 * and OUR_BREAK_* at resolution time.
 * <p>
 * Unlike the robot-side {@code WaveTracker} which relies on stale scan data,
 * this uses exact opponent coordinates every tick for ground-truth resolution.
 */
final class WaveResolver {

    /** Per-perspective wave tracking state. */
    private final PerPerspective[] persp = { new PerPerspective(), new PerPerspective() };

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
     * Main per-tick call. Detects new bullets (fire), resolves existing waves,
     * and sets features on whiteboards.
     *
     * @return for each perspective index, true if a wave was resolved this tick
     */
    boolean[] processTick(Perspective[] perspectives, IRobotSnapshot[] robots, ITurnSnapshot turn) {
        boolean[] resolved = { false, false };

        detectNewBullets(turn, perspectives, robots);
        detectHitBullets(turn);

        for (Perspective us : perspectives) {
            if (us.isDead())
                continue;
            PerPerspective pp = persp[us.robotIndex()];

            // If a fire was detected, set OUR_FIRE_* features on the whiteboard
            if (pp.pendingFire) {
                setFireFeatures(us.wb(), pp.lastFiredWave);
                pp.pendingFire = false;
            }

            // Resolve waves using true opponent position
            if (!us.peer().isDead()) {
                double oppX = robots[us.peer().robotIndex()].getX();
                double oppY = robots[us.peer().robotIndex()].getY();
                long tick = (long) us.wb().getFeature(Feature.TICK);

                resolved[us.robotIndex()] = resolveWaves(us.wb(), us.peer().wb(),
                        pp, us.robotIndex(), oppX, oppY, tick);
            }
        }

        return resolved;
    }

    private void detectNewBullets(ITurnSnapshot turn, Perspective[] perspectives,
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
            for (Perspective us : perspectives) {
                if (owner == us.robotIndex() && !us.peer().isDead()) {
                    createWave(us, bullet, robots);
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

    private void createWave(Perspective us, IBulletSnapshot bullet, IRobotSnapshot[] robots) {
        IRobotSnapshot self = robots[us.robotIndex()];
        IRobotSnapshot opponent = robots[us.peer().robotIndex()];

        double fireX = self.getX();
        double fireY = self.getY();
        double power = bullet.getPower();
        double bulletSpeed = GuessFactor.bulletSpeed(power);

        // Absolute bearing from us to opponent at fire time
        double dx = opponent.getX() - fireX;
        double dy = opponent.getY() - fireY;
        double absoluteBearing = Math.atan2(dx, dy);
        double distance = Math.sqrt(dx * dx + dy * dy);

        // Lateral velocity of opponent relative to our bearing
        double oppHeading = opponent.getBodyHeading();
        double oppVel = opponent.getVelocity();
        double latVel = oppVel * Math.sin(oppHeading - absoluteBearing);

        int direction = GuessFactor.direction(latVel);
        int distSeg = GuessFactor.distanceSegment(distance);
        int latVelSeg = GuessFactor.lateralVelocitySegment(latVel);

        long fireTick = (long) us.wb().getFeature(Feature.TICK);

        Wave wave = new Wave(fireX, fireY, fireTick, absoluteBearing,
                bulletSpeed, direction, distSeg, latVelSeg);

        PerPerspective pp = persp[us.robotIndex()];
        pp.activeWaves.add(new TrackedWave(wave, bullet.getBulletId(),
                distance, latVel, oppVel, power,
                opponent.getX(), opponent.getY()));
        pp.lastFiredWave = pp.activeWaves.get(pp.activeWaves.size() - 1);
        pp.pendingFire = true;
    }

    private void setFireFeatures(Whiteboard wb, TrackedWave tw) {
        Wave w = tw.wave;
        wb.setFeature(Feature.OUR_FIRE_POWER, tw.power);
        wb.setFeature(Feature.OUR_FIRE_X, w.fireX);
        wb.setFeature(Feature.OUR_FIRE_Y, w.fireY);
        wb.setFeature(Feature.OUR_FIRE_TICK, w.fireTick);
        wb.setFeature(Feature.OUR_FIRE_BEARING_ABSOLUTE, w.fireBearing);
        wb.setFeature(Feature.OUR_FIRE_DISTANCE, tw.fireDistance);
        wb.setFeature(Feature.OUR_FIRE_LATERAL_VELOCITY, tw.fireLateralVelocity);
        wb.setFeature(Feature.OUR_FIRE_ADVANCING_VELOCITY, tw.fireAdvancingVelocity);
        wb.setFeature(Feature.OUR_FIRE_BULLET_SPEED, w.bulletSpeed);
        wb.setFeature(Feature.OUR_FIRE_MEA, w.mea);
        wb.setFeature(Feature.OUR_FIRE_DIRECTION, w.direction);
        wb.setFeature(Feature.OUR_FIRE_OPPONENT_X, tw.fireOpponentX);
        wb.setFeature(Feature.OUR_FIRE_OPPONENT_Y, tw.fireOpponentY);
    }

    private boolean resolveWaves(Whiteboard wb, Whiteboard peerWb, PerPerspective pp, int perspIndex,
            double oppX, double oppY, long currentTick) {
        boolean anyResolved = false;
        Iterator<TrackedWave> it = pp.activeWaves.iterator();

        while (it.hasNext()) {
            TrackedWave tw = it.next();
            if (tw.wave.hasReached(oppX, oppY, currentTick)) {
                double gf = tw.wave.computeGuessFactor(oppX, oppY);
                int binIndex = GuessFactor.gfToBinIndex(gf, GuessFactor.NUM_BINS);

                // Update VCS store
                VcsStore vcs = wb.getVcsStore();
                if (vcs != null) {
                    vcs.increment(tw.wave.distanceSegment, tw.wave.latVelSegment, binIndex);
                }

                wb.setFeature(Feature.OUR_BREAK_TICK, currentTick);
                wb.setFeature(Feature.OUR_BREAK_GF, gf);

                double dx = oppX - tw.wave.fireX;
                double dy = oppY - tw.wave.fireY;
                double actualBearing = Math.atan2(dx, dy);
                double angleOffset = RoboMath.normalRelativeAngle(actualBearing - tw.wave.fireBearing);
                wb.setFeature(Feature.OUR_BREAK_BEARING_OFFSET, angleOffset);
                wb.setFeature(Feature.OUR_BREAK_OPPONENT_X, oppX);
                wb.setFeature(Feature.OUR_BREAK_OPPONENT_Y, oppY);

                boolean hit = hitBulletIds.contains(tw.bulletId);
                wb.setFeature(Feature.OUR_BREAK_HIT, hit ? 1.0 : 0.0);

                // Track hit rate
                roundFired[perspIndex]++;
                if (hit)
                    roundHits[perspIndex]++;

                // Also re-set fire features so CSV row has both fire + break
                setFireFeatures(wb, tw);

                // Set THEIR_* features on the peer's whiteboard (their perspective)
                setTheirWaveFeatures(peerWb, tw, oppX, oppY, currentTick, gf, angleOffset, hit);

                anyResolved = true;
                it.remove();
            }
        }
        return anyResolved;
    }

    /**
     * Set their-wave fire + break features on the target's whiteboard.
     * Called when OUR wave resolves — this is THEIR incoming wave from the target's perspective.
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

        // Break-time features
        peerWb.setFeature(Feature.THEIR_BREAK_TICK, breakTick);
        peerWb.setFeature(Feature.THEIR_BREAK_OUR_X, targetX);
        peerWb.setFeature(Feature.THEIR_BREAK_OUR_Y, targetY);
        peerWb.setFeature(Feature.THEIR_BREAK_GF, gf);
        peerWb.setFeature(Feature.THEIR_BREAK_BEARING_OFFSET, bearingOffset);
        peerWb.setFeature(Feature.THEIR_HIT_US, hit ? 1.0 : 0.0);
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

        TrackedWave(Wave wave, int bulletId, double distance, double latVel,
                double advVel, double power, double oppX, double oppY) {
            this.wave = wave;
            this.bulletId = bulletId;
            this.fireDistance = distance;
            this.fireLateralVelocity = latVel;
            this.fireAdvancingVelocity = advVel;
            this.power = power;
            this.fireOpponentX = oppX;
            this.fireOpponentY = oppY;
        }
    }
}

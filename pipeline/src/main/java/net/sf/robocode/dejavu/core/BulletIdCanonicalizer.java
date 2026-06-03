/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.core;

import robocode.Rules;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.IScoreSnapshot;
import robocode.control.snapshot.ITurnSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Restores unique per-bullet identity to captured turn snapshots before they
 * reach the reconstructors.
 * <p>
 * The host assigns each fire command a bullet id once, then the battle replays
 * the persisted command list every turn. An engine that does not consume a
 * fired command (a robot that stops calling {@code execute()} while a fire is
 * pending) can fire the <em>same</em> command twice, producing two distinct
 * {@code BulletPeer}s that share one id (see {@code PR.md}). Every reconstructor
 * site keys a bullet by its id, so two live bullets sharing an id collapse to a
 * single tracking slot (last-writer-wins) and corrupt rising-edge detection,
 * fire-cost accounting and the post-movement position replay.
 * <p>
 * This pass detects such collisions and rewrites the duplicate's id into a
 * disjoint negative band so every live bullet again has a unique id. Real ids
 * are always positive ({@code 1 + robotIndex*10000 + roundNum*1000000 + ...}),
 * so a negative id can never alias one. The <em>n</em>-th instance sharing a raw
 * id {@code r} is mapped to:
 * <ul>
 *   <li>{@code n == 0} (the original): {@code r}</li>
 *   <li>{@code n == 1} (first duplicate): {@code -r}</li>
 *   <li>{@code n >= 2}: {@code -r - (n - 1) * R}</li>
 * </ul>
 * where {@code R} exceeds every real id, so each band {@code [(n-1)*R, n*R)} is
 * disjoint and the mapping is injective over {@code (rawId, n)}.
 * <p>
 * The instance ordinal {@code n} cannot be read from array order: the engine
 * never reorders the bullet list (fire order, survivor-stable removal), but when
 * an older same-id bullet is removed the newer one shifts forward and a naive
 * per-tick recount would flip its ordinal. The ordinal is therefore carried
 * across turns by trajectory continuity &mdash; each current bullet is matched
 * to the previous-turn instance whose predicted next position is nearest &mdash;
 * so a synthetic id stays glued to its physical bullet for life, surviving the
 * removal of any earlier same-id bullet.
 */
final class BulletIdCanonicalizer {

    /**
     * Maximum gap between a bullet's predicted next position and the matched
     * snapshot position. A bullet advances at most {@code Rules.MAX_BULLET_SPEED}
     * (20) px/turn, and duplicate bullets sharing an id are fired many turns
     * apart so they are separated by far more than this; the tolerance is loose
     * enough to absorb the snap-back applied to a bullet on the turn it hits, yet
     * far tighter than any realistic inter-duplicate distance.
     */
    private static final double MATCH_TOLERANCE = 30.0;

    /** Width of each negative id band; exceeds every real bullet id. */
    private final long range;

    /** Bullets tracked from the previous canonicalized tick. */
    private List<Tracked> previous = new ArrayList<Tracked>();

    private boolean remappedThisTick;

    BulletIdCanonicalizer(int numRounds) {
        // Real id = 1 + robotIndex*10000 + roundNum*1000000 + fireCountThisRound.
        // (numRounds + 1) * 1_000_000 dominates that: robotIndex*10000 < 1_000_000
        // and a robot fires far fewer than 1_000_000 bullets in a round.
        this.range = (long) (Math.max(numRounds, 1) + 1) * 1_000_000L;
    }

    /** Begin a new round; discard cross-turn tracking state. */
    void reset() {
        previous = new ArrayList<Tracked>();
        remappedThisTick = false;
    }

    /** Whether the most recent {@link #canonicalize} remapped a duplicate id. */
    boolean remappedThisTick() {
        return remappedThisTick;
    }

    /**
     * Return a view of {@code raw} in which any bullet sharing an id with a
     * concurrently-live bullet has been re-keyed into the negative band, leaving
     * every other bullet (and the snapshot itself) untouched. When no duplicate
     * is present the original snapshot is returned unchanged.
     */
    ITurnSnapshot canonicalize(ITurnSnapshot raw) {
        remappedThisTick = false;

        IBulletSnapshot[] bullets = raw == null ? null : raw.getBullets();
        if (bullets == null || bullets.length == 0) {
            previous = new ArrayList<Tracked>();
            return raw;
        }

        Tracked[] tracked = new Tracked[bullets.length];
        boolean[] prevUsed = new boolean[previous.size()];

        // Pass 1: carry the synthetic id forward for every bullet that continues a
        // tracked instance (nearest predicted position among same-id candidates).
        for (int i = 0; i < bullets.length; i++) {
            IBulletSnapshot b = bullets[i];
            int best = -1;
            double bestDist = MATCH_TOLERANCE;
            for (int j = 0; j < previous.size(); j++) {
                Tracked t = previous.get(j);
                if (prevUsed[j] || t.owner != b.getOwnerIndex() || t.rawId != b.getBulletId()) {
                    continue;
                }
                double d = t.distanceToPredicted(b.getX(), b.getY());
                if (d < bestDist) {
                    bestDist = d;
                    best = j;
                }
            }
            if (best >= 0) {
                Tracked t = previous.get(best);
                prevUsed[best] = true;
                tracked[i] = Tracked.continued(t, b);
            }
        }

        // Pass 2: allocate a fresh ordinal for every newly-born bullet, choosing
        // the smallest ordinal not already in use by another same-id instance this
        // tick (whether carried forward or just allocated).
        for (int i = 0; i < bullets.length; i++) {
            if (tracked[i] != null) {
                continue;
            }
            IBulletSnapshot b = bullets[i];
            int ordinal = 0;
            while (ordinalInUse(tracked, b.getBulletId(), ordinal)) {
                ordinal++;
            }
            int canonicalId = canonicalFor(b.getBulletId(), ordinal);
            tracked[i] = Tracked.born(b, canonicalId, ordinal);
        }

        // Roll the tracking state forward and decide whether anything was remapped.
        List<Tracked> next = new ArrayList<Tracked>(bullets.length);
        boolean anyRemap = false;
        for (int i = 0; i < bullets.length; i++) {
            next.add(tracked[i]);
            if (tracked[i].canonicalId != tracked[i].rawId) {
                anyRemap = true;
            }
        }
        previous = next;
        remappedThisTick = anyRemap;

        if (!anyRemap) {
            return raw;
        }

        IBulletSnapshot[] wrapped = new IBulletSnapshot[bullets.length];
        for (int i = 0; i < bullets.length; i++) {
            wrapped[i] = tracked[i].canonicalId == bullets[i].getBulletId()
                    ? bullets[i]
                    : new RemappedBullet(bullets[i], tracked[i].canonicalId);
        }
        return new RemappedTurn(raw, wrapped);
    }

    /** Whether {@code ordinal} for {@code rawId} is already assigned this tick. */
    private boolean ordinalInUse(Tracked[] tracked, int rawId, int ordinal) {
        int canonicalId = canonicalFor(rawId, ordinal);
        for (Tracked t : tracked) {
            if (t != null && t.rawId == rawId && t.canonicalId == canonicalId) {
                return true;
            }
        }
        return false;
    }

    /** Map the {@code ordinal}-th instance of {@code rawId} to its canonical id. */
    private int canonicalFor(int rawId, int ordinal) {
        if (ordinal == 0) {
            return rawId;
        }
        return (int) (-((long) rawId) - (ordinal - 1) * range);
    }

    /** One physical bullet's identity and last-known kinematics. */
    private static final class Tracked {
        final int canonicalId;
        final int rawId;
        final int owner;
        final int ordinal;
        final double x;
        final double y;
        final double heading;
        final double power;
        final boolean active;

        private Tracked(int canonicalId, int rawId, int owner, int ordinal,
                IBulletSnapshot b) {
            this.canonicalId = canonicalId;
            this.rawId = rawId;
            this.owner = owner;
            this.ordinal = ordinal;
            this.x = b.getX();
            this.y = b.getY();
            this.heading = b.getHeading();
            this.power = b.getPower();
            this.active = b.getState() != null && b.getState().isActive();
        }

        static Tracked born(IBulletSnapshot b, int canonicalId, int ordinal) {
            return new Tracked(canonicalId, b.getBulletId(), b.getOwnerIndex(), ordinal, b);
        }

        static Tracked continued(Tracked prev, IBulletSnapshot b) {
            return new Tracked(prev.canonicalId, b.getBulletId(), b.getOwnerIndex(),
                    prev.ordinal, b);
        }

        /**
         * Distance from this bullet's predicted next position to {@code (px, py)}.
         * A live bullet advances one velocity step along its heading; a bullet in
         * a terminal state is frozen for its explosion animation, so its predicted
         * position is its current position.
         */
        double distanceToPredicted(double px, double py) {
            double v = active ? Rules.getBulletSpeed(power) : 0.0;
            double nx = x + v * Math.sin(heading);
            double ny = y + v * Math.cos(heading);
            double dx = nx - px;
            double dy = ny - py;
            return Math.sqrt(dx * dx + dy * dy);
        }
    }

    /** A {@link IBulletSnapshot} with a rewritten id; all else delegates. */
    private static final class RemappedBullet implements IBulletSnapshot {
        private final IBulletSnapshot delegate;
        private final int bulletId;

        RemappedBullet(IBulletSnapshot delegate, int bulletId) {
            this.delegate = delegate;
            this.bulletId = bulletId;
        }

        @Override
        public int getBulletId() {
            return bulletId;
        }

        @Override
        public BulletState getState() {
            return delegate.getState();
        }

        @Override
        public double getPower() {
            return delegate.getPower();
        }

        @Override
        public double getX() {
            return delegate.getX();
        }

        @Override
        public double getY() {
            return delegate.getY();
        }

        @Override
        public double getPaintX() {
            return delegate.getPaintX();
        }

        @Override
        public double getPaintY() {
            return delegate.getPaintY();
        }

        @Override
        public int getColor() {
            return delegate.getColor();
        }

        @Override
        public int getFrame() {
            return delegate.getFrame();
        }

        @Override
        public boolean isExplosion() {
            return delegate.isExplosion();
        }

        @Override
        public int getExplosionImageIndex() {
            return delegate.getExplosionImageIndex();
        }

        @Override
        public double getHeading() {
            return delegate.getHeading();
        }

        @Override
        public int getVictimIndex() {
            return delegate.getVictimIndex();
        }

        @Override
        public int getOwnerIndex() {
            return delegate.getOwnerIndex();
        }
    }

    /** A {@link ITurnSnapshot} exposing canonicalized bullets; all else delegates. */
    private static final class RemappedTurn implements ITurnSnapshot {
        private final ITurnSnapshot delegate;
        private final IBulletSnapshot[] bullets;

        RemappedTurn(ITurnSnapshot delegate, IBulletSnapshot[] bullets) {
            this.delegate = delegate;
            this.bullets = bullets;
        }

        @Override
        public IBulletSnapshot[] getBullets() {
            return bullets.clone();
        }

        @Override
        public IRobotSnapshot[] getRobots() {
            return delegate.getRobots();
        }

        @Override
        public int getTPS() {
            return delegate.getTPS();
        }

        @Override
        public int getRound() {
            return delegate.getRound();
        }

        @Override
        public int getTurn() {
            return delegate.getTurn();
        }

        @Override
        public IScoreSnapshot[] getSortedTeamScores() {
            return delegate.getSortedTeamScores();
        }

        @Override
        public IScoreSnapshot[] getIndexedTeamScores() {
            return delegate.getIndexedTeamScores();
        }
    }
}

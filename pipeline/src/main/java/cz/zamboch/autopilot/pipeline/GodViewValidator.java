package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.Whiteboard;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Cross-robot quality validator. For each perspective ("us"), compares what
 * our whiteboard says about the opponent ("them") against their actual engine
 * state.
 * <p>
 * Unlike {@link DebugValidator} (which fails fast on pipeline bugs), this
 * accumulates per-feature statistics to measure estimation quality over time.
 * All comparisons are exact (within epsilon).
 */
public final class GodViewValidator {

    private static final double EPSILON = 1e-4;

    // Per-perspective state indexed by robotIndex (0 or 1)
    private final PerspectiveState[] state = { new PerspectiveState(), new PerspectiveState() };

    // Track known bullet IDs to detect new bullets (FIRED state not in snapshots)
    private final Set<Integer> knownBulletIds = new HashSet<>();

    /**
     * Reset per-round state. Call at the start of each new round.
     */
    public void resetRound() {
        for (PerspectiveState ps : state) {
            ps.firePowerAccum = 0;
            ps.fireCount = 0;
        }
        knownBulletIds.clear();
    }

    // --- Validation (called each tick) ---

    /**
     * Validate both perspectives, but only on ticks where a fresh scan occurred.
     * Between scans the whiteboard intentionally holds stale data — comparing
     * it against current reality would penalize normal radar gaps.
     * <p>
     * Tracks opponent FIRED bullets each tick to provide ground truth for
     * OPPONENT_FIRE_POWER validation on scan ticks.
     */
    public void validate(Perspective[] perspectives, IRobotSnapshot[] robots, ITurnSnapshot turn) {
        trackFiredBullets(turn, perspectives);

        for (Perspective us : perspectives) {
            PerspectiveState ps = state[us.robotIndex()];

            double tick = us.wb().getFeature(Feature.TICK);
            double lastScan = us.wb().getFeature(Feature.LAST_SCAN_TICK);
            if (tick == lastScan) {
                if (us.isOurs() && ps.fireCount > 1) {
                    throw new IllegalStateException(
                            "Multiple opponent fires (" + ps.fireCount
                                    + ") between our scans — radar gap too large at tick "
                                    + (long) tick);
                }
                validatePerspective(us.wb(), robots[us.robotIndex()],
                        robots[us.peer().robotIndex()], ps.stats,
                        ps.firePowerAccum, ps.fireCount);
                ps.firePowerAccum = 0;
                ps.fireCount = 0;
            }
        }
    }

    private void trackFiredBullets(ITurnSnapshot turn, Perspective[] perspectives) {
        IBulletSnapshot[] bullets = turn.getBullets();
        if (bullets == null)
            return;
        for (IBulletSnapshot bullet : bullets) {
            if (bullet.getState() == BulletState.INACTIVE)
                continue;
            int id = bullet.getBulletId();
            if (!knownBulletIds.add(id))
                continue;
            // New bullet — attribute to the perspective where owner is "them"
            int owner = bullet.getOwnerIndex();
            for (Perspective us : perspectives) {
                if (owner == us.peer().robotIndex()) {
                    PerspectiveState ps = state[us.robotIndex()];
                    ps.firePowerAccum += bullet.getPower();
                    ps.fireCount++;
                }
            }
        }
        // Clean up IDs for bullets no longer in snapshot
        if (knownBulletIds.size() > 100) {
            Set<Integer> active = new HashSet<>();
            for (IBulletSnapshot bullet : bullets) {
                if (bullet.getState() != BulletState.INACTIVE)
                    active.add(bullet.getBulletId());
            }
            knownBulletIds.retainAll(active);
        }
    }

    private void validatePerspective(Whiteboard wb, IRobotSnapshot self,
            IRobotSnapshot opponent, Map<Feature, Stats> stats,
            double opponentFirePower, int opponentFireCount) {
        for (Feature feature : Feature.values()) {
            double estimated = wb.getFeature(feature);
            double truth = getGroundTruth(feature, self, opponent,
                    opponentFirePower, opponentFireCount);

            if (Double.isNaN(estimated) || Double.isNaN(truth))
                continue;

            Stats s = stats.computeIfAbsent(feature, k -> new Stats());
            s.checks++;

            if (Math.abs(estimated - truth) <= EPSILON) {
                s.hits++;
            } else {
                s.misses++;
            }
        }
    }

    /**
     * Map a whiteboard feature to the actual engine value.
     * Returns NaN for features that cannot be computed from snapshots alone.
     */
    private static double getGroundTruth(Feature feature,
            IRobotSnapshot self, IRobotSnapshot opponent,
            double opponentFirePower, int opponentFireCount) {
        switch (feature) {
            // --- Our state (always exact from snapshot) ---
            case OUR_X:
                return self.getX();
            case OUR_Y:
                return self.getY();
            case OUR_HEADING:
                return self.getBodyHeading();
            case OUR_VELOCITY:
                return self.getVelocity();
            case OUR_ENERGY:
                return self.getEnergy();
            case GUN_HEAT:
                return self.getGunHeat();
            case GUN_HEADING:
                return self.getGunHeading();
            case RADAR_HEADING:
                return self.getRadarHeading();

            // --- Opponent state (exact on scan ticks) ---
            case OPPONENT_ENERGY:
                return opponent.getEnergy();
            case OPPONENT_HEADING:
                return opponent.getBodyHeading();
            case OPPONENT_VELOCITY:
                return opponent.getVelocity();

            // --- Computed spatial (exact on scan ticks) ---
            case DISTANCE:
                return Math.hypot(
                        opponent.getX() - self.getX(),
                        opponent.getY() - self.getY());
            case BEARING_RADIANS: {
                double dx = opponent.getX() - self.getX();
                double dy = opponent.getY() - self.getY();
                double absoluteBearing = Math.atan2(dx, dy);
                return RoboMath.normalRelativeAngle(absoluteBearing - self.getBodyHeading());
            }
            case OPPONENT_BEARING_ABSOLUTE: {
                double dx = opponent.getX() - self.getX();
                double dy = opponent.getY() - self.getY();
                double heading = self.getBodyHeading();
                double relBearing = RoboMath.normalRelativeAngle(
                        Math.atan2(dx, dy) - heading);
                return heading + relBearing;
            }
            case OPPONENT_X:
                return opponent.getX();
            case OPPONENT_Y:
                return opponent.getY();
            case OPPONENT_LATERAL_VELOCITY: {
                double dx = opponent.getX() - self.getX();
                double dy = opponent.getY() - self.getY();
                double absBearing = Math.atan2(dx, dy);
                // Pipeline uses bearing-from-opponent (absBearing + PI)
                double relHeading = opponent.getBodyHeading() - absBearing - Math.PI;
                return opponent.getVelocity() * Math.sin(relHeading);
            }
            case OPPONENT_ADVANCING_VELOCITY: {
                double dx = opponent.getX() - self.getX();
                double dy = opponent.getY() - self.getY();
                double absBearing = Math.atan2(dx, dy);
                // Pipeline uses bearing-from-opponent (absBearing + PI)
                double relHeading = opponent.getBodyHeading() - absBearing - Math.PI;
                return opponent.getVelocity() * Math.cos(relHeading);
            }

            // --- Fire detection ---
            case THEIR_FIRE_POWER:
                if (opponentFireCount == 1) {
                    return opponentFirePower;
                }
                return Double.NaN;

            default:
                return Double.NaN;
        }
    }

    // --- Reporting ---

    /** Get accuracy (0.0–1.0) for a feature across both perspectives. */
    public double getAccuracy(Feature feature) {
        long totalChecks = 0;
        long totalHits = 0;
        for (PerspectiveState ps : state) {
            Stats s = ps.stats.get(feature);
            if (s != null) {
                totalChecks += s.checks;
                totalHits += s.hits;
            }
        }
        return totalChecks == 0 ? 1.0 : (double) totalHits / totalChecks;
    }

    /** Total checks performed across all features, both perspectives. */
    public long getTotalChecks() {
        long total = 0;
        for (PerspectiveState ps : state)
            for (Stats s : ps.stats.values())
                total += s.checks;
        return total;
    }

    /** Total hits (matches) across all features, both perspectives. */
    public long getTotalHits() {
        long total = 0;
        for (PerspectiveState ps : state)
            for (Stats s : ps.stats.values())
                total += s.hits;
        return total;
    }

    /** Print a summary table to stdout. */
    public void printSummary() {
        System.out.println("=== GOD VIEW VALIDATION ===");
        System.out.println(String.format("%-35s %8s %8s %8s %10s",
                "Feature", "Checks", "Hits", "Misses", "Accuracy"));

        for (Feature feature : Feature.values()) {
            long checks = 0, hits = 0, misses = 0;
            for (PerspectiveState ps : state) {
                Stats s = ps.stats.getOrDefault(feature, Stats.EMPTY);
                checks += s.checks;
                hits += s.hits;
                misses += s.misses;
            }
            if (checks == 0)
                continue;
            double accuracy = (double) hits / checks;

            System.out.println(String.format("%-35s %8d %8d %8d %9.1f%%",
                    feature.name(), checks, hits, misses, accuracy * 100));
        }

        long totalChecks = getTotalChecks();
        long totalHits = getTotalHits();
        double overall = totalChecks == 0 ? 1.0 : (double) totalHits / totalChecks;
        System.out.println(String.format("%-35s %8d %8d %8d %9.1f%%",
                "TOTAL", totalChecks, totalHits, totalChecks - totalHits, overall * 100));
        System.out.println();
    }

    // --- Internal types ---

    private static final class PerspectiveState {
        double firePowerAccum;
        int fireCount;
        final Map<Feature, Stats> stats = new EnumMap<>(Feature.class);
    }

    private static final class Stats {
        static final Stats EMPTY = new Stats();
        long checks;
        long hits;
        long misses;
    }
}

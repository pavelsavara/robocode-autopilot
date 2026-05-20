package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.Whiteboard;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Cross-robot quality validator. Compares what robot A's whiteboard says about
 * opponent B against B's actual engine state (and vice versa).
 * <p>
 * Unlike {@link DebugValidator} (which fails fast on pipeline bugs), this
 * accumulates per-feature statistics to measure estimation quality over time.
 * <p>
 * Validation modes per feature:
 * <ul>
 * <li><b>EXACT</b> — must match within epsilon</li>
 * <li><b>DELAYED</b> — ground truth may match a recent pipeline value (1–N
 * ticks)</li>
 * <li><b>MARGIN</b> — allows a configurable absolute tolerance</li>
 * </ul>
 */
public final class GodViewValidator {

    public enum Mode {
        EXACT, DELAYED, MARGIN
    }

    private static final double DEFAULT_EPSILON = 1e-4;
    private static final int HISTORY_CAPACITY = 4;

    private final Map<Feature, Rule> rules = new EnumMap<>(Feature.class);
    private final Map<Feature, Stats> statsA = new EnumMap<>(Feature.class);
    private final Map<Feature, Stats> statsB = new EnumMap<>(Feature.class);

    public GodViewValidator() {
        exact(Feature.OPPONENT_HEADING);
        exact(Feature.OPPONENT_VELOCITY);
        exact(Feature.DISTANCE);
        exact(Feature.BEARING_RADIANS);
        exact(Feature.OPPONENT_ENERGY);
        exact(Feature.OPPONENT_FIRE_POWER);
    }

    // --- Configuration ---

    private void exact(Feature feature) {
        rules.put(feature, new Rule(Mode.EXACT, DEFAULT_EPSILON, 0));
    }

    private void margin(Feature feature, double tolerance) {
        rules.put(feature, new Rule(Mode.MARGIN, tolerance, 0));
    }

    private void delayed(Feature feature, int maxDelay) {
        rules.put(feature, new Rule(Mode.DELAYED, DEFAULT_EPSILON, maxDelay));
    }

    // Accumulated fire power from opponent bullets between scans
    // Perspective A: opponent is robot index 1; Perspective B: opponent is index 0
    private double firePowerAccumA;
    private double firePowerAccumB;
    private int fireCountA;
    private int fireCountB;

    // Track known bullet IDs to detect new bullets (FIRED state not in snapshots)
    private final Set<Integer> knownBulletIds = new HashSet<>();

    // --- Validation (called each tick) ---

    /**
     * Validate both perspectives, but only on ticks where a fresh scan occurred.
     * Between scans the whiteboard intentionally holds stale data — comparing
     * it against current reality would penalize normal radar gaps.
     * <p>
     * Tracks opponent FIRED bullets each tick to provide ground truth for
     * OPPONENT_FIRE_POWER validation on scan ticks.
     */
    public void validate(Whiteboard wbA, IRobotSnapshot robotA,
            Whiteboard wbB, IRobotSnapshot robotB, ITurnSnapshot turn) {
        // Track FIRED bullets from each perspective's opponent
        trackFiredBullets(turn);

        double tickA = wbA.getFeature(Feature.TICK);
        double lastScanA = wbA.getFeature(Feature.LAST_SCAN_TICK);
        if (tickA == lastScanA) {
            validatePerspective(wbA, robotA, robotB, statsA, firePowerAccumA, fireCountA);
            firePowerAccumA = 0;
            fireCountA = 0;
        }

        double tickB = wbB.getFeature(Feature.TICK);
        double lastScanB = wbB.getFeature(Feature.LAST_SCAN_TICK);
        if (tickB == lastScanB) {
            validatePerspective(wbB, robotB, robotA, statsB, firePowerAccumB, fireCountB);
            firePowerAccumB = 0;
            fireCountB = 0;
        }
    }

    private void trackFiredBullets(ITurnSnapshot turn) {
        IBulletSnapshot[] bullets = turn.getBullets();
        if (bullets == null)
            return;
        // Detect newly appeared bullets (FIRED state is not exposed in snapshots,
        // so we track bullet IDs and count first appearances as fire events)
        for (IBulletSnapshot bullet : bullets) {
            if (bullet.getState() == BulletState.INACTIVE)
                continue;
            int id = bullet.getBulletId();
            if (!knownBulletIds.add(id))
                continue;
            // New bullet — this is a fire event
            int owner = bullet.getOwnerIndex();
            if (owner == 1) {
                firePowerAccumA += bullet.getPower();
                fireCountA++;
            } else if (owner == 0) {
                firePowerAccumB += bullet.getPower();
                fireCountB++;
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
        for (Map.Entry<Feature, Rule> entry : rules.entrySet()) {
            Feature feature = entry.getKey();
            Rule rule = entry.getValue();

            double estimated = wb.getFeature(feature);
            double truth = getGroundTruth(feature, self, opponent,
                    opponentFirePower, opponentFireCount);

            if (Double.isNaN(estimated) || Double.isNaN(truth))
                continue;

            Stats s = stats.computeIfAbsent(feature, k -> new Stats());
            s.checks++;

            boolean match;
            switch (rule.mode) {
                case DELAYED:
                    rule.recordTruth(truth);
                    match = matchesHistory(estimated, rule);
                    break;
                case MARGIN:
                    match = Math.abs(estimated - truth) <= rule.tolerance;
                    break;
                default: // EXACT
                    match = Math.abs(estimated - truth) <= rule.tolerance;
                    break;
            }

            if (match) {
                s.hits++;
            } else {
                s.misses++;
                s.totalError += Math.abs(estimated - truth);
            }
        }
    }

    /**
     * Map a whiteboard opponent-feature to the actual engine value.
     */
    private static double getGroundTruth(Feature feature,
            IRobotSnapshot self, IRobotSnapshot opponent,
            double opponentFirePower, int opponentFireCount) {
        switch (feature) {
            case OPPONENT_ENERGY:
                return opponent.getEnergy();
            case OPPONENT_HEADING:
                return opponent.getBodyHeading();
            case OPPONENT_VELOCITY:
                return opponent.getVelocity();
            case DISTANCE:
                return Math.hypot(
                        opponent.getX() - self.getX(),
                        opponent.getY() - self.getY());
            case BEARING_RADIANS:
                double dx = opponent.getX() - self.getX();
                double dy = opponent.getY() - self.getY();
                double absoluteBearing = Math.atan2(dx, dy);
                return RoboMath.normalRelativeAngle(absoluteBearing - self.getBodyHeading());
            case OPPONENT_FIRE_POWER:
                // Ground truth from FIRED bullets between scans.
                // Only valid when exactly one bullet fired (multiple fires
                // cause ambiguous energy-drop detection).
                if (opponentFireCount == 1) {
                    return opponentFirePower;
                }
                return Double.NaN;
            default:
                return Double.NaN;
        }
    }

    private static boolean matchesHistory(double estimated, Rule rule) {
        for (double v : rule.history) {
            if (!Double.isNaN(v) && Math.abs(estimated - v) <= rule.tolerance) {
                return true;
            }
        }
        return false;
    }

    // --- Reporting ---

    /** Get accuracy (0.0–1.0) for a feature across both perspectives. */
    public double getAccuracy(Feature feature) {
        long totalChecks = 0;
        long totalHits = 0;
        Stats sa = statsA.get(feature);
        Stats sb = statsB.get(feature);
        if (sa != null) {
            totalChecks += sa.checks;
            totalHits += sa.hits;
        }
        if (sb != null) {
            totalChecks += sb.checks;
            totalHits += sb.hits;
        }
        return totalChecks == 0 ? 1.0 : (double) totalHits / totalChecks;
    }

    /** Total checks performed across all features, both perspectives. */
    public long getTotalChecks() {
        long total = 0;
        for (Stats s : statsA.values())
            total += s.checks;
        for (Stats s : statsB.values())
            total += s.checks;
        return total;
    }

    /** Total hits (matches) across all features, both perspectives. */
    public long getTotalHits() {
        long total = 0;
        for (Stats s : statsA.values())
            total += s.hits;
        for (Stats s : statsB.values())
            total += s.hits;
        return total;
    }

    /** Print a summary table to stdout. */
    public void printSummary() {
        System.out.println("=== GOD VIEW VALIDATION ===");
        System.out.println(String.format("%-30s %8s %8s %8s %10s",
                "Feature", "Checks", "Hits", "Misses", "Accuracy"));

        for (Feature feature : rules.keySet()) {
            Stats sa = statsA.getOrDefault(feature, Stats.EMPTY);
            Stats sb = statsB.getOrDefault(feature, Stats.EMPTY);
            long checks = sa.checks + sb.checks;
            long hits = sa.hits + sb.hits;
            long misses = sa.misses + sb.misses;
            double accuracy = checks == 0 ? 1.0 : (double) hits / checks;

            System.out.println(String.format("%-30s %8d %8d %8d %9.1f%%",
                    feature.name(), checks, hits, misses, accuracy * 100));
        }

        long totalChecks = getTotalChecks();
        long totalHits = getTotalHits();
        double overall = totalChecks == 0 ? 1.0 : (double) totalHits / totalChecks;
        System.out.println(String.format("%-30s %8d %8d %8d %9.1f%%",
                "TOTAL", totalChecks, totalHits, totalChecks - totalHits, overall * 100));
        System.out.println();
    }

    // --- Internal types ---

    private static final class Rule {
        final Mode mode;
        final double tolerance;
        final int maxDelay;
        final Deque<Double> history;

        Rule(Mode mode, double tolerance, int maxDelay) {
            this.mode = mode;
            this.tolerance = tolerance;
            this.maxDelay = maxDelay;
            this.history = (mode == Mode.DELAYED)
                    ? new ArrayDeque<>(HISTORY_CAPACITY)
                    : null;
        }

        void recordTruth(double value) {
            if (history == null)
                return;
            if (history.size() >= HISTORY_CAPACITY)
                history.pollFirst();
            history.addLast(value);
        }
    }

    private static final class Stats {
        static final Stats EMPTY = new Stats();
        long checks;
        long hits;
        long misses;
        double totalError;
    }
}

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

    private final double bfWidth;
    private final double bfHeight;

    // Per-perspective state indexed by robotIndex (0 or 1)
    private final PerspectiveState[] state = { new PerspectiveState(), new PerspectiveState() };

    // Track known bullet IDs to detect new bullets (FIRED state not in snapshots)
    private final Set<Integer> knownBulletIds = new HashSet<>();

    public GodViewValidator(double bfWidth, double bfHeight) {
        this.bfWidth = bfWidth;
        this.bfHeight = bfHeight;
    }

    /**
     * Reset per-round state. Call at the start of each new round.
     */
    public void resetRound() {
        for (PerspectiveState ps : state) {
            ps.firePowerAccum = 0;
            ps.fireCount = 0;
            ps.fireTick = -1;
            ps.fireX = Double.NaN;
            ps.fireY = Double.NaN;
            ps.fireOurX = Double.NaN;
            ps.fireOurY = Double.NaN;
            ps.prevScanOpponentEnergy = Double.NaN;
            ps.prevScanInitialized = false;
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
        trackFiredBullets(turn, perspectives, robots);

        long currentTick = (long) turn.getTurn();

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

                IRobotSnapshot self = robots[us.robotIndex()];
                IRobotSnapshot opponent = robots[us.peer().robotIndex()];

                // Build truth context
                TruthContext ctx = new TruthContext();
                ctx.self = self;
                ctx.opponent = opponent;
                ctx.opponentFirePower = ps.firePowerAccum;
                ctx.opponentFireCount = ps.fireCount;
                ctx.currentTick = currentTick;
                ctx.bfWidth = bfWidth;
                ctx.bfHeight = bfHeight;
                ctx.fireTick = ps.fireTick;
                ctx.fireX = ps.fireX;
                ctx.fireY = ps.fireY;
                ctx.fireOurX = ps.fireOurX;
                ctx.fireOurY = ps.fireOurY;
                ctx.prevScanOpponentEnergy = ps.prevScanOpponentEnergy;
                ctx.prevScanInitialized = ps.prevScanInitialized;
                // Compute opponent ID hash
                String oppName = opponent.getShortName();
                int sp = oppName.indexOf(' ');
                String botId = (sp < 0) ? oppName : oppName.substring(0, sp);
                ctx.opponentIdHash = RoboMath.fnv1a32(botId);

                validatePerspective(us.wb(), ps.stats, ctx);

                // Update prev scan state for next scan
                ps.prevScanOpponentEnergy = opponent.getEnergy();
                ps.prevScanInitialized = true;

                // Reset fire accumulators for next inter-scan period
                ps.firePowerAccum = 0;
                ps.fireCount = 0;
                ps.fireTick = -1;
                ps.fireX = Double.NaN;
                ps.fireY = Double.NaN;
                ps.fireOurX = Double.NaN;
                ps.fireOurY = Double.NaN;
            }
        }
    }

    private void trackFiredBullets(ITurnSnapshot turn, Perspective[] perspectives,
            IRobotSnapshot[] robots) {
        IBulletSnapshot[] bullets = turn.getBullets();
        if (bullets == null)
            return;
        long currentTick = (long) turn.getTurn();
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
                    // Record fire-time data (overwrite each time; only valid when count==1)
                    ps.fireTick = currentTick;
                    ps.fireX = robots[owner].getX();
                    ps.fireY = robots[owner].getY();
                    ps.fireOurX = robots[us.robotIndex()].getX();
                    ps.fireOurY = robots[us.robotIndex()].getY();
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

    private void validatePerspective(Whiteboard wb, Map<Feature, Stats> stats,
            TruthContext ctx) {
        for (Feature feature : Feature.values()) {
            double truth = getGroundTruth(feature, ctx);

            // Feature not validatable this tick (explicit from getGroundTruth)
            if (Double.isNaN(truth))
                continue;

            double estimated = wb.getFeature(feature);
            Stats s = stats.computeIfAbsent(feature, k -> new Stats());
            s.checks++;

            if (Double.isNaN(estimated)) {
                // Whiteboard failed to produce a value — count as miss
                s.misses++;
            } else if (Math.abs(estimated - truth) <= EPSILON) {
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
    private static double getGroundTruth(Feature feature, TruthContext ctx) {
        switch (feature) {
            // --- Timing / battlefield (always exact) ---
            case TICK:
                return ctx.currentTick;
            case BATTLEFIELD_WIDTH:
                return ctx.bfWidth;
            case BATTLEFIELD_HEIGHT:
                return ctx.bfHeight;
            case LAST_SCAN_TICK:
                // We only validate on scan ticks, so LAST_SCAN_TICK == current tick
                return ctx.currentTick;
            case TICKS_SINCE_SCAN:
                // We only validate on scan ticks, so this is always 0
                return 0;

            // --- Our state (always exact from snapshot) ---
            case OUR_X:
                return ctx.self.getX();
            case OUR_Y:
                return ctx.self.getY();
            case OUR_HEADING:
                return ctx.self.getBodyHeading();
            case OUR_VELOCITY:
                return ctx.self.getVelocity();
            case OUR_ENERGY:
                return ctx.self.getEnergy();
            case GUN_HEAT:
                return ctx.self.getGunHeat();
            case GUN_HEADING:
                return ctx.self.getGunHeading();
            case RADAR_HEADING:
                return ctx.self.getRadarHeading();

            // --- Opponent state (exact on scan ticks) ---
            case OPPONENT_ENERGY:
                return ctx.opponent.getEnergy();
            case OPPONENT_HEADING:
                return ctx.opponent.getBodyHeading();
            case OPPONENT_VELOCITY:
                return ctx.opponent.getVelocity();

            // --- Computed spatial (exact on scan ticks) ---
            case DISTANCE:
                return Math.hypot(
                        ctx.opponent.getX() - ctx.self.getX(),
                        ctx.opponent.getY() - ctx.self.getY());
            case BEARING_RADIANS: {
                double dx = ctx.opponent.getX() - ctx.self.getX();
                double dy = ctx.opponent.getY() - ctx.self.getY();
                double absoluteBearing = Math.atan2(dx, dy);
                return RoboMath.normalRelativeAngle(absoluteBearing - ctx.self.getBodyHeading());
            }
            case OPPONENT_BEARING_ABSOLUTE: {
                double dx = ctx.opponent.getX() - ctx.self.getX();
                double dy = ctx.opponent.getY() - ctx.self.getY();
                double heading = ctx.self.getBodyHeading();
                double relBearing = RoboMath.normalRelativeAngle(
                        Math.atan2(dx, dy) - heading);
                return heading + relBearing;
            }
            case OPPONENT_X:
                return ctx.opponent.getX();
            case OPPONENT_Y:
                return ctx.opponent.getY();
            case OPPONENT_LATERAL_VELOCITY: {
                double dx = ctx.opponent.getX() - ctx.self.getX();
                double dy = ctx.opponent.getY() - ctx.self.getY();
                double absBearing = Math.atan2(dx, dy);
                // Pipeline uses bearing-from-opponent (absBearing + PI)
                double relHeading = ctx.opponent.getBodyHeading() - absBearing - Math.PI;
                return ctx.opponent.getVelocity() * Math.sin(relHeading);
            }
            case OPPONENT_ADVANCING_VELOCITY: {
                double dx = ctx.opponent.getX() - ctx.self.getX();
                double dy = ctx.opponent.getY() - ctx.self.getY();
                double absBearing = Math.atan2(dx, dy);
                // Pipeline uses bearing-from-opponent (absBearing + PI)
                double relHeading = ctx.opponent.getBodyHeading() - absBearing - Math.PI;
                return ctx.opponent.getVelocity() * Math.cos(relHeading);
            }

            // --- Previous scan opponent energy ---
            case PREV_SCAN_OPPONENT_ENERGY:
                // After wb.process(), FireFeatures writes current opponent energy here.
                return ctx.opponent.getEnergy();

            // --- Opponent identity ---
            case OPPONENT_ID_HASH:
                return ctx.opponentIdHash;

            // --- Fire detection ---
            // Fire-time features measure estimation quality (scan-time approximation
            // vs actual fire-time positions). Not validated here because:
            //   - TheirWaveTracker is not registered in pipeline (no fire-time staging)
            //   - Energy-drop detection has inherent 1-tick delay
            //   - Scan-time positions differ from actual fire-time positions
            case THEIR_FIRE_POWER:
            case THEIR_FIRE_TICK:
            case THEIR_FIRE_X:
            case THEIR_FIRE_Y:
            case THEIR_FIRE_OUR_X:
            case THEIR_FIRE_OUR_Y:
            case THEIR_BULLET_SPEED:
            case THEIR_FIRE_BEARING:
            case THEIR_FIRE_DISTANCE:
                return Double.NaN;

            // --- Not validatable from snapshots (explicit NaN) ---
            case OUR_BULLET_DAMAGE_TO_OPPONENT:
            case OPPONENT_BULLET_ENERGY_GAIN:
            case RAM_DAMAGE_TO_OPPONENT:
            case OPPONENT_RAM_ENERGY_GAIN:
            case OPPONENT_WALL_HIT_DAMAGE:
            case GUN_AIM_POWER:
            case GUN_AIM_ANGLE:
            case GUN_AIM_GF:
            case OPPONENT_ID:
            case THEIR_BREAK_TICK:
            case THEIR_BREAK_OUR_X:
            case THEIR_BREAK_OUR_Y:
            case THEIR_BREAK_GF:
            case THEIR_BREAK_BEARING_OFFSET:
            case THEIR_HIT_US:
            case OUR_FIRE_DISTANCE:
            case OUR_FIRE_LATERAL_VELOCITY:
            case OUR_FIRE_ADVANCING_VELOCITY:
            case OUR_FIRE_BULLET_SPEED:
            case OUR_FIRE_MEA:
            case OUR_FIRE_DIRECTION:
            case OUR_FIRE_BEARING_ABSOLUTE:
            case OUR_FIRE_X:
            case OUR_FIRE_Y:
            case OUR_FIRE_OPPONENT_X:
            case OUR_FIRE_OPPONENT_Y:
            case OUR_FIRE_POWER:
            case OUR_FIRE_TICK:
            case OUR_FIRE_BULLET_ID:
            case OUR_FIRE_AIM_GF:
            case OUR_FIRE_IS_REAL:
            case OUR_BREAK_TICK:
            case OUR_BREAK_GF:
            case OUR_BREAK_BEARING_OFFSET:
            case OUR_BREAK_OPPONENT_X:
            case OUR_BREAK_OPPONENT_Y:
            case OUR_BREAK_HIT:
            case ROUND_HIT_RATE:
            case ROUND_RESULT:
                return Double.NaN;

            default:
                throw new IllegalArgumentException("Unhandled feature: " + feature);
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

    private static final class TruthContext {
        IRobotSnapshot self;
        IRobotSnapshot opponent;
        double opponentFirePower;
        int opponentFireCount;
        long currentTick;
        double bfWidth, bfHeight;
        // Fire-time data (valid when opponentFireCount == 1)
        long fireTick;
        double fireX, fireY;
        double fireOurX, fireOurY;
        // Previous scan opponent energy
        double prevScanOpponentEnergy;
        boolean prevScanInitialized;
        // Opponent identity hash
        int opponentIdHash;
    }

    private static final class PerspectiveState {
        double firePowerAccum;
        int fireCount;
        // Fire-time tracking (positions when bullet first appeared)
        long fireTick = -1;
        double fireX = Double.NaN;
        double fireY = Double.NaN;
        double fireOurX = Double.NaN;
        double fireOurY = Double.NaN;
        // Previous scan opponent energy for PREV_SCAN_OPPONENT_ENERGY validation
        double prevScanOpponentEnergy = Double.NaN;
        boolean prevScanInitialized;
        final Map<Feature, Stats> stats = new EnumMap<>(Feature.class);
    }

    private static final class Stats {
        static final Stats EMPTY = new Stats();
        long checks;
        long hits;
        long misses;
    }
}

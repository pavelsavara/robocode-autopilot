package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.Whiteboard;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * God-view quality validator — measures the precision gap between the robot's
 * partial-information estimates and god-view (engine ground-truth) reality.
 * <p>
 * It reads a <b>separate god-view whiteboard</b> (seeded from the observer's
 * robot-side whiteboard each tick, then overlaid by {@link GodViewWaveResolver})
 * and never mutates the observer's robot-side state. Layer 0 fidelity
 * (in-game robot vs observer) is validated independently by
 * {@link Layer0DebugFidelityValidator}.
 *
 * <h2>4 Quality Layers</h2>
 * <ol>
 * <li>Spatial &amp; Kinematic Fidelity (every scan tick)</li>
 * <li>Fire Detection Fidelity</li>
 * <li>Wave Resolution &amp; GF Precision</li>
 * <li>Energy Accounting</li>
 * </ol>
 */
public final class GodViewQualityValidator {

    private static final double EPSILON = 1e-4;

    private final double bfWidth;
    private final double bfHeight;

    // --- Layer 1: Spatial (per-feature stats) ---
    private final EnumMap<Feature, ValidationStats> spatialStats = new EnumMap<>(Feature.class);

    // --- Layer 2: Fire Detection ---
    private final FireDetectionTracker[] fireTracking = {
            new FireDetectionTracker(), new FireDetectionTracker()
    };

    // --- Layer 3: Wave GF Precision ---
    private final WavePrecisionTracker[] waveTracking = {
            new WavePrecisionTracker(), new WavePrecisionTracker()
    };

    // --- Layer 4: Energy Accounting ---
    private final int[] energyChecks = { 0, 0 };
    private final int[] energyDiscrepancies = { 0, 0 };
    private final double[] prevEnergy = { Double.NaN, Double.NaN };
    private final double[] prevVelocity = { Double.NaN, Double.NaN };

    public GodViewQualityValidator(double bfWidth, double bfHeight) {
        this.bfWidth = bfWidth;
        this.bfHeight = bfHeight;
    }

    // ========== Inner Types ==========

    private static final class ValidationStats {
        int checks;
        int mismatches;
    }

    private static final class FireRecord {
        final double power;
        final double x;
        final double y;
        final double heading;
        final long tick;

        FireRecord(double power, double x, double y, double heading, long tick) {
            this.power = power;
            this.x = x;
            this.y = y;
            this.heading = heading;
            this.tick = tick;
        }
    }

    private static final class FireDetectionTracker {
        final List<FireRecord> godViewFires = new ArrayList<>();
        final List<FireRecord> robotSideFires = new ArrayList<>();
        double positionErrorSum;
        double powerErrorSum;
        double latencySum;
        int pairedCount;

        double getFireDetectionRate() {
            if (godViewFires.isEmpty())
                return Double.NaN;
            return (double) robotSideFires.size() / godViewFires.size();
        }

        double getPositionMAE() {
            return pairedCount > 0 ? positionErrorSum / pairedCount : Double.NaN;
        }

        double getPowerMAE() {
            return pairedCount > 0 ? powerErrorSum / pairedCount : Double.NaN;
        }

        double getDetectionLatency() {
            return pairedCount > 0 ? latencySum / pairedCount : Double.NaN;
        }
    }

    private static final class WavePrecisionTracker {
        double gfErrorSum;
        double gfMaxError;
        int gfComparisonCount;
        double breakTickErrorSum;
        int breakTickComparisonCount;
        int godViewResolutions;
        int robotSideResolutions;

        double getWaveMatchRate() {
            if (godViewResolutions == 0)
                return Double.NaN;
            return (double) robotSideResolutions / godViewResolutions;
        }

        double getBreakTickMAE() {
            return breakTickComparisonCount > 0
                    ? breakTickErrorSum / breakTickComparisonCount
                    : Double.NaN;
        }
    }

    // ========== Layer 1: Spatial & Kinematic Fidelity ==========

    /**
     * Validate spatial/kinematic features.
     * Self and static features are checked every tick.
     * Opponent-dependent features are only checked on scan ticks.
     */
    public void validateSpatial(int perspIndex, Whiteboard wb,
            IRobotSnapshot self, IRobotSnapshot opponent, ITurnSnapshot turn) {
        double tick = wb.getFeature(Feature.TICK);
        if (Double.isNaN(tick)) {
            throw new IllegalStateException("TICK must be set before validateSpatial is called");
        }

        // Skip if observer didn't process this turn (robot is dead)
        if (Math.abs(tick - turn.getTurn()) > EPSILON) {
            return;
        }

        // Self features (available every tick from StatusEvent)
        checkSpatialFeature(wb, Feature.OUR_X, self.getX());
        checkSpatialFeature(wb, Feature.OUR_Y, self.getY());
        checkSpatialFeature(wb, Feature.OUR_HEADING, self.getBodyHeading());
        checkSpatialFeature(wb, Feature.OUR_VELOCITY, self.getVelocity());
        checkSpatialFeature(wb, Feature.OUR_ENERGY, self.getEnergy());
        checkSpatialFeature(wb, Feature.GUN_HEADING, self.getGunHeading());
        checkSpatialFeature(wb, Feature.RADAR_HEADING, self.getRadarHeading());
        checkSpatialFeature(wb, Feature.GUN_HEAT, self.getGunHeat());

        // Static features
        checkSpatialFeature(wb, Feature.BATTLEFIELD_WIDTH, bfWidth);
        checkSpatialFeature(wb, Feature.BATTLEFIELD_HEIGHT, bfHeight);

        // Timing (always available)
        checkSpatialFeature(wb, Feature.TICK, turn.getTurn());

        // Opponent features (only valid on scan ticks — whiteboard is stale otherwise)
        double lastScanTick = wb.getFeature(Feature.LAST_SCAN_TICK);
        if (Double.isNaN(lastScanTick) || Math.abs(tick - lastScanTick) > EPSILON) {
            return; // not a scan tick — skip opponent features
        }

        checkSpatialFeature(wb, Feature.OPPONENT_X, opponent.getX());
        checkSpatialFeature(wb, Feature.OPPONENT_Y, opponent.getY());
        checkSpatialFeature(wb, Feature.OPPONENT_HEADING, opponent.getBodyHeading());
        checkSpatialFeature(wb, Feature.OPPONENT_VELOCITY, opponent.getVelocity());
        checkSpatialFeature(wb, Feature.OPPONENT_ENERGY, opponent.getEnergy());

        // Derived features (depend on opponent position)
        double dx = opponent.getX() - self.getX();
        double dy = opponent.getY() - self.getY();
        double expectedDistance = Math.hypot(dx, dy);
        checkSpatialFeature(wb, Feature.DISTANCE, expectedDistance);

        double absBearing = Math.atan2(dx, dy);
        double expectedBearing = RoboMath.normalRelativeAngle(absBearing - self.getBodyHeading());
        checkSpatialFeature(wb, Feature.BEARING_RADIANS, expectedBearing);

        double oppVel = opponent.getVelocity();
        double oppHeading = opponent.getBodyHeading();
        double expectedLateral = oppVel * Math.sin(oppHeading - absBearing - Math.PI);
        double expectedAdvancing = oppVel * Math.cos(oppHeading - absBearing - Math.PI);
        checkSpatialFeature(wb, Feature.OPPONENT_LATERAL_VELOCITY, expectedLateral);
        checkSpatialFeature(wb, Feature.OPPONENT_ADVANCING_VELOCITY, expectedAdvancing);

        // Scan timing (only meaningful on scan ticks)
        checkSpatialFeature(wb, Feature.LAST_SCAN_TICK, turn.getTurn());
        checkSpatialFeature(wb, Feature.TICKS_SINCE_SCAN, 0.0);
    }

    private void checkSpatialFeature(Whiteboard wb, Feature feature, double expected) {
        ValidationStats stats = spatialStats.computeIfAbsent(feature, k -> new ValidationStats());
        stats.checks++;
        double actual = wb.getFeature(feature);
        if (Double.isNaN(actual) && Double.isNaN(expected)) {
            return; // both NaN = match
        }
        if (Double.isNaN(actual) || Double.isNaN(expected) || Math.abs(actual - expected) > EPSILON) {
            stats.mismatches++;
            if (stats.mismatches <= 3) {
                System.out.printf("AGENT_DEBUG spatial mismatch: %s actual=%.6f expected=%.6f tick=%.0f%n",
                        feature, actual, expected, wb.getFeature(Feature.TICK));
            }
        }
    }

    // ========== Layer 2: Fire Detection ==========

    /**
     * Track god-view fire (from IBulletSnapshot FIRED state).
     */
    public void recordGodViewFire(int perspIndex, double power, double x, double y,
            double heading, long tick) {
        fireTracking[perspIndex].godViewFires.add(new FireRecord(power, x, y, heading, tick));
    }

    /**
     * Track robot-side fire detection (from energy drop).
     * Pairs with oldest unmatched god-view fire (FIFO) and computes errors
     * immediately.
     */
    public void recordRobotSideFire(int perspIndex, double power, double x, double y, long tick) {
        FireDetectionTracker tracker = fireTracking[perspIndex];
        FireRecord record = new FireRecord(power, x, y, Double.NaN, tick);
        tracker.robotSideFires.add(record);

        // FIFO pairing: try to pair with next unmatched god-view fire
        int pairedSoFar = tracker.pairedCount;
        if (pairedSoFar < tracker.godViewFires.size()) {
            FireRecord godView = tracker.godViewFires.get(pairedSoFar);
            double dx = godView.x - x;
            double dy = godView.y - y;
            tracker.positionErrorSum += Math.hypot(dx, dy);
            tracker.powerErrorSum += Math.abs(godView.power - power);
            tracker.latencySum += (tick - godView.tick);
            tracker.pairedCount++;
        }
    }

    // ========== Layer 3: Wave GF Precision ==========

    /**
     * Compare wave resolutions when both sides resolve.
     */
    public void compareWaveBreak(int perspIndex, double godViewGF, double robotSideGF,
            long godViewBreakTick, long robotSideBreakTick) {
        WavePrecisionTracker tracker = waveTracking[perspIndex];
        double error = Math.abs(godViewGF - robotSideGF);
        tracker.gfErrorSum += error;
        tracker.gfMaxError = Math.max(tracker.gfMaxError, error);
        tracker.gfComparisonCount++;
        tracker.breakTickErrorSum += Math.abs(godViewBreakTick - robotSideBreakTick);
        tracker.breakTickComparisonCount++;
    }

    /**
     * Record a god-view wave resolution (even if no robot-side match).
     */
    public void recordGodViewWaveResolution(int perspIndex) {
        waveTracking[perspIndex].godViewResolutions++;
    }

    /**
     * Record a robot-side wave resolution (even if no god-view match).
     */
    public void recordRobotSideWaveResolution(int perspIndex) {
        waveTracking[perspIndex].robotSideResolutions++;
    }

    // ========== Layer 4: Energy Accounting ==========

    /**
     * Track energy changes against engine rules.
     * On first tick per perspective, initializes prevEnergy without checking.
     */
    public void accountEnergy(int perspIndex, IRobotSnapshot[] robots,
            IBulletSnapshot[] bullets) {
        IRobotSnapshot self = robots[perspIndex];
        double currentEnergy = self.getEnergy();

        if (Double.isNaN(prevEnergy[perspIndex])) {
            prevEnergy[perspIndex] = currentEnergy;
            prevVelocity[perspIndex] = self.getVelocity();
            return;
        }

        double expected = prevEnergy[perspIndex];

        // Subtract fire power (bullets owned by us that are FIRED this tick)
        if (bullets != null) {
            for (IBulletSnapshot b : bullets) {
                if (b.getOwnerIndex() == perspIndex && b.getState() == BulletState.FIRED) {
                    expected -= b.getPower();
                }
            }
        }

        // Add hit energy bonus (our bullets that hit victim)
        if (bullets != null) {
            for (IBulletSnapshot b : bullets) {
                if (b.getOwnerIndex() == perspIndex && b.getState() == BulletState.HIT_VICTIM) {
                    expected += hitEnergyBonus(b.getPower());
                }
            }
        }

        // Subtract bullet damage received (opponent bullets hitting us)
        int opponentIndex = 1 - perspIndex;
        if (bullets != null) {
            for (IBulletSnapshot b : bullets) {
                if (b.getOwnerIndex() == opponentIndex && b.getState() == BulletState.HIT_VICTIM
                        && b.getVictimIndex() == perspIndex) {
                    expected -= bulletDamage(b.getPower());
                }
            }
        }

        // Wall hit damage (use previous tick velocity — snapshot velocity is
        // post-impact)
        if (self.getState() == RobotState.HIT_WALL && !Double.isNaN(prevVelocity[perspIndex])) {
            expected -= wallDamage(prevVelocity[perspIndex]);
        }

        // Ram damage
        if (self.getState() == RobotState.HIT_ROBOT) {
            expected -= RAM_DAMAGE;
        }

        energyChecks[perspIndex]++;
        if (Math.abs(expected - currentEnergy) > EPSILON) {
            energyDiscrepancies[perspIndex]++;
        }

        prevEnergy[perspIndex] = currentEnergy;
        prevVelocity[perspIndex] = self.getVelocity();
    }

    // ========== Round Reset ==========

    /** Reset per-round state. Counters are cumulative across rounds. */
    public void resetRound() {
        prevEnergy[0] = Double.NaN;
        prevEnergy[1] = Double.NaN;
        prevVelocity[0] = Double.NaN;
        prevVelocity[1] = Double.NaN;
    }

    // ========== Non-Vacuous Assertion ==========

    /**
     * Asserts all required layers have performed at least one check.
     * Layer 5 is optional (only when Autopilot is fighting).
     *
     * @throws IllegalStateException if any required layer has 0 checks
     */
    public void assertNonVacuous() {
        if (getSpatialChecks() == 0) {
            throw new IllegalStateException("Layer 1 vacuous: 0 spatial checks performed");
        }
        int totalGodViewFires = fireTracking[0].godViewFires.size() + fireTracking[1].godViewFires.size();
        if (totalGodViewFires == 0) {
            throw new IllegalStateException("Layer 2 vacuous: 0 god-view fires detected");
        }
        // Layer 3 GF comparisons require matched wave resolution between observer and
        // live robot. Since the observer fires independently (different timing),
        // matched
        // comparisons may not occur. Not a vacuous-test concern.
        if (energyChecks[0] + energyChecks[1] == 0) {
            throw new IllegalStateException("Layer 4 vacuous: 0 energy checks performed");
        }
    }

    // ========== Getters ==========

    public int getSpatialMismatches() {
        int total = 0;
        for (ValidationStats s : spatialStats.values())
            total += s.mismatches;
        return total;
    }

    public int getSpatialChecks() {
        int total = 0;
        for (ValidationStats s : spatialStats.values())
            total += s.checks;
        return total;
    }

    public int getSpatialMismatches(Feature feature) {
        ValidationStats s = spatialStats.get(feature);
        return s != null ? s.mismatches : 0;
    }

    public int getSpatialChecks(Feature feature) {
        ValidationStats s = spatialStats.get(feature);
        return s != null ? s.checks : 0;
    }

    public double getFireDetectionRate(int perspIndex) {
        return fireTracking[perspIndex].getFireDetectionRate();
    }

    public int getGodViewFires(int perspIndex) {
        return fireTracking[perspIndex].godViewFires.size();
    }

    public int getRobotSideFires(int perspIndex) {
        return fireTracking[perspIndex].robotSideFires.size();
    }

    public double getFirePositionMAE(int perspIndex) {
        return fireTracking[perspIndex].getPositionMAE();
    }

    public double getFirePowerMAE(int perspIndex) {
        return fireTracking[perspIndex].getPowerMAE();
    }

    public double getFireDetectionLatency(int perspIndex) {
        return fireTracking[perspIndex].getDetectionLatency();
    }

    public double getGfMeanAbsoluteError(int perspIndex) {
        WavePrecisionTracker t = waveTracking[perspIndex];
        return t.gfComparisonCount > 0 ? t.gfErrorSum / t.gfComparisonCount : Double.NaN;
    }

    public double getGfMaxError(int perspIndex) {
        WavePrecisionTracker t = waveTracking[perspIndex];
        return t.gfComparisonCount > 0 ? t.gfMaxError : Double.NaN;
    }

    public int getGfComparisonCount(int perspIndex) {
        return waveTracking[perspIndex].gfComparisonCount;
    }

    public double getWaveMatchRate(int perspIndex) {
        return waveTracking[perspIndex].getWaveMatchRate();
    }

    public int getGodViewWaveResolutions(int perspIndex) {
        return waveTracking[perspIndex].godViewResolutions;
    }

    public int getRobotSideWaveResolutions(int perspIndex) {
        return waveTracking[perspIndex].robotSideResolutions;
    }

    public double getBreakTickMAE(int perspIndex) {
        return waveTracking[perspIndex].getBreakTickMAE();
    }

    public int getEnergyDiscrepancies(int perspIndex) {
        return energyDiscrepancies[perspIndex];
    }

    public int getEnergyChecks(int perspIndex) {
        return energyChecks[perspIndex];
    }

    // ========== Summary ==========

    public void printSummary() {
        System.out.println("=== GodViewQualityValidator Summary ===");
        System.out.println();

        // Layer 1
        System.out.printf("Layer 1 — Spatial Fidelity:%n");
        System.out.printf("  Checks: %d, Mismatches: %d%n", getSpatialChecks(), getSpatialMismatches());
        for (var entry : spatialStats.entrySet()) {
            if (entry.getValue().mismatches > 0) {
                System.out.printf("    %s: checks=%d, mismatches=%d%n",
                        entry.getKey(), entry.getValue().checks, entry.getValue().mismatches);
            }
        }
        System.out.println();

        // Layer 2
        System.out.printf("Layer 2 — Fire Detection:%n");
        for (int pi = 0; pi < 2; pi++) {
            FireDetectionTracker t = fireTracking[pi];
            double rate = t.getFireDetectionRate();
            double posMAE = t.getPositionMAE();
            double powMAE = t.getPowerMAE();
            double latency = t.getDetectionLatency();
            System.out.printf("  Perspective %d: godView=%d, robotSide=%d, rate=%s%n",
                    pi, t.godViewFires.size(), t.robotSideFires.size(),
                    formatMetric(rate, "%.3f"));
            System.out.printf("    positionMAE=%s, powerMAE=%s, latency=%s%n",
                    formatMetric(posMAE, "%.4f"),
                    formatMetric(powMAE, "%.4f"),
                    formatMetric(latency, "%.2f"));
        }
        System.out.println();

        // Layer 3
        System.out.printf("Layer 3 — GF Precision:%n");
        for (int pi = 0; pi < 2; pi++) {
            WavePrecisionTracker t = waveTracking[pi];
            double mae = getGfMeanAbsoluteError(pi);
            double maxErr = getGfMaxError(pi);
            double matchRate = t.getWaveMatchRate();
            double btMAE = t.getBreakTickMAE();
            System.out.printf("  Perspective %d: comparisons=%d, MAE=%s, maxError=%s%n",
                    pi, t.gfComparisonCount,
                    formatMetric(mae, "%.6f"),
                    formatMetric(maxErr, "%.6f"));
            System.out.printf("    waveMatchRate=%s (godView=%d, robotSide=%d), breakTickMAE=%s%n",
                    formatMetric(matchRate, "%.3f"),
                    t.godViewResolutions, t.robotSideResolutions,
                    formatMetric(btMAE, "%.2f"));
        }
        System.out.println();

        // Layer 4
        System.out.printf("Layer 4 — Energy Accounting:%n");
        for (int pi = 0; pi < 2; pi++) {
            System.out.printf("  Perspective %d: checks=%d, discrepancies=%d%n",
                    pi, energyChecks[pi], energyDiscrepancies[pi]);
        }
        System.out.println();

        System.out.println("=================================");
    }

    private static String formatMetric(double value, String format) {
        return Double.isNaN(value) ? "N/A" : String.format(format, value);
    }

    // ========== Engine Damage Formulas ==========

    static double bulletDamage(double power) {
        return 4 * power + Math.max(0, 2 * (power - 1));
    }

    static double hitEnergyBonus(double power) {
        return 3 * power;
    }

    static double gunHeatFromFire(double power) {
        return 1 + power / 5.0;
    }

    static double wallDamage(double velocity) {
        return Math.max(Math.abs(velocity) / 2.0 - 1, 0);
    }

    static final double RAM_DAMAGE = 0.6;
}

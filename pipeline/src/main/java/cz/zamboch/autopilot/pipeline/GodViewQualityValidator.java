package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.Whiteboard;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * God-view quality validator — measures the precision gap between the robot's
 * partial-information estimates and god-view (engine ground-truth) reality.
 * <p>
 * It reads a <b>separate god-view whiteboard</b> (seeded from the observer's
 * robot-side whiteboard each tick, then overlaid by
 * {@link GodViewWaveResolver})
 * and never mutates the observer's robot-side state. Layer 0 fidelity
 * (in-game robot vs observer) is validated independently by
 * {@link Layer0DebugFidelityValidator}.
 *
 * <h2>4 Quality Layers</h2>
 * <ol>
 * <li>Spatial &amp; Kinematic Fidelity (every scan tick)</li>
 * <li>Energy Accounting (ground-truth ledger)</li>
 * <li>Fire Detection Fidelity (depends on the energy ledger)</li>
 * <li>Wave Resolution &amp; GF Precision (depends on fire detection)</li>
 * </ol>
 */
public final class GodViewQualityValidator {

    private static final double EPSILON = 1e-4;

    // --- Layer 1: Spatial (per-feature stats) ---
    private final EnumMap<Feature, ValidationStats> spatialStats = new EnumMap<>(Feature.class);

    // --- Layer 3: Incoming-Fire Detection (autopilot side only) ---
    // god-view ground truth (the opponent's bullet) vs the autopilot's energy-drop
    // inference. Paired by fire tick because the blindfolded autopilot has no
    // bullet id for incoming fire. Origin/timing/power are knowable exactly; the
    // muzzle angle is not — angleMAE quantifies that single irreducible unknown.
    private final TheirFireDetectionTracker theirFireTracking = new TheirFireDetectionTracker();

    // --- Layer 4: Wave GF Precision ---
    private final WavePrecisionTracker[] waveTracking = {
            new WavePrecisionTracker(), new WavePrecisionTracker()
    };

    // --- Layer 2: Damage-Observation Drift (autopilot perspective only) ---
    // Compares the autopilot's running tally of opponent damage events
    // (subtracted out of the energy delta in FireFeatures.process) against the
    // god-view ledger of the same components. When all four sub-metrics are
    // zero, the autopilot has perfectly observed every legal source of
    // opponent energy change, so anything left over in the scan-to-scan drop
    // must be an enemy fire — and L3 phantoms collapse to 0.
    private final DamageObservationTracker damageObsTracking = new DamageObservationTracker();

    // --- Engine-rule self-test (internal sanity check; not headline metric) ---
    // Reconciles each robot's per-tick energy change against the engine's own
    // event stream (god-view bullets + collisions). This validates OUR model of
    // the engine, not the autopilot's observation quality.
    private final int[] energyChecks = { 0, 0 };
    private final int[] energyDiscrepancies = { 0, 0 };
    private final double[] prevEnergy = { Double.NaN, Double.NaN };
    private final double[] prevVelocity = { Double.NaN, Double.NaN };
    private final RobotState[] prevState = { null, null };
    // Bullet-id lifecycle tracking: snapshot states linger for several ticks
    // (explosion animation), so each energy event is applied exactly once per
    // bullet id. Ids are per-round sequential, hence cleared in resetRound().
    private final Set<Integer>[] firedBulletIds = newIdSets();
    private final Set<Integer>[] hitByUsBulletIds = newIdSets();
    private final Set<Integer>[] hitOnUsBulletIds = newIdSets();

    // Per-round bullet-id de-dup for damage-observation drift (autopilot view).
    private final Set<Integer> obsHitByUsBulletIds = new HashSet<>();
    private final Set<Integer> obsHitOnUsBulletIds = new HashSet<>();
    // Opponent prev-tick state/velocity for the autopilot's wall-damage truth.
    private RobotState prevOppState = null;
    private double prevOppVelocity = Double.NaN;

    // Last per-channel obs accumulator reading. The autopilot's wb accumulators
    // monotonically grow within a carry window and snap to a smaller value (or
    // 0) when FireFeatures consumes + resets them on a scan tick. Per-tick obs
    // delta is therefore: max(0, curr - prev) when curr >= prev (growth), or
    // curr when curr < prev (reset happened; the new value is this tick's fresh
    // contribution).
    private final double[] prevObs = new double[DamageObservationTracker.N];

    // Whether the PREVIOUS recorded tick was a scan tick on which the autopilot
    // zeroed its damage accumulators. When true, this tick's obs snapshot is a
    // fresh post-reset per-tick value (taken verbatim) rather than a running
    // total to snap-down. See recordDamageObservation.
    private boolean prevTickAccumulatorReset = false;

    // Optional per-event trace writer for damage observations (analog of
    // theirFireTrace for Layer 3). Null when -PdamageEventsDir not provided.
    private DamageEventsTraceWriter damageEventsTrace;

    public void setDamageEventsTrace(DamageEventsTraceWriter w) {
        this.damageEventsTrace = w;
    }

    private static final String[] DAMAGE_CHANNEL_NAMES = {
            "OUR_BULLET_DMG", "OPP_BULLET_GAIN", "RAM_DMG", "OPP_WALL_DMG"
    };

    @SuppressWarnings("unchecked")
    private static Set<Integer>[] newIdSets() {
        return new Set[] { new HashSet<Integer>(), new HashSet<Integer>() };
    }

    public GodViewQualityValidator() {
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

    /**
     * Incoming-fire ("their wave") detection tracker.
     * <p>
     * Only the autopilot perspective is recorded — measuring the autopilot's
     * own outgoing fire against god-view is omitted (the autopilot owns those
     * bullets, so it can never disagree with the engine about them).
     * </p>
     * <p>
     * The blindfolded autopilot infers an enemy shot from an energy drop, so it
     * knows the origin (enemy position one tick before detection), timing (that
     * same tick) and power (the drop) exactly, but cannot know the muzzle angle —
     * it must assume a head-on bearing. The two streams are paired by <b>fire
     * tick</b> (not bullet id: the autopilot never sees the incoming bullet, so it
     * has no id for it).
     * </p>
     */
    private static final class TheirFireDetectionTracker {
        int godViewCount;
        int robotSideCount;
        final Map<Long, FireRecord> pendingGodView = new HashMap<>();
        final Map<Long, FireRecord> pendingRobotSide = new HashMap<>();
        double positionErrorSum;
        double powerErrorSum;
        double latencySum;
        double angleErrorSum;
        int pairedCount;

        double getFireDetectionRate() {
            if (godViewCount == 0)
                return Double.NaN;
            return (double) robotSideCount / godViewCount;
        }

        /**
         * Accumulate errors for one paired (god-view, robot-side) incoming fire.
         * {@code godView.heading} is the engine bullet's true flight direction;
         * {@code robotSide.heading} is the robot's head-on assumption. Their gap is
         * the irreducible muzzle-angle unknown.
         */
        void pair(FireRecord godView, FireRecord robotSide) {
            positionErrorSum += Math.hypot(godView.x - robotSide.x, godView.y - robotSide.y);
            powerErrorSum += Math.abs(godView.power - robotSide.power);
            latencySum += (robotSide.tick - godView.tick);
            angleErrorSum += Math.abs(RoboMath.normalRelativeAngle(godView.heading - robotSide.heading));
            pairedCount++;
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

        double getAngleMAE() {
            return pairedCount > 0 ? angleErrorSum / pairedCount : Double.NaN;
        }
    }

    /**
     * Per-class damage-observation drift tracker (autopilot perspective).
     * <p>
     * Four damage channels affect opponent energy each tick:
     * <ol>
     * <li>our bullets hitting the opponent (we observe via
     * {@code BulletHitEvent})</li>
     * <li>opponent bullets hitting us — opponent gains the hit bonus (we
     * observe our own {@code HitByBulletEvent} and credit the opponent)</li>
     * <li>ram damage (we observe via {@code HitRobotEvent}; opponent also
     * pays {@code RAM_DAMAGE} per tick of contact)</li>
     * <li>opponent wall hits — inferred from a scanned
     * {@code oppState == HIT_WALL} transition using the opponent's prior-scan
     * velocity ({@code wallDamage(prevOppVelocity)}). The autopilot has the
     * same inputs the validator does, so this channel can be observed exactly
     * on every-tick scans; only the across-scan-gap acceleration uncertainty
     * (±2 px/s/tick) is residual</li>
     * </ol>
     * For each channel we accumulate the god-view truth, the autopilot's
     * observation, and the absolute drift. The four drifts together upper-bound
     * the residual energy the autopilot cannot attribute, which is exactly the
     * material L3 has to work with when classifying an energy drop as fire.
     */
    static final class DamageObservationTracker {
        static final int OUR_BULLET_DMG = 0;
        static final int OPP_BULLET_GAIN = 1;
        static final int RAM_DMG = 2;
        static final int OPP_WALL_DMG = 3;
        static final int N = 4;
        static final String[] LABELS = {
                "ourBulletDmg(on opp)", "oppBulletGain(from us)",
                "ramDmg(on opp)", "oppWallDmg"
        };

        final double[] gvTotal = new double[N];
        final double[] obsTotal = new double[N];
        final double[] absDriftTotal = new double[N];
        final int[] gvEventCount = new int[N];
        final int[] obsEventCount = new int[N];
        final int[] driftTickCount = new int[N];
        long ticks;
        long mismatchTicks;

        void record(double[] gv, double[] obs) {
            ticks++;
            boolean anyDrift = false;
            for (int i = 0; i < N; i++) {
                gvTotal[i] += gv[i];
                obsTotal[i] += obs[i];
                if (gv[i] > EPSILON)
                    gvEventCount[i]++;
                if (obs[i] > EPSILON)
                    obsEventCount[i]++;
                double drift = Math.abs(gv[i] - obs[i]);
                if (drift > EPSILON) {
                    absDriftTotal[i] += drift;
                    driftTickCount[i]++;
                    anyDrift = true;
                }
            }
            if (anyDrift)
                mismatchTicks++;
        }

        double totalAbsDrift() {
            double s = 0;
            for (double d : absDriftTotal)
                s += d;
            return s;
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
        }
    }

    // ========== Layer 3: Incoming-Fire Detection ==========

    /**
     * Track a god-view incoming fire — the opponent's bullet as the engine created
     * it (true muzzle origin, true fire tick, true power, true flight heading).
     * Paired with the autopilot's energy-drop inference of the same fire tick.
     */
    public void recordGodViewTheirFire(double power, double x, double y,
            double heading, long fireTick) {
        theirFireTracking.godViewCount++;
        FireRecord godView = new FireRecord(power, x, y, heading, fireTick);
        FireRecord robotSide = theirFireTracking.pendingRobotSide.remove(fireTick);
        if (robotSide != null) {
            theirFireTracking.pair(godView, robotSide);
        } else {
            theirFireTracking.pendingGodView.put(fireTick, godView);
        }
    }

    /**
     * Track an autopilot-side incoming-fire detection — inferred from an enemy
     * energy drop. {@code bearing} is the head-on assumption of the muzzle angle.
     * Paired with the god-view fire of the same fire tick.
     */
    public void recordRobotSideTheirFire(double power, double x, double y,
            double bearing, long fireTick) {
        theirFireTracking.robotSideCount++;
        FireRecord robotSide = new FireRecord(power, x, y, bearing, fireTick);
        FireRecord godView = theirFireTracking.pendingGodView.remove(fireTick);
        if (godView != null) {
            theirFireTracking.pair(godView, robotSide);
        } else {
            theirFireTracking.pendingRobotSide.put(fireTick, robotSide);
        }
    }

    // ========== Layer 4: Wave GF Precision ==========

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

    // ========== Layer 2: Damage-Observation Drift (autopilot-only) ==========

    /**
     * Per-tick comparison of the autopilot's observed opponent-damage tally vs
     * god-view truth, for the four channels that legitimately move opponent
     * energy. The four numbers in {@code obs*} come straight off the autopilot
     * whiteboard — the exact values {@code FireFeatures.process} subtracts from
     * the scan-to-scan drop before deciding "fire vs not-fire".
     * <p>
     * Channels (autopilot perspective; opponent = {@code 1 - autopilotIndex}):
     * <ul>
     * <li><b>OUR_BULLET_DMG</b> &mdash; our bullets that transitioned to
     * {@code HIT_VICTIM} on opponent this tick; god-view value is
     * {@code bulletDamage(power)}.</li>
     * <li><b>OPP_BULLET_GAIN</b> &mdash; opponent bullets that transitioned to
     * {@code HIT_VICTIM} on us this tick; god-view value is
     * {@code hitEnergyBonus(power)} credited to opponent.</li>
     * <li><b>RAM_DMG</b> &mdash; {@code RAM_DAMAGE} when either robot is in
     * {@code HIT_ROBOT} this tick (opponent loses one unit per contact tick).</li>
     * <li><b>OPP_WALL_DMG</b> &mdash; {@code wallDamage(prevOppVelocity)} when
     * opponent transitions into {@code HIT_WALL}. Computed from
     * {@link IRobotSnapshot#getState()} and the prior-scan velocity, both of
     * which the autopilot observes via {@code ScannedRobotEvent}; residual
     * drift is bounded by scan-gap acceleration only.</li>
     * </ul>
     * NaN values from the autopilot whiteboard (no event this tick) are
     * treated as 0.
     */
    public void recordDamageObservation(int round, long tick,
            int autopilotIndex, IRobotSnapshot[] robots,
            IBulletSnapshot[] bullets,
            double obsOurBulletDmg, double obsOppBulletGain,
            double obsRamDmg, double obsOppWallDmg,
            boolean accumulatorResetThisTick) {
        int opp = 1 - autopilotIndex;
        IRobotSnapshot oppSnap = robots[opp];
        IRobotSnapshot selfSnap = robots[autopilotIndex];

        double[] gv = new double[DamageObservationTracker.N];

        if (bullets != null) {
            for (IBulletSnapshot b : bullets) {
                int id = b.getBulletId();
                BulletState state = b.getState();
                int owner = b.getOwnerIndex();
                int victim = b.getVictimIndex();
                if (state != BulletState.HIT_VICTIM)
                    continue;
                if (owner == autopilotIndex && victim == opp
                        && obsHitByUsBulletIds.add(id)) {
                    gv[DamageObservationTracker.OUR_BULLET_DMG] += bulletDamage(b.getPower());
                } else if (owner == opp && victim == autopilotIndex
                        && obsHitOnUsBulletIds.add(id)) {
                    gv[DamageObservationTracker.OPP_BULLET_GAIN] += hitEnergyBonus(b.getPower());
                }
            }
        }

        // Ram: opponent loses RAM_DAMAGE per tick of contact. The opponent's
        // HIT_ROBOT state is the most direct signal; self HIT_ROBOT is also
        // counted because wall+ram pin reports HIT_WALL on the pinned side.
        if (selfSnap.getState() == RobotState.HIT_ROBOT
                || oppSnap.getState() == RobotState.HIT_ROBOT) {
            gv[DamageObservationTracker.RAM_DMG] += RAM_DAMAGE;
        }

        // Opponent wall hit: charged once on transition into HIT_WALL, using
        // opponent's prev-tick velocity (post-impact velocity is zeroed).
        if (oppSnap.getState() == RobotState.HIT_WALL
                && prevOppState != RobotState.HIT_WALL
                && !Double.isNaN(prevOppVelocity)) {
            gv[DamageObservationTracker.OPP_WALL_DMG] += wallDamage(prevOppVelocity);
        }
        prevOppState = oppSnap.getState();
        prevOppVelocity = oppSnap.getVelocity();

        // Per-tick obs delta: monotonic growth within a window, snap-down on
        // FireFeatures consumption. Treat any decrease as a reset, so the new
        // value IS this tick's contribution. Additionally, when the PREVIOUS
        // tick was a scan tick the autopilot zeroed the accumulators at the end
        // of that tick's doTurn, so this tick's snapshot is already a fresh
        // post-reset per-tick value (not a running total) and must be taken
        // verbatim. Without this, a sustained every-tick-scan clinch (wall-pin)
        // holds the snapshot at a constant per-tick value and the plain
        // snap-down delta collapses to 0, under-counting ram damage.
        double[] obsCurr = {
                nanToZero(obsOurBulletDmg), nanToZero(obsOppBulletGain),
                nanToZero(obsRamDmg), nanToZero(obsOppWallDmg)
        };
        double[] obsDelta = new double[DamageObservationTracker.N];
        for (int i = 0; i < DamageObservationTracker.N; i++) {
            if (prevTickAccumulatorReset) {
                obsDelta[i] = obsCurr[i];
            } else {
                obsDelta[i] = obsCurr[i] >= prevObs[i] ? obsCurr[i] - prevObs[i] : obsCurr[i];
            }
            prevObs[i] = obsCurr[i];
        }
        prevTickAccumulatorReset = accumulatorResetThisTick;
        damageObsTracking.record(gv, obsDelta);

        if (damageEventsTrace != null) {
            final double EPS = 1e-9;
            for (int i = 0; i < DamageObservationTracker.N; i++) {
                if (Math.abs(gv[i]) > EPS || Math.abs(obsDelta[i]) > EPS) {
                    damageEventsTrace.write(round, tick, DAMAGE_CHANNEL_NAMES[i],
                            gv[i], obsDelta[i],
                            selfSnap.getEnergy(), oppSnap.getEnergy(),
                            selfSnap.getState() == null ? null : selfSnap.getState().name(),
                            oppSnap.getState() == null ? null : oppSnap.getState().name());
                }
            }
        }
    }

    private static double nanToZero(double v) {
        return Double.isNaN(v) ? 0.0 : v;
    }

    // ========== Layer 2: Energy Accounting ==========

    /**
     * Track energy changes against engine rules.
     * On first tick per perspective, initializes prevEnergy without checking.
     * <p>
     * God-view bullet/robot snapshot states linger for several ticks (the
     * explosion animation keeps a hit bullet in {@code HIT_VICTIM} state, and a
     * robot stays in {@code HIT_WALL}/{@code HIT_ROBOT} while pinned). Energy is
     * charged by the engine only on the transition tick, so each event is applied
     * exactly once: bullet costs/bonuses are keyed by bullet id, and wall/ram
     * damage is applied only on the state transition.
     */
    public void accountEnergy(int perspIndex, IRobotSnapshot[] robots,
            IBulletSnapshot[] bullets) {
        IRobotSnapshot self = robots[perspIndex];
        double currentEnergy = self.getEnergy();
        int opponentIndex = 1 - perspIndex;

        if (Double.isNaN(prevEnergy[perspIndex])) {
            prevEnergy[perspIndex] = currentEnergy;
            prevVelocity[perspIndex] = self.getVelocity();
            prevState[perspIndex] = self.getState();
            return;
        }

        double expected = prevEnergy[perspIndex];

        if (bullets != null) {
            for (IBulletSnapshot b : bullets) {
                int id = b.getBulletId();
                BulletState state = b.getState();
                int owner = b.getOwnerIndex();
                int victim = b.getVictimIndex();

                // Fire cost: charged once when our bullet is first observed in a
                // pre-impact state (FIRED on the firing tick, or MOVING in flight).
                // A bullet first seen already in a terminal hit state was fired on an
                // earlier, unobserved tick, so its cost is not (re)charged here.
                if (owner == perspIndex
                        && (state == BulletState.FIRED || state == BulletState.MOVING)
                        && firedBulletIds[perspIndex].add(id)) {
                    expected -= b.getPower();
                }

                // Hit energy bonus: credited once when our bullet first hits.
                if (owner == perspIndex && state == BulletState.HIT_VICTIM
                        && hitByUsBulletIds[perspIndex].add(id)) {
                    expected += hitEnergyBonus(b.getPower());
                }

                // Bullet damage received: charged once when an opponent bullet
                // first hits us.
                if (owner == opponentIndex && victim == perspIndex
                        && state == BulletState.HIT_VICTIM
                        && hitOnUsBulletIds[perspIndex].add(id)) {
                    expected -= bulletDamage(b.getPower());
                }
            }
        }

        // Wall hit damage: applied once on transition into HIT_WALL, using the
        // previous tick's velocity (the impact-tick snapshot velocity is already
        // zeroed post-impact). The exact intra-tick impact speed is unobservable
        // from snapshots — the robot may accelerate (+1), hold, or brake (-2)
        // during the impact tick — so prevVelocity is used as the neutral prior.
        if (self.getState() == RobotState.HIT_WALL && prevState[perspIndex] != RobotState.HIT_WALL
                && !Double.isNaN(prevVelocity[perspIndex])) {
            expected -= wallDamage(prevVelocity[perspIndex]);
        }

        // Ram damage: both robots lose RAM_DAMAGE each tick they are in contact.
        // A robot pinned against a wall reports HIT_WALL (wall takes priority in the
        // state enum) even while being rammed, so the collision is detected via
        // either robot's HIT_ROBOT state rather than self's state alone.
        if (self.getState() == RobotState.HIT_ROBOT
                || robots[opponentIndex].getState() == RobotState.HIT_ROBOT) {
            expected -= RAM_DAMAGE;
        }

        energyChecks[perspIndex]++;
        if (Math.abs(expected - currentEnergy) > EPSILON) {
            energyDiscrepancies[perspIndex]++;
        }

        prevEnergy[perspIndex] = currentEnergy;
        prevVelocity[perspIndex] = self.getVelocity();
        prevState[perspIndex] = self.getState();
    }
    // ========== Round Reset ==========

    /** Reset per-round state. Counters are cumulative across rounds. */
    public void resetRound() {
        prevEnergy[0] = Double.NaN;
        prevEnergy[1] = Double.NaN;
        prevVelocity[0] = Double.NaN;
        prevVelocity[1] = Double.NaN;
        prevState[0] = null;
        prevState[1] = null;
        // Bullet ids are per-round sequential; clear so next round starts fresh.
        for (int p = 0; p < 2; p++) {
            firedBulletIds[p].clear();
            hitByUsBulletIds[p].clear();
            hitOnUsBulletIds[p].clear();
        }
        obsHitByUsBulletIds.clear();
        obsHitOnUsBulletIds.clear();
        prevOppState = null;
        prevOppVelocity = Double.NaN;
        prevTickAccumulatorReset = false;
        for (int i = 0; i < DamageObservationTracker.N; i++) {
            prevObs[i] = 0;
        }
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
        if (energyChecks[0] + energyChecks[1] == 0) {
            throw new IllegalStateException("Layer 2 self-test vacuous: 0 energy checks performed");
        }
        if (damageObsTracking.ticks == 0) {
            throw new IllegalStateException(
                    "Layer 2 vacuous: 0 damage-observation ticks recorded");
        }
        if (theirFireTracking.godViewCount == 0) {
            throw new IllegalStateException("Layer 3 vacuous: 0 god-view incoming fires detected");
        }
        // Layer 4 GF comparisons require matched wave resolution between observer and
        // live robot. Since the observer fires independently (different timing),
        // matched comparisons may not occur. Not a vacuous-test concern.
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

    public double getTheirFireDetectionRate() {
        return theirFireTracking.getFireDetectionRate();
    }

    public int getTheirGodViewFires() {
        return theirFireTracking.godViewCount;
    }

    public int getTheirRobotSideFires() {
        return theirFireTracking.robotSideCount;
    }

    public double getTheirFirePositionMAE() {
        return theirFireTracking.getPositionMAE();
    }

    public double getTheirFirePowerMAE() {
        return theirFireTracking.getPowerMAE();
    }

    public double getTheirFireDetectionLatency() {
        return theirFireTracking.getDetectionLatency();
    }

    public double getTheirFireAngleMAE() {
        return theirFireTracking.getAngleMAE();
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

        // Layer 2 — autopilot's observation of opponent-damage events vs god-view.
        // Drift here is what FireFeatures cannot subtract out of the energy delta,
        // so it directly upper-bounds the L3 phantom rate.
        System.out.printf("Layer 2 — Damage Observation Drift (autopilot):%n");
        DamageObservationTracker d2 = damageObsTracking;
        System.out.printf("  ticks=%d, mismatchTicks=%d, totalAbsDrift=%.4f%n",
                d2.ticks, d2.mismatchTicks, d2.totalAbsDrift());
        for (int i = 0; i < DamageObservationTracker.N; i++) {
            System.out.printf("    %-24s gv=%.4f (events=%d) obs=%.4f (events=%d) "
                    + "absDrift=%.4f (driftTicks=%d)%n",
                    DamageObservationTracker.LABELS[i],
                    d2.gvTotal[i], d2.gvEventCount[i],
                    d2.obsTotal[i], d2.obsEventCount[i],
                    d2.absDriftTotal[i], d2.driftTickCount[i]);
        }
        System.out.println();

        // Engine-rule self-test (internal sanity check). Reconciles each robot's
        // per-tick energy change against the engine's own god-view event stream.
        // Discrepancies here mean OUR model of the engine is wrong; they say
        // nothing about autopilot observation quality.
        System.out.printf("Energy Model Self-Test (engine-rule sanity):%n");
        for (int pi = 0; pi < 2; pi++) {
            System.out.printf("  Perspective %d: checks=%d, discrepancies=%d%n",
                    pi, energyChecks[pi], energyDiscrepancies[pi]);
        }
        System.out.println();

        // Layer 3 — autopilot's detection of incoming fire from its energy-drop
        // inference. Origin/timing/power are knowable exactly (expect ~0 MAE);
        // angleMAE is the irreducible muzzle-angle unknown the blindfolded
        // autopilot cannot infer.
        System.out.printf("Layer 3 — Incoming-Fire Detection (autopilot):%n");
        TheirFireDetectionTracker t3 = theirFireTracking;
        System.out.printf("  godView=%d, robotSide=%d, rate=%s%n",
                t3.godViewCount, t3.robotSideCount,
                formatMetric(t3.getFireDetectionRate(), "%.3f"));
        System.out.printf("    positionMAE=%s, powerMAE=%s, latency=%s, angleMAE(rad)=%s%n",
                formatMetric(t3.getPositionMAE(), "%.4f"),
                formatMetric(t3.getPowerMAE(), "%.4f"),
                formatMetric(t3.getDetectionLatency(), "%.2f"),
                formatMetric(t3.getAngleMAE(), "%.4f"));
        System.out.println();

        // Layer 4
        System.out.printf("Layer 4 — GF Precision:%n");
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
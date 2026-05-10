package cz.zamboch.autopilot.core;

import cz.zamboch.autopilot.core.predictors.PredictorRegistry;
import cz.zamboch.autopilot.core.util.PrimitiveLongRingBuffer;
import cz.zamboch.autopilot.core.util.PrimitiveRollingBuffer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Central state store per robot perspective. Receives robocode events,
 * accumulates per-tick data, and provides lookback history.
 * Feature values are stored in an array indexed by Feature.ordinal().
 */
public final class Whiteboard {

    // === Current tick state ===
    private double ourX, ourY, ourHeading, ourGunHeading, ourRadarHeading;
    private double ourVelocity, ourEnergy, ourGunHeat;
    private long tick;
    private int round;
    private String battleId;

    // Opponent state (from last scan — may be stale)
    private String opponentName;
    private String opponentBotId;     // part before first space (e.g. "DrussGT")
    private String opponentVersion;   // part after first space (e.g. "3.1.7"), "" if none
    private double opponentX, opponentY, opponentHeading, opponentVelocity, opponentEnergy;
    private double prevOpponentEnergy;
    private double prevOpponentHeading = Double.NaN;
    private double prevOpponentVelocity = Double.NaN;
    private long lastScanTick = -1;
    private long prevScanTick = -1;
    private boolean scanAvailableThisTick;

    // Event flags for energy drop detection
    private boolean weHitOpponentThisTick;
    private boolean opponentHitWallThisTick;

    // Movement segmentation inter-tick state (written by features, stored here for statelessness)
    private int prevLateralDirection;
    private long ticksSinceDirectionChange;

    // Opponent fire tracking (set by EnergyFeatures on detected fire)
    private long lastOpponentFireTick = -1;
    private double lastOpponentFirePower;

    // Our fire tracking (set by Player on first sighting of a new bullet whose owner is us)
    private long lastOurFireTick = -1;
    private double lastOurFirePower;

    // Current fire power budget (set by strategy computer after each scan, consumed by TargetingFeatures)
    private double currentFirePowerBudget;

    // Radar history (for scan-coverage features)
    private double prevOurRadarHeading = Double.NaN;

    // Velocity-change tracking (#96)
    private long lastVelocityChangeTick = -1;
    private double lastSignificantOpponentVelocity = Double.NaN;

    // Distance-since-direction-change accumulator (#97)
    private double distanceSinceDirChange;

    // Multi-wave tracking: all active in-flight waves
    private final List<WaveRecord> opponentWaves = new ArrayList<WaveRecord>(20);
    private final List<WaveRecord> ourWaves = new ArrayList<WaveRecord>(20);

    // VCS (Visit Count Statistics) histograms — pre-allocated, persist across rounds
    // Segments: 3 distance bins × 2 lateral direction × 2 velocity bins = 12
    public static final int VCS_BINS = 61;
    public static final int VCS_DISTANCE_BINS = 3;
    public static final int VCS_DIR_BINS = 2;
    public static final int VCS_VEL_BINS = 2;
    public static final int VCS_SEGMENTS = VCS_DISTANCE_BINS * VCS_DIR_BINS * VCS_VEL_BINS;
    private static final double VCS_DIST_CLOSE = 250.0;
    private static final double VCS_DIST_MID = 500.0;
    /** Velocity threshold for slow vs fast bucket (px/tick). Half max speed. */
    public static final double VCS_VEL_THRESHOLD = 4.0;

    /** Gun VCS: where the opponent has been at our wave breaks. Used by VcsGun. */
    private final int[][] gunVcs = new int[VCS_SEGMENTS][VCS_BINS];
    /** Move VCS: where opponent bullets hit us (opponent's targeting model). Used by VcsWaveDanger. */
    private final int[][] moveVcs = new int[VCS_SEGMENTS][VCS_BINS];

    // Rolling history buffers (largest window = 50 for scan coverage)
    private final PrimitiveRollingBuffer latVelHistory30 = new PrimitiveRollingBuffer(30, 10);
    private final PrimitiveRollingBuffer velHistory30 = new PrimitiveRollingBuffer(30, 10);
    private final PrimitiveRollingBuffer headingDeltaHistory30 = new PrimitiveRollingBuffer(30, 10);
    private final PrimitiveLongRingBuffer scanTickHistory50 = new PrimitiveLongRingBuffer(50);

    // Battle constants
    private int battlefieldWidth, battlefieldHeight;
    private double gunCoolingRate;
    private int numRounds;

    // Cumulative counters (battle-level)
    private int ourShotsFired, opponentShotsDetected;
    private double damageDealt, damageReceived;
    private int roundsWon, roundsLost;
    private int ourBulletHitCount, opponentBulletHitCount;

    // Per-round counters (reset each round for SCORES output)
    private int ourShotsFiredThisRound, opponentShotsDetectedThisRound;
    private double damageDealtThisRound, damageReceivedThisRound;
    private int ourBulletHitCountThisRound, opponentBulletHitCountThisRound;

    // Computed features (array-backed for performance)
    private final double[] features = new double[Feature.values().length];
    private final boolean[] featureSet = new boolean[Feature.values().length];

    // Predictor registry for complex (non-scalar) predictions
    private final PredictorRegistry predictorRegistry = new PredictorRegistry();

    // === Feature API ===

    public void setFeature(Feature key, double value) {
        features[key.ordinal()] = value;
        featureSet[key.ordinal()] = true;
    }

    public double getFeature(Feature key) {
        return features[key.ordinal()];
    }

    public boolean hasFeature(Feature key) {
        return featureSet[key.ordinal()];
    }

    // === Lifecycle ===

    /** Shift current state to history, clear per-tick feature flags. */
    public void advanceTick() {
        tick++;
        scanAvailableThisTick = false;
        weHitOpponentThisTick = false;
        opponentHitWallThisTick = false;
        for (int i = 0; i < featureSet.length; i++) {
            featureSet[i] = false;
            features[i] = Double.NaN;
        }
    }

    /** Clear per-round state, keep battle-level counters. */
    public void resetRound() {
        tick = 0;
        scanAvailableThisTick = false;
        lastScanTick = -1;
        prevScanTick = -1;
        prevOpponentEnergy = 0;
        prevOpponentHeading = Double.NaN;
        prevOpponentVelocity = Double.NaN;
        opponentHeading = Double.NaN;
        weHitOpponentThisTick = false;
        opponentHitWallThisTick = false;
        prevLateralDirection = 0;
        ticksSinceDirectionChange = 0;
        lastOpponentFireTick = -1;
        lastOpponentFirePower = 0;
        lastOurFireTick = -1;
        lastOurFirePower = 0;
        currentFirePowerBudget = 0;
        prevOurRadarHeading = Double.NaN;
        lastVelocityChangeTick = -1;
        lastSignificantOpponentVelocity = Double.NaN;
        distanceSinceDirChange = 0;
        latVelHistory30.clear();
        velHistory30.clear();
        headingDeltaHistory30.clear();
        scanTickHistory50.clear();
        opponentWaves.clear();
        ourWaves.clear();
        ourShotsFiredThisRound = 0;
        opponentShotsDetectedThisRound = 0;
        damageDealtThisRound = 0;
        damageReceivedThisRound = 0;
        ourBulletHitCountThisRound = 0;
        opponentBulletHitCountThisRound = 0;
        for (int i = 0; i < featureSet.length; i++) {
            featureSet[i] = false;
            features[i] = Double.NaN;
        }
    }

    /** Full reset for a new battle. */
    public void resetBattle() {
        resetRound();
        round = 0;
        roundsWon = 0;
        roundsLost = 0;
        ourShotsFired = 0;
        opponentShotsDetected = 0;
        damageDealt = 0;
        damageReceived = 0;
        ourBulletHitCount = 0;
        opponentBulletHitCount = 0;
        // VCS histograms: clear for new battle (different opponent)
        for (int s = 0; s < VCS_SEGMENTS; s++) {
            for (int b = 0; b < VCS_BINS; b++) {
                gunVcs[s][b] = 0;
                moveVcs[s][b] = 0;
            }
        }
    }

    // === Initialization ===

    public void setBattleId(String battleId) {
        this.battleId = battleId;
    }

    public void onRoundStart(int round, int battlefieldWidth, int battlefieldHeight,
                             double gunCoolingRate, int numRounds) {
        resetRound();
        this.round = round;
        this.battlefieldWidth = battlefieldWidth;
        this.battlefieldHeight = battlefieldHeight;
        this.gunCoolingRate = gunCoolingRate;
        this.numRounds = numRounds;
    }

    // === Getters ===

    public double getOurX() { return ourX; }
    public double getOurY() { return ourY; }
    public double getOurHeading() { return ourHeading; }
    public double getOurGunHeading() { return ourGunHeading; }
    public double getOurRadarHeading() { return ourRadarHeading; }
    public double getOurVelocity() { return ourVelocity; }
    public double getOurEnergy() { return ourEnergy; }
    public double getOurGunHeat() { return ourGunHeat; }
    public long getTick() { return tick; }
    public int getRound() { return round; }
    public String getBattleId() { return battleId; }

    public String getOpponentName() { return opponentName; }
    public String getOpponentBotId() { return opponentBotId; }
    public String getOpponentVersion() { return opponentVersion; }
    public double getOpponentX() { return opponentX; }
    public double getOpponentY() { return opponentY; }
    public double getOpponentHeading() { return opponentHeading; }
    public double getOpponentVelocity() { return opponentVelocity; }
    public double getOpponentEnergy() { return opponentEnergy; }
    public double getPrevOpponentEnergy() { return prevOpponentEnergy; }
    public double getPrevOpponentHeading() { return prevOpponentHeading; }
    public double getPrevOpponentVelocity() { return prevOpponentVelocity; }
    public long getLastScanTick() { return lastScanTick; }
    public long getPrevScanTick() { return prevScanTick; }

    public int getPrevLateralDirection() { return prevLateralDirection; }
    public long getTicksSinceDirectionChange() { return ticksSinceDirectionChange; }
    public long getLastOpponentFireTick() { return lastOpponentFireTick; }
    public double getLastOpponentFirePower() { return lastOpponentFirePower; }
    public long getLastOurFireTick() { return lastOurFireTick; }
    public double getLastOurFirePower() { return lastOurFirePower; }
    public double getCurrentFirePowerBudget() { return currentFirePowerBudget; }
    public double getPrevOurRadarHeading() { return prevOurRadarHeading; }
    public long getLastVelocityChangeTick() { return lastVelocityChangeTick; }
    public double getLastSignificantOpponentVelocity() { return lastSignificantOpponentVelocity; }
    public double getDistanceSinceDirChange() { return distanceSinceDirChange; }
    public PrimitiveRollingBuffer getLatVelHistory30() { return latVelHistory30; }

    // Multi-wave tracking
    public List<WaveRecord> getOpponentWaves() { return opponentWaves; }
    public List<WaveRecord> getOurWaves() { return ourWaves; }

    /** Add a detected opponent wave (called by MultiWaveFeatures when fire detected). */
    public void addOpponentWave(WaveRecord wave) { opponentWaves.add(wave); }

    /** Add one of our waves (called by Player/Autopilot when we fire a bullet). */
    public void addOurWave(WaveRecord wave) { ourWaves.add(wave); }

    /** Remove waves that have passed their target. Called each tick. */
    public void prunePassedWaves(double distanceToOpponent) {
        long t = tick;
        Iterator<WaveRecord> it = opponentWaves.iterator();
        while (it.hasNext()) {
            WaveRecord w = it.next();
            if (w.hasPassed(w.fireDistance, t)) {
                // Update move VCS before removing: record our GF at wave break
                if (!Double.isNaN(w.fireBearing)) {
                    double candBearing = Math.atan2(ourX - w.originX, ourY - w.originY);
                    double offset = normalRelAngle(candBearing - w.fireBearing);
                    double mea = Math.asin(Math.min(1.0, 8.0 / w.bulletSpeed));
                    double gf = mea > 0 ? offset / mea : 0;
                    gf = Math.max(-1.0, Math.min(1.0, gf));
                    // Use fire-time distance and lateral direction for segmentation
                    int latDir = w.fireLateralDir;
                    if (latDir == 0) latDir = 1;
                    int segment = vcsSegment(w.fireDistance, latDir, w.fireOpponentAbsVelocity);
                    incrementMoveVcs(segment, gfToBin(gf));
                }
                it.remove();
            }
        }
        it = ourWaves.iterator();
        while (it.hasNext()) {
            WaveRecord w = it.next();
            if (w.hasPassed(distanceToOpponent, t)) {
                // Update gun VCS before removing: record opponent's GF at wave break
                if (!Double.isNaN(w.fireBearing)) {
                    double candBearing = Math.atan2(
                            opponentX - w.originX, opponentY - w.originY);
                    double offset = normalRelAngle(candBearing - w.fireBearing);
                    double mea = Math.asin(Math.min(1.0, 8.0 / w.bulletSpeed));
                    double gf = mea > 0 ? offset / mea : 0;
                    gf = Math.max(-1.0, Math.min(1.0, gf));
                    // Use fire-time distance and lateral direction for segmentation
                    // (must match VcsGun's query-time segmentation)
                    int latDir = w.fireLateralDir;
                    if (latDir == 0) latDir = 1;
                    int segment = vcsSegment(w.fireDistance, latDir, w.fireOpponentAbsVelocity);
                    incrementGunVcs(segment, gfToBin(gf));
                }
                it.remove();
            }
        }
    }

    // === VCS helpers ===

    /** Compute VCS segment index from distance, lateral direction, and absolute velocity. */
    public static int vcsSegment(double distance, int lateralDir, double absVelocity) {
        int distBin = distance < VCS_DIST_CLOSE ? 0
                : distance < VCS_DIST_MID ? 1 : 2;
        int dirBin = lateralDir >= 0 ? 0 : 1;
        int velBin = absVelocity < VCS_VEL_THRESHOLD ? 0 : 1;
        return (distBin * VCS_DIR_BINS + dirBin) * VCS_VEL_BINS + velBin;
    }

    /** Backward-compatible overload — assumes fast velocity (>= threshold). */
    public static int vcsSegment(double distance, int lateralDir) {
        return vcsSegment(distance, lateralDir, VCS_VEL_THRESHOLD);
    }

    /** Convert a GF value in [-1,1] to a histogram bin index in [0, VCS_BINS-1]. */
    public static int gfToBin(double gf) {
        int bin = (int) Math.round((gf + 1.0) * 30.0);
        return Math.max(0, Math.min(VCS_BINS - 1, bin));
    }

    /** Convert a histogram bin index to GF value. */
    public static double binToGf(int bin) {
        return (bin / 30.0) - 1.0;
    }

    public void incrementGunVcs(int segment, int bin) { gunVcs[segment][bin]++; }
    public void incrementMoveVcs(int segment, int bin) { moveVcs[segment][bin]++; }
    public int[] getGunVcsSegment(int segment) { return gunVcs[segment]; }
    public int[] getMoveVcsSegment(int segment) { return moveVcs[segment]; }

    /**
     * Initialize VCS histograms with a Gaussian prior centered at GF=0.
     * Gives the VCS gun reasonable starting data before it has observed hits.
     * Called at battle start if no persisted histograms are loaded.
     */
    public void initVcsPrior(int strength) {
        for (int s = 0; s < VCS_SEGMENTS; s++) {
            for (int b = 0; b < VCS_BINS; b++) {
                double gf = binToGf(b);
                // Gaussian kernel σ=0.3 centered at GF=0
                int prior = (int) Math.round(strength * Math.exp(-0.5 * (gf / 0.3) * (gf / 0.3)));
                gunVcs[s][b] += prior;
            }
        }
    }

    // === Opponent interpolation on no-scan ticks (7m) ===

    /** Update opponent position without a scan (dead-reckoning extrapolation). */
    public void interpolateOpponent(double x, double y) {
        this.opponentX = x;
        this.opponentY = y;
    }

    /** Minimal angle normalisation (avoid pulling in RoboMath from Whiteboard). */
    private static double normalRelAngle(double angle) {
        double r = angle % (2 * Math.PI);
        if (r >= Math.PI) r -= 2 * Math.PI;
        else if (r < -Math.PI) r += 2 * Math.PI;
        return r;
    }
    public PrimitiveRollingBuffer getVelHistory30() { return velHistory30; }
    public PrimitiveRollingBuffer getHeadingDeltaHistory30() { return headingDeltaHistory30; }
    public PrimitiveLongRingBuffer getScanTickHistory50() { return scanTickHistory50; }
    public boolean isScanAvailableThisTick() { return scanAvailableThisTick; }
    public boolean isWeHitOpponentThisTick() { return weHitOpponentThisTick; }
    public boolean isOpponentHitWallThisTick() { return opponentHitWallThisTick; }

    public int getBattlefieldWidth() { return battlefieldWidth; }
    public int getBattlefieldHeight() { return battlefieldHeight; }
    public double getGunCoolingRate() { return gunCoolingRate; }
    public int getNumRounds() { return numRounds; }

    public PredictorRegistry getPredictorRegistry() { return predictorRegistry; }

    public int getOurShotsFired() { return ourShotsFired; }
    public int getOpponentShotsDetected() { return opponentShotsDetected; }
    public double getDamageDealt() { return damageDealt; }
    public double getDamageReceived() { return damageReceived; }
    public int getOurBulletHitCount() { return ourBulletHitCount; }
    public int getOpponentBulletHitCount() { return opponentBulletHitCount; }
    public int getRoundsWon() { return roundsWon; }
    public int getRoundsLost() { return roundsLost; }

    public int getOurShotsFiredThisRound() { return ourShotsFiredThisRound; }
    public int getOpponentShotsDetectedThisRound() { return opponentShotsDetectedThisRound; }
    public double getDamageDealtThisRound() { return damageDealtThisRound; }
    public double getDamageReceivedThisRound() { return damageReceivedThisRound; }
    public int getOurBulletHitCountThisRound() { return ourBulletHitCountThisRound; }
    public int getOpponentBulletHitCountThisRound() { return opponentBulletHitCountThisRound; }

    // === Setters (used by Player to inject state from snapshots) ===

    public void setOurState(double x, double y, double heading, double gunHeading,
                            double radarHeading, double velocity, double energy, double gunHeat) {
        this.ourX = x;
        this.ourY = y;
        this.ourHeading = heading;
        this.ourGunHeading = gunHeading;
        this.ourRadarHeading = radarHeading;
        this.ourVelocity = velocity;
        this.ourEnergy = energy;
        this.ourGunHeat = gunHeat;
    }

    public void setOpponentScan(String name, double x, double y, double heading, double velocity, double energy) {
        if (this.opponentName == null) {
            this.opponentName = name;
            int sp = name == null ? -1 : name.indexOf(' ');
            if (sp < 0) {
                this.opponentBotId = name == null ? "" : name;
                this.opponentVersion = "";
            } else {
                this.opponentBotId = name.substring(0, sp);
                this.opponentVersion = name.substring(sp + 1);
            }
        }
        this.prevOpponentEnergy = this.opponentEnergy;
        this.prevOpponentHeading = this.opponentHeading;
        this.prevOpponentVelocity = this.opponentVelocity;
        this.opponentX = x;
        this.opponentY = y;
        this.opponentHeading = heading;
        this.opponentVelocity = velocity;
        this.opponentEnergy = energy;
        this.prevScanTick = this.lastScanTick;
        this.lastScanTick = tick;
        this.scanAvailableThisTick = true;
    }

    public void setTick(long tick) { this.tick = tick; }
    public void setWeHitOpponentThisTick(boolean hit) { this.weHitOpponentThisTick = hit; }
    public void setOpponentHitWallThisTick(boolean hit) { this.opponentHitWallThisTick = hit; }

    public void incrementOurShotsFired() { ourShotsFired++; ourShotsFiredThisRound++; }
    public void incrementOpponentShotsDetected() { opponentShotsDetected++; opponentShotsDetectedThisRound++; }
    public void setPrevLateralDirection(int dir) { this.prevLateralDirection = dir; }
    public void setTicksSinceDirectionChange(long ticks) { this.ticksSinceDirectionChange = ticks; }
    public void setLastOpponentFire(long tick, double power) {
        this.lastOpponentFireTick = tick;
        this.lastOpponentFirePower = power;
    }
    public void setLastOurFire(long tick, double power) {
        this.lastOurFireTick = tick;
        this.lastOurFirePower = power;
    }
    public void setCurrentFirePowerBudget(double budget) { this.currentFirePowerBudget = budget; }
    public void setPrevOurRadarHeading(double heading) { this.prevOurRadarHeading = heading; }
    public void setLastVelocityChange(long tick, double velocity) {
        this.lastVelocityChangeTick = tick;
        this.lastSignificantOpponentVelocity = velocity;
    }
    public void setDistanceSinceDirChange(double distance) { this.distanceSinceDirChange = distance; }
    public void addDamageDealt(double damage) { damageDealt += damage; damageDealtThisRound += damage; }
    public void addDamageReceived(double damage) { damageReceived += damage; damageReceivedThisRound += damage; }
    public void incrementOurBulletHitCount() { ourBulletHitCount++; ourBulletHitCountThisRound++; }
    public void incrementOpponentBulletHitCount() { opponentBulletHitCount++; opponentBulletHitCountThisRound++; }
    public void incrementRoundsWon() { roundsWon++; }
    public void incrementRoundsLost() { roundsLost++; }
}

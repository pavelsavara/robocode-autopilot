package cz.zamboch.autopilot.core;

import cz.zamboch.autopilot.core.util.RingBuffer;

/**
 * Central state store per robot perspective. Receives robocode events,
 * accumulates per-tick data, and provides lookback history.
 * Feature values are stored in an array indexed by Feature.ordinal().
 */
public class Whiteboard {

    // === Current tick state ===
    private double ourX, ourY, ourHeading, ourGunHeading, ourRadarHeading;
    private double ourVelocity, ourEnergy, ourGunHeat;
    private long tick;
    private int round;
    private String battleId;

    // Opponent state (from last scan — may be stale)
    private double opponentX, opponentY, opponentHeading, opponentVelocity, opponentEnergy;
    private double prevOpponentEnergy;
    private double prevOpponentHeading = Double.NaN;
    private long lastScanTick = -1;
    private boolean scanAvailableThisTick;

    // Event flags for energy drop detection
    private boolean weHitOpponentThisTick;
    private boolean opponentHitWallThisTick;

    // Battle constants
    private int battlefieldWidth, battlefieldHeight;
    private double gunCoolingRate;
    private int numRounds;

    // Cumulative counters
    private int ourShotsFired, opponentShotsDetected;
    private double damageDealt, damageReceived;
    private int roundsWon, roundsLost;
    private int ourBulletHitCount, opponentBulletHitCount;

    // Computed features (array-backed for performance)
    private final double[] features = new double[Feature.values().length];
    private final boolean[] featureSet = new boolean[Feature.values().length];

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
        prevOpponentEnergy = 0;
        prevOpponentHeading = Double.NaN;
        opponentHeading = Double.NaN;
        weHitOpponentThisTick = false;
        opponentHitWallThisTick = false;
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

    public double getOpponentX() { return opponentX; }
    public double getOpponentY() { return opponentY; }
    public double getOpponentHeading() { return opponentHeading; }
    public double getOpponentVelocity() { return opponentVelocity; }
    public double getOpponentEnergy() { return opponentEnergy; }
    public double getPrevOpponentEnergy() { return prevOpponentEnergy; }
    public double getPrevOpponentHeading() { return prevOpponentHeading; }
    public long getLastScanTick() { return lastScanTick; }
    public boolean isScanAvailableThisTick() { return scanAvailableThisTick; }
    public boolean isWeHitOpponentThisTick() { return weHitOpponentThisTick; }
    public boolean isOpponentHitWallThisTick() { return opponentHitWallThisTick; }

    public int getBattlefieldWidth() { return battlefieldWidth; }
    public int getBattlefieldHeight() { return battlefieldHeight; }
    public double getGunCoolingRate() { return gunCoolingRate; }
    public int getNumRounds() { return numRounds; }

    public int getOurShotsFired() { return ourShotsFired; }
    public int getOpponentShotsDetected() { return opponentShotsDetected; }
    public double getDamageDealt() { return damageDealt; }
    public double getDamageReceived() { return damageReceived; }

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

    public void setOpponentScan(double x, double y, double heading, double velocity, double energy) {
        this.prevOpponentEnergy = this.opponentEnergy;
        this.prevOpponentHeading = this.opponentHeading;
        this.opponentX = x;
        this.opponentY = y;
        this.opponentHeading = heading;
        this.opponentVelocity = velocity;
        this.opponentEnergy = energy;
        this.lastScanTick = tick;
        this.scanAvailableThisTick = true;
    }

    public void setTick(long tick) { this.tick = tick; }
    public void setWeHitOpponentThisTick(boolean hit) { this.weHitOpponentThisTick = hit; }
    public void setOpponentHitWallThisTick(boolean hit) { this.opponentHitWallThisTick = hit; }

    public void incrementOurShotsFired() { ourShotsFired++; }
    public void incrementOpponentShotsDetected() { opponentShotsDetected++; }
    public void addDamageDealt(double damage) { damageDealt += damage; }
    public void addDamageReceived(double damage) { damageReceived += damage; }
    public void incrementOurBulletHitCount() { ourBulletHitCount++; }
    public void incrementOpponentBulletHitCount() { opponentBulletHitCount++; }
    public void incrementRoundsWon() { roundsWon++; }
    public void incrementRoundsLost() { roundsLost++; }
}

package cz.zamboch.autopilot.core;

import robocode.ScannedRobotEvent;

/**
 * Central state store for one robot's perspective during a battle.
 * All feature values are stored in a flat double[] array indexed by Feature
 * ordinal.
 * All inter-tick mutable state lives here — feature classes must be stateless.
 */
public final class Whiteboard {
    private final double[] features = new double[Feature.COUNT];

    // Raw game state (set by the robot each tick before feature processing)
    private long tick;
    private double ourX;
    private double ourY;
    private double ourHeading;
    private double ourVelocity;
    private double ourEnergy;
    private double gunHeat;
    private double gunHeadingRadians;
    private ScannedRobotEvent lastScan;
    private long lastScanTick = -1;

    // Battle constants
    private double battlefieldWidth;
    private double battlefieldHeight;

    // Pipeline scan data (injected by Player from recordings)
    private boolean hasScanData;
    private double scanDistance;
    private double scanBearingDegrees;
    private double scanOppHeading;
    private double scanOppVelocity;
    private double scanOppEnergy;

    // Opponent fire detection
    private boolean opponentFired;
    private double opponentFirePower = Double.NaN;

    // Round result
    private int roundResult;

    /** Set a computed feature value. */
    public void setFeature(Feature f, double value) {
        if (Double.isInfinite(value)) {
            // Reject ±Infinity — NaN is the documented "missing" sentinel.
            value = Double.NaN;
        }
        features[f.ordinal()] = value;
    }

    /** Get a computed feature value. Returns NaN if not yet set. */
    public double getFeature(Feature f) {
        return features[f.ordinal()];
    }

    /** Reset all features to NaN at start of tick. */
    public void clearFeatures() {
        for (int i = 0; i < features.length; i++) {
            features[i] = Double.NaN;
        }
    }

    // --- Raw state accessors ---

    public void setTick(long tick) {
        this.tick = tick;
    }

    public long getTick() {
        return tick;
    }

    public void setOurPosition(double x, double y) {
        this.ourX = x;
        this.ourY = y;
    }

    public double getOurX() {
        return ourX;
    }

    public double getOurY() {
        return ourY;
    }

    public void setOurHeading(double heading) {
        this.ourHeading = heading;
    }

    public double getOurHeading() {
        return ourHeading;
    }

    public void setOurVelocity(double velocity) {
        this.ourVelocity = velocity;
    }

    public double getOurVelocity() {
        return ourVelocity;
    }

    public void setOurEnergy(double energy) {
        this.ourEnergy = energy;
    }

    public double getOurEnergy() {
        return ourEnergy;
    }

    public void setGunHeat(double heat) {
        this.gunHeat = heat;
    }

    public double getGunHeat() {
        return gunHeat;
    }

    public void setGunHeadingRadians(double heading) {
        this.gunHeadingRadians = heading;
    }

    public double getGunHeadingRadians() {
        return gunHeadingRadians;
    }

    public void setLastScan(ScannedRobotEvent e, long tick) {
        this.lastScan = e;
        this.lastScanTick = tick;
    }

    public ScannedRobotEvent getLastScan() {
        return lastScan;
    }

    public long getLastScanTick() {
        return lastScanTick;
    }

    public void setBattlefieldSize(double width, double height) {
        this.battlefieldWidth = width;
        this.battlefieldHeight = height;
    }

    public double getBattlefieldWidth() {
        return battlefieldWidth;
    }

    public double getBattlefieldHeight() {
        return battlefieldHeight;
    }

    // --- Pipeline injection (from Player when replaying recordings) ---

    /**
     * Inject scan data synthesized by the pipeline Player.
     * Equivalent to receiving a ScannedRobotEvent.
     */
    public void setScanData(long scanTick, double distance, double bearingDegrees,
            double oppHeadingRadians, double oppVelocity, double oppEnergy) {
        this.lastScanTick = scanTick;
        this.scanDistance = distance;
        this.scanBearingDegrees = bearingDegrees;
        this.scanOppHeading = oppHeadingRadians;
        this.scanOppVelocity = oppVelocity;
        this.scanOppEnergy = oppEnergy;
        this.hasScanData = true;
    }

    public boolean hasScanData() {
        return hasScanData;
    }

    public double getScanDistance() {
        return scanDistance;
    }

    public double getScanBearingDegrees() {
        return scanBearingDegrees;
    }

    public double getScanOppHeading() {
        return scanOppHeading;
    }

    public double getScanOppVelocity() {
        return scanOppVelocity;
    }

    public double getScanOppEnergy() {
        return scanOppEnergy;
    }

    /**
     * Record that the opponent fired with the given power (detected via energy
     * drop).
     */
    public void setOpponentFired(double firePower) {
        this.opponentFirePower = firePower;
        this.opponentFired = true;
    }

    public boolean hasOpponentFired() {
        return opponentFired;
    }

    public double getOpponentFirePower() {
        return opponentFirePower;
    }

    /** Set round result: 1=win, -1=loss, 0=tie */
    public void setRoundResult(int result) {
        this.roundResult = result;
    }

    public int getRoundResult() {
        return roundResult;
    }

    /** Call at start of each tick to reset per-tick flags. */
    public void advanceTick() {
        opponentFired = false;
        opponentFirePower = Double.NaN;
        hasScanData = false;
    }
}

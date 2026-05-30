package cz.zamboch.autopilot.pipeline;

import robocode.Bullet;
import robocode.Condition;
import robocode.Event;
import robocode.StatusEvent;
import robocode.robotinterfaces.peer.IAdvancedRobotPeer;

import java.awt.*;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A faithful re-implementation of the Robocode engine's gun/fire mechanics for
 * observer-mode Autopilot.
 * <p>
 * Replicates the exact algorithm from {@code RobotPeer} and
 * {@code BasicRobotProxy}:
 * <ul>
 * <li>{@link #updateState} provides authoritative position/heading/energy from
 * snapshots.
 * Gun heat is tracked internally (not from snapshot) to match the engine's
 * fire-then-cool sequence.</li>
 * <li>{@link #setTurnGun} stores gunTurnRemaining exactly as
 * {@code setTurnGunRightRadians}
 * does in the AdvancedRobotProxy — it REPLACES the remaining value.</li>
 * <li>{@link #getGunTurnRemaining} returns the value in RADIANS (same as the
 * internal
 * storage in BasicRobotProxy). The AdvancedRobot API wrapper converts to
 * degrees.</li>
 * <li>{@link #setFire} checks gun heat (including accumulated firedHeat within
 * the same
 * tick), clamps power to [MIN_BULLET_POWER, MAX_BULLET_POWER] and available
 * energy,
 * deducts energy, adds heat = 1 + power/5.</li>
 * <li>{@link #executeTick} simulates the engine's per-tick processing: cool gun
 * by
 * gunCoolingRate, reset firedHeat/firedEnergy accumulators (matching the
 * proxy's
 * reset after executeImpl).</li>
 * </ul>
 * <p>
 * Engine tick order (from Battle.runTurn):
 * <ol>
 * <li>loadCommands → fireBullets (processes fire commands from last
 * execute)</li>
 * <li>performMove → updateGunHeat (cool), updateGunHeading (rotate),
 * updateMovement</li>
 * <li>performScan → generate ScannedRobotEvent</li>
 * <li>publishStatuses → RobotStatus with current state</li>
 * <li>wakeupRobots → robot code runs (receives events, calls set* methods)</li>
 * </ol>
 * The observer sees the status AFTER cooling and rotation (step 4), then makes
 * decisions
 * (step 5). Fire commands are processed at step 1 of the NEXT tick.
 * <p>
 * All movement and radar commands are no-ops (state comes from snapshots).
 */
@SuppressWarnings("unchecked") // Robocode API uses raw List return types
public class ObserverRobotPeer implements IAdvancedRobotPeer {

    /** Max gun rotation per tick: 20 degrees in radians. */
    private static final double GUN_TURN_RATE_RADIANS = Math.toRadians(20);
    private static final double MIN_BULLET_POWER = 0.1;
    private static final double MAX_BULLET_POWER = 3.0;

    private final double battleFieldWidth;
    private final double battleFieldHeight;
    private final double gunCoolingRate;

    // --- Engine-tracked state ---
    private double gunHeat = 3.0;
    private double energy = 100.0;
    private double gunHeading;
    private double x;
    private double y;

    // --- Per-tick accumulators (reset by executeTick, mirroring BasicRobotProxy)
    // ---
    private double firedHeat;
    private double firedEnergy;

    // --- Gun turn tracking (mirrors ExecCommands.gunTurnRemaining) ---
    private double gunTurnRemaining;

    /**
     * Next bullet id. Mirrors {@code BasicRobotProxy.nextBulletId}: reset at the
     * start of each round to {@code 1 + robotIndex*10000 + roundNum*1000000}
     * (see {@link #resetRound(int, int)}), then pre-incremented on each fire so
     * the value matches the live engine's {@code Bullet.hashCode()} exactly. The
     * default base (robotIndex 0, round 0) keeps unit tests that fire without a
     * round reset deterministic.
     */
    private int nextBulletId = 1;

    /**
     * Read-only data directory for loading VCS data (e.g. vcs.dat).
     * When set, {@link #getDataFile(String)} returns files from this directory so
     * the observer loads the SAME persisted model the live robot loads, keyed by
     * OPPONENT_ID_HASH. The observer never writes here — the pipeline never calls
     * the observer's onBattleEnded, so no save path is exercised.
     */
    private File dataDir;

    /**
     * Debug properties published by the observer Autopilot this tick (via
     * {@link #setDebugProperty(String, String)}). Retained so the pipeline can dump
     * the observer's published state to {@code observer.csv} for apples-to-apples
     * diffing against the live robot's {@code in-game.csv} — both come from the
     * identical {@code Autopilot.doTurn} publish path. Cleared at the start of each
     * tick in {@link #executeTick()} so stale per-wave keys ({@code COLUMN/waveId})
     * do not linger after a wave resolves.
     */
    private final Map<String, String> debugProperties = new LinkedHashMap<>();

    public ObserverRobotPeer(double battleFieldWidth, double battleFieldHeight, double gunCoolingRate) {
        this.battleFieldWidth = battleFieldWidth;
        this.battleFieldHeight = battleFieldHeight;
        this.gunCoolingRate = gunCoolingRate;
    }

    /**
     * Provide authoritative position/heading/energy from snapshot data.
     * Called once per tick BEFORE the robot's event handlers and doTurn().
     * <p>
     * Gun heat is NOT taken from the snapshot — it is tracked internally to
     * replicate the engine's fire→cool sequence. Position, heading, and energy
     * are authoritative from the snapshot (movement physics are not simulated).
     */
    public void updateState(double x, double y, double gunHeading, double gunHeat, double energy) {
        this.x = x;
        this.y = y;
        this.gunHeading = gunHeading;
        // Gun heat is tracked internally — ignore snapshot gunHeat.
        // Energy is authoritative from snapshot (damage/ram events are external).
        this.energy = energy;
    }

    /**
     * Simulate one tick of engine processing. Call BEFORE feeding the next tick's
     * events. This mirrors the engine's performMove sequence:
     * <ol>
     * <li>Cool gun by gunCoolingRate (clamp to 0)</li>
     * <li>Reset per-tick accumulators (firedHeat, firedEnergy)</li>
     * </ol>
     * Gun heading rotation is NOT simulated — heading comes from snapshots.
     */
    public void executeTick() {
        // Clear debug properties from the previous tick so per-wave keys
        // (COLUMN/waveId) for waves that have since resolved do not linger.
        debugProperties.clear();
        // Engine: updateGunHeat() in performMove
        gunHeat -= gunCoolingRate;
        if (gunHeat < 0) {
            gunHeat = 0;
        }
        // Engine: BasicRobotProxy resets after executeImpl
        firedHeat = 0;
        firedEnergy = 0;
    }

    /**
     * Reset state for a new round. Mirrors engine's round-start initialization:
     * gunHeat=3.0, energy/position will come from first updateState() call.
     * <p>
     * Also reseeds {@link #nextBulletId} with the live engine's per-round formula
     * ({@code BasicRobotProxy.initialize}:
     * {@code 1 + robotIndex*10000 + roundNum*1000000}) so the first fire of the
     * round produces the same {@code Bullet.hashCode()} the live robot sees.
     *
     * @param robotIndex this robot's battle index (0 or 1 in a 1v1 battle)
     * @param roundNum   zero-based round number
     */
    public void resetRound(int robotIndex, int roundNum) {
        gunHeat = 3.0;
        firedHeat = 0;
        firedEnergy = 0;
        gunTurnRemaining = 0;
        nextBulletId = 1 + robotIndex * 10000 + roundNum * 1000000;
    }

    public double getGunHeat() {
        return gunHeat;
    }

    /**
     * Effective gun heat as seen by the robot code (includes pending fires this
     * tick).
     * Mirrors BasicRobotProxy.getGunHeatImpl().
     */
    public double getGunHeatImpl() {
        return gunHeat + firedHeat;
    }

    public double getEnergy() {
        return energy;
    }

    /**
     * Effective energy as seen by the robot code (excludes pending fires this
     * tick).
     * Mirrors BasicRobotProxy.getEnergyImpl().
     */
    public double getEnergyImpl() {
        return energy - firedEnergy;
    }

    // --- Firing (mirrors BasicRobotProxy.setFireImpl) ---

    @Override
    public Bullet setFire(double power) {
        if (Double.isNaN(power)) {
            return null;
        }
        if (getGunHeatImpl() > 0 || getEnergyImpl() <= 0) {
            return null;
        }
        // Clamp power to [MIN_BULLET_POWER, MAX_BULLET_POWER] and available energy
        power = Math.min(getEnergyImpl(),
                Math.min(Math.max(power, MIN_BULLET_POWER), MAX_BULLET_POWER));

        firedEnergy += power;
        firedHeat += 1.0 + power / 5.0;

        // In the engine, actual energy deduction and heat addition happen in
        // fireBullets() at loadCommands time. For observer, we apply immediately
        // (same net effect since executeTick resets accumulators).
        energy -= power;
        gunHeat += 1.0 + power / 5.0;

        // Pre-increment to match BasicRobotProxy.setFireImpl (nextBulletId++ first).
        nextBulletId++;
        return new Bullet(gunHeading, x, y, power, "Observer", null, true, nextBulletId);
    }

    @Override
    public Bullet fire(double power) {
        return setFire(power);
    }

    // --- Battlefield dimensions ---

    @Override
    public double getBattleFieldWidth() {
        return battleFieldWidth;
    }

    @Override
    public double getBattleFieldHeight() {
        return battleFieldHeight;
    }

    // --- Gun turn tracking (mirrors ExecCommands + BasicRobotProxy) ---

    @Override
    public void setTurnGun(double radians) {
        // Mirrors BasicRobotProxy.setTurnGunImpl: REPLACES gunTurnRemaining
        gunTurnRemaining = radians;
    }

    /**
     * Returns gun turn remaining in RADIANS.
     * Note: AdvancedRobot.getGunTurnRemaining() converts this to degrees via
     * Math.toDegrees(peer.getGunTurnRemaining()). The Autopilot code checks
     * Math.abs(getGunTurnRemaining()) < 5 which is in degrees (< 5°).
     */
    @Override
    public double getGunTurnRemaining() {
        return gunTurnRemaining;
    }

    // --- Movement commands (no-ops — state comes from snapshots) ---

    @Override
    public void setMove(double distance) {
    }

    @Override
    public void setTurnBody(double radians) {
    }

    @Override
    public void setTurnRadar(double radians) {
    }

    @Override
    public void setMaxTurnRate(double newTurnRate) {
    }

    @Override
    public void setMaxVelocity(double newVelocity) {
    }

    @Override
    public void setResume() {
    }

    @Override
    public void setStop(boolean overwrite) {
    }

    @Override
    public void move(double distance) {
    }

    @Override
    public void turnBody(double radians) {
    }

    @Override
    public void turnGun(double radians) {
    }

    @Override
    public void turnRadar(double radians) {
    }

    // --- Getters returning 0 or default ---

    @Override
    public double getDistanceRemaining() {
        return 0;
    }

    @Override
    public double getRadarTurnRemaining() {
        return 0;
    }

    public String getName() {
        return "Observer";
    }

    @Override
    public long getTime() {
        return 0;
    }

    @Override
    public double getBodyHeading() {
        return 0;
    }

    @Override
    public double getGunHeading() {
        return gunHeading;
    }

    @Override
    public double getRadarHeading() {
        return 0;
    }

    @Override
    public double getVelocity() {
        return 0;
    }

    @Override
    public double getX() {
        return x;
    }

    @Override
    public double getY() {
        return y;
    }

    @Override
    public double getBodyTurnRemaining() {
        return 0;
    }

    @Override
    public double getGunCoolingRate() {
        return gunCoolingRate;
    }

    @Override
    public int getOthers() {
        return 1;
    }

    @Override
    public int getNumSentries() {
        return 0;
    }

    @Override
    public int getSentryBorderSize() {
        return 0;
    }

    @Override
    public int getNumRounds() {
        return 1;
    }

    @Override
    public int getRoundNum() {
        return 0;
    }

    @Override
    public Graphics2D getGraphics() {
        return null;
    }

    // --- Event management (no-ops) ---

    @Override
    public void setInterruptible(boolean interruptable) {
    }

    @Override
    public void setEventPriority(String eventClass, int priority) {
    }

    @Override
    public int getEventPriority(String eventClass) {
        return 0;
    }

    @Override
    public void addCustomEvent(Condition condition) {
    }

    @Override
    public void removeCustomEvent(Condition condition) {
    }

    @Override
    public void clearAllEvents() {
    }

    @Override
    public List<Event> getAllEvents() {
        return List.of();
    }

    @Override
    public List<StatusEvent> getStatusEvents() {
        return List.of();
    }

    @Override
    public List getBulletMissedEvents() {
        return List.of();
    }

    @Override
    public List getBulletHitBulletEvents() {
        return List.of();
    }

    @Override
    public List getBulletHitEvents() {
        return List.of();
    }

    @Override
    public List getHitByBulletEvents() {
        return List.of();
    }

    @Override
    public List getHitRobotEvents() {
        return List.of();
    }

    @Override
    public List getHitWallEvents() {
        return List.of();
    }

    @Override
    public List getRobotDeathEvents() {
        return List.of();
    }

    @Override
    public List getScannedRobotEvents() {
        return List.of();
    }

    @Override
    public void waitFor(Condition condition) {
    }

    // --- Data / IO ---

    /**
     * Point the observer at a read-only data directory for VCS loading.
     * The observer reads (never writes) from here.
     */
    public void setDataDir(File dataDir) {
        this.dataDir = dataDir;
    }

    @Override
    public File getDataDirectory() {
        return dataDir;
    }

    @Override
    public File getDataFile(String filename) {
        return dataDir == null ? null : new File(dataDir, filename);
    }

    @Override
    public long getDataQuotaAvailable() {
        return 0;
    }

    // --- Output (no-ops) ---

    @Override
    public void setDebugProperty(String key, String value) {
        debugProperties.put(key, value);
    }

    /**
     * The debug properties the observer Autopilot published this tick. Mirrors the
     * live robot's {@code IRobotSnapshot.getDebugProperties()} (same publish path),
     * for {@code observer.csv} dumping.
     */
    public Map<String, String> getDebugProperties() {
        return debugProperties;
    }

    @Override
    public void rescan() {
    }

    // --- Adjust flags ---

    @Override
    public void setAdjustGunForBodyTurn(boolean adjust) {
    }

    @Override
    public void setAdjustRadarForBodyTurn(boolean adjust) {
    }

    @Override
    public void setAdjustRadarForGunTurn(boolean adjust) {
    }

    @Override
    public boolean isAdjustGunForBodyTurn() {
        return true;
    }

    @Override
    public boolean isAdjustRadarForBodyTurn() {
        return true;
    }

    @Override
    public boolean isAdjustRadarForGunTurn() {
        return true;
    }

    // --- Misc ---

    @Override
    public void execute() {
    }

    @Override
    public void setBodyColor(Color color) {
    }

    @Override
    public void setGunColor(Color color) {
    }

    @Override
    public void setRadarColor(Color color) {
    }

    @Override
    public void setBulletColor(Color color) {
    }

    @Override
    public void setScanColor(Color color) {
    }

    public void getCall() {
    }

    public void setCall() {
    }

    public void println(String s) {
    }

    public void print(String s) {
    }

    // --- IStandardRobotPeer blocking methods ---

    @Override
    public void stop(boolean overwrite) {
    }

    @Override
    public void resume() {
    }
}

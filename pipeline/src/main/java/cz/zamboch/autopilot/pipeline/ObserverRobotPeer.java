package cz.zamboch.autopilot.pipeline;

import robocode.Bullet;
import robocode.Condition;
import robocode.Event;
import robocode.StatusEvent;
import robocode.robotinterfaces.peer.IAdvancedRobotPeer;

import java.awt.*;
import java.io.File;
import java.util.List;

/**
 * A no-op implementation of {@link IAdvancedRobotPeer} for observer-mode Autopilot.
 * <p>
 * Tracks gun heat and energy, supports firing (returns a {@link Bullet} when the gun
 * is cool and energy is sufficient). All movement and radar commands are no-ops.
 */
public class ObserverRobotPeer implements IAdvancedRobotPeer {

    private final double battleFieldWidth;
    private final double battleFieldHeight;
    private final double gunCoolingRate;

    private double gunHeat = 3.0;
    private double energy = 100.0;
    private int bulletIdCounter = 1;

    // Tracking heading for Bullet construction
    private double gunHeading;
    private double x;
    private double y;

    public ObserverRobotPeer(double battleFieldWidth, double battleFieldHeight, double gunCoolingRate) {
        this.battleFieldWidth = battleFieldWidth;
        this.battleFieldHeight = battleFieldHeight;
        this.gunCoolingRate = gunCoolingRate;
    }

    /** Call each tick to reduce gun heat by the cooling rate. */
    public void coolGun() {
        gunHeat = Math.max(0, gunHeat - gunCoolingRate);
    }

    /** Update position/heading from externally-fed StatusEvent data. */
    public void updateState(double x, double y, double gunHeading, double gunHeat, double energy) {
        this.x = x;
        this.y = y;
        this.gunHeading = gunHeading;
        this.gunHeat = gunHeat;
        this.energy = energy;
    }

    public double getGunHeat() {
        return gunHeat;
    }

    public double getEnergy() {
        return energy;
    }

    // --- Firing ---

    @Override
    public Bullet setFire(double power) {
        if (gunHeat > 0 || energy <= 0 || power <= 0) {
            return null;
        }
        double actualPower = Math.min(power, energy);
        energy -= actualPower;
        gunHeat = 1.0 + actualPower / 5.0;
        return new Bullet(gunHeading, x, y, actualPower, "Observer", null, true, bulletIdCounter++);
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

    // --- Movement commands (no-ops) ---

    @Override
    public void setMove(double distance) { }

    @Override
    public void setTurnBody(double radians) { }

    @Override
    public void setTurnGun(double radians) { }

    @Override
    public void setTurnRadar(double radians) { }

    @Override
    public void setMaxTurnRate(double newTurnRate) { }

    @Override
    public void setMaxVelocity(double newVelocity) { }

    @Override
    public void setResume() { }

    @Override
    public void setStop(boolean overwrite) { }

    @Override
    public void move(double distance) { }

    @Override
    public void turnBody(double radians) { }

    @Override
    public void turnGun(double radians) { }

    @Override
    public void turnRadar(double radians) { }

    // --- Getters returning 0 or default ---

    @Override
    public double getDistanceRemaining() { return 0; }

    @Override
    public double getGunTurnRemaining() { return 0; }

    @Override
    public double getRadarTurnRemaining() { return 0; }

    public String getName() { return "Observer"; }

    @Override
    public long getTime() { return 0; }

    @Override
    public double getBodyHeading() { return 0; }

    @Override
    public double getGunHeading() { return gunHeading; }

    @Override
    public double getRadarHeading() { return 0; }

    @Override
    public double getVelocity() { return 0; }

    @Override
    public double getX() { return x; }

    @Override
    public double getY() { return y; }

    @Override
    public double getBodyTurnRemaining() { return 0; }

    @Override
    public double getGunCoolingRate() { return gunCoolingRate; }

    @Override
    public int getOthers() { return 1; }

    @Override
    public int getNumSentries() { return 0; }

    @Override
    public int getSentryBorderSize() { return 0; }

    @Override
    public int getNumRounds() { return 1; }

    @Override
    public int getRoundNum() { return 0; }

    @Override
    public Graphics2D getGraphics() { return null; }

    // --- Event management (no-ops) ---

    @Override
    public void setInterruptible(boolean interruptable) { }

    @Override
    public void setEventPriority(String eventClass, int priority) { }

    @Override
    public int getEventPriority(String eventClass) { return 0; }

    @Override
    public void addCustomEvent(Condition condition) { }

    @Override
    public void removeCustomEvent(Condition condition) { }

    @Override
    public void clearAllEvents() { }

    @Override
    public List<Event> getAllEvents() { return List.of(); }

    @Override
    public List<StatusEvent> getStatusEvents() { return List.of(); }

    @Override
    @SuppressWarnings("unchecked")
    public List getBulletMissedEvents() { return List.of(); }

    @Override
    @SuppressWarnings("unchecked")
    public List getBulletHitBulletEvents() { return List.of(); }

    @Override
    @SuppressWarnings("unchecked")
    public List getBulletHitEvents() { return List.of(); }

    @Override
    @SuppressWarnings("unchecked")
    public List getHitByBulletEvents() { return List.of(); }

    @Override
    @SuppressWarnings("unchecked")
    public List getHitRobotEvents() { return List.of(); }

    @Override
    @SuppressWarnings("unchecked")
    public List getHitWallEvents() { return List.of(); }

    @Override
    @SuppressWarnings("unchecked")
    public List getRobotDeathEvents() { return List.of(); }

    @Override
    @SuppressWarnings("unchecked")
    public List getScannedRobotEvents() { return List.of(); }

    @Override
    public void waitFor(Condition condition) { }

    // --- Data / IO ---

    @Override
    public File getDataDirectory() { return null; }

    @Override
    public File getDataFile(String filename) { return null; }

    @Override
    public long getDataQuotaAvailable() { return 0; }

    // --- Output (no-ops) ---

    @Override
    public void setDebugProperty(String key, String value) { }

    @Override
    public void rescan() { }

    // --- Adjust flags ---

    @Override
    public void setAdjustGunForBodyTurn(boolean adjust) { }

    @Override
    public void setAdjustRadarForBodyTurn(boolean adjust) { }

    @Override
    public void setAdjustRadarForGunTurn(boolean adjust) { }

    @Override
    public boolean isAdjustGunForBodyTurn() { return true; }

    @Override
    public boolean isAdjustRadarForBodyTurn() { return true; }

    @Override
    public boolean isAdjustRadarForGunTurn() { return true; }

    // --- Misc ---

    @Override
    public void execute() { }

    @Override
    public void setBodyColor(Color color) { }

    @Override
    public void setGunColor(Color color) { }

    @Override
    public void setRadarColor(Color color) { }

    @Override
    public void setBulletColor(Color color) { }

    @Override
    public void setScanColor(Color color) { }

    public void getCall() { }

    public void setCall() { }

    public void println(String s) { }

    public void print(String s) { }

    // --- IStandardRobotPeer blocking methods ---

    @Override
    public void stop(boolean overwrite) { }

    @Override
    public void resume() { }
}

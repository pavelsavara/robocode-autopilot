package cz.zamboch;

import cz.zamboch.autopilot.core.Transformer;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.EnergyFeatures;
import cz.zamboch.autopilot.core.features.MovementFeatures;
import cz.zamboch.autopilot.core.features.SpatialFeatures;
import cz.zamboch.autopilot.core.features.TimingFeatures;
import robocode.AdvancedRobot;
import robocode.BulletHitEvent;
import robocode.HitByBulletEvent;
import robocode.RobotDeathEvent;
import robocode.RoundEndedEvent;
import robocode.ScannedRobotEvent;
import robocode.StatusEvent;
import robocode.WinEvent;
import robocode.DeathEvent;
import robocode.Rules;

/**
 * Competition robot skeleton. Receives events from the Robocode engine,
 * forwards to Whiteboard, computes features via Transformer.
 * ML decision module is a future addition.
 */
public final class Autopilot extends AdvancedRobot {
    private Whiteboard whiteboard;
    private Transformer transformer;

    @Override
    public void run() {
        whiteboard = new Whiteboard();
        transformer = createTransformer();

        whiteboard.onRoundStart(getRoundNum(), (int) getBattleFieldWidth(),
                (int) getBattleFieldHeight(), getGunCoolingRate(),
                getNumRounds());

        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true);

        while (true) {
            setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
            execute();
        }
    }

    @Override
    public void onStatus(StatusEvent e) {
        whiteboard.advanceTick();
        whiteboard.setTick(e.getStatus().getTime());
        whiteboard.setOurState(
                e.getStatus().getX(),
                e.getStatus().getY(),
                Math.toRadians(e.getStatus().getHeading()),
                Math.toRadians(e.getStatus().getGunHeading()),
                Math.toRadians(e.getStatus().getRadarHeading()),
                e.getStatus().getVelocity(),
                e.getStatus().getEnergy(),
                e.getStatus().getGunHeat()
        );
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        // Convert relative bearing + distance to absolute opponent position
        double absBearing = Math.toRadians(getHeading()) + Math.toRadians(e.getBearing());
        double oppX = getX() + e.getDistance() * Math.sin(absBearing);
        double oppY = getY() + e.getDistance() * Math.cos(absBearing);

        whiteboard.setOpponentScan(
                oppX,
                oppY,
                Math.toRadians(e.getHeading()),
                e.getVelocity(),
                e.getEnergy()
        );

        transformer.process(whiteboard);
    }

    @Override
    public void onBulletHit(BulletHitEvent e) {
        whiteboard.setWeHitOpponentThisTick(true);
        whiteboard.incrementOurBulletHitCount();
        double power = e.getBullet().getPower();
        whiteboard.addDamageDealt(Rules.getBulletDamage(power));
    }

    @Override
    public void onHitByBullet(HitByBulletEvent e) {
        whiteboard.incrementOpponentBulletHitCount();
        double power = e.getBullet().getPower();
        whiteboard.addDamageReceived(Rules.getBulletDamage(power));
    }

    @Override
    public void onWin(WinEvent e) {
        whiteboard.incrementRoundsWon();
    }

    @Override
    public void onDeath(DeathEvent e) {
        whiteboard.incrementRoundsLost();
    }

    private static Transformer createTransformer() {
        Transformer t = new Transformer();
        t.register(new SpatialFeatures());
        t.register(new MovementFeatures());
        t.register(new EnergyFeatures());
        t.register(new TimingFeatures());
        t.resolveDependencies();
        return t;
    }
}

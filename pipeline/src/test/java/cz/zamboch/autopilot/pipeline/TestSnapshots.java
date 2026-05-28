package cz.zamboch.autopilot.pipeline;

import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IDebugProperty;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.IScoreSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.RobotState;

/**
 * Factory for lightweight snapshot test doubles.
 */
final class TestSnapshots {

    private TestSnapshots() {
    }

    static ITurnSnapshot turn(int tick, IRobotSnapshot robotA, IRobotSnapshot robotB) {
        return new StubTurn(tick, new IRobotSnapshot[] { robotA, robotB }, new IBulletSnapshot[0]);
    }

    static ITurnSnapshot turn(int tick, IRobotSnapshot robotA, IRobotSnapshot robotB,
            IBulletSnapshot... bullets) {
        return new StubTurn(tick, new IRobotSnapshot[] { robotA, robotB }, bullets);
    }

    static IBulletSnapshot bullet(int bulletId, int ownerIndex, int victimIndex,
            double power, BulletState state) {
        return new StubBullet(bulletId, ownerIndex, victimIndex, power, state);
    }

    static IRobotSnapshot robot(double x, double y, double bodyHeading,
            double velocity, double energy, double gunHeat,
            double gunHeading, double radarHeading,
            int contestantIndex, RobotState state, String shortName) {
        return new StubRobot(x, y, bodyHeading, velocity, energy, gunHeat,
                gunHeading, radarHeading, contestantIndex, state, shortName);
    }

    static IRobotSnapshot robot(double x, double y, int contestantIndex, String name) {
        return robot(x, y, 0, 0, 100, 0, 0, 0, contestantIndex, RobotState.ACTIVE, name);
    }

    static IRobotSnapshot robotWithDebug(double x, double y, int contestantIndex, String name,
            IDebugProperty[] debugProperties) {
        return new StubRobot(x, y, 0, 0, 100, 0, 0, 0, contestantIndex, RobotState.ACTIVE, name,
                debugProperties);
    }

    static IDebugProperty debugProperty(String key, String value) {
        return new StubDebugProperty(key, value);
    }

    // ========== Stub implementations ==========

    private static final class StubTurn implements ITurnSnapshot {
        private final int tick;
        private final IRobotSnapshot[] robots;
        private final IBulletSnapshot[] bullets;

        StubTurn(int tick, IRobotSnapshot[] robots, IBulletSnapshot[] bullets) {
            this.tick = tick;
            this.robots = robots;
            this.bullets = bullets;
        }

        public IRobotSnapshot[] getRobots() {
            return robots;
        }

        public IBulletSnapshot[] getBullets() {
            return bullets;
        }

        public int getTPS() {
            return 0;
        }

        public int getRound() {
            return 0;
        }

        public int getTurn() {
            return tick;
        }

        public IScoreSnapshot[] getSortedTeamScores() {
            return null;
        }

        public IScoreSnapshot[] getIndexedTeamScores() {
            return null;
        }
    }

    private static final class StubRobot implements IRobotSnapshot {
        private final double x, y, bodyHeading, velocity, energy, gunHeat;
        private final double gunHeading, radarHeading;
        private final int contestantIndex;
        private final RobotState state;
        private final String shortName;
        private final IDebugProperty[] debugProperties;

        StubRobot(double x, double y, double bodyHeading, double velocity,
                double energy, double gunHeat, double gunHeading, double radarHeading,
                int contestantIndex, RobotState state, String shortName) {
            this(x, y, bodyHeading, velocity, energy, gunHeat, gunHeading, radarHeading,
                    contestantIndex, state, shortName, null);
        }

        StubRobot(double x, double y, double bodyHeading, double velocity,
                double energy, double gunHeat, double gunHeading, double radarHeading,
                int contestantIndex, RobotState state, String shortName,
                IDebugProperty[] debugProperties) {
            this.x = x;
            this.y = y;
            this.bodyHeading = bodyHeading;
            this.velocity = velocity;
            this.energy = energy;
            this.gunHeat = gunHeat;
            this.gunHeading = gunHeading;
            this.radarHeading = radarHeading;
            this.contestantIndex = contestantIndex;
            this.state = state;
            this.shortName = shortName;
            this.debugProperties = debugProperties;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        public double getBodyHeading() {
            return bodyHeading;
        }

        public double getGunHeading() {
            return gunHeading;
        }

        public double getRadarHeading() {
            return radarHeading;
        }

        public double getVelocity() {
            return velocity;
        }

        public double getEnergy() {
            return energy;
        }

        public double getGunHeat() {
            return gunHeat;
        }

        public int getContestantIndex() {
            return contestantIndex;
        }

        public RobotState getState() {
            return state;
        }

        public String getShortName() {
            return shortName;
        }

        public String getName() {
            return shortName;
        }

        public String getVeryShortName() {
            return shortName;
        }

        public String getTeamName() {
            return null;
        }

        public int getRobotIndex() {
            return contestantIndex;
        }

        public int getTeamIndex() {
            return -1;
        }

        public int getBodyColor() {
            return 0;
        }

        public int getGunColor() {
            return 0;
        }

        public int getRadarColor() {
            return 0;
        }

        public int getScanColor() {
            return 0;
        }

        public boolean isDroid() {
            return false;
        }

        public boolean isSentryRobot() {
            return false;
        }

        public boolean isPaintRobot() {
            return false;
        }

        public boolean isPaintEnabled() {
            return false;
        }

        public boolean isSGPaintEnabled() {
            return false;
        }

        public IDebugProperty[] getDebugProperties() {
            return debugProperties;
        }

        public String getOutputStreamSnapshot() {
            return null;
        }

        public IScoreSnapshot getScoreSnapshot() {
            return null;
        }
    }

    // ========== Bullet Stub ==========

    private static final class StubBullet implements IBulletSnapshot {
        private final int bulletId;
        private final int ownerIndex;
        private final int victimIndex;
        private final double power;
        private final BulletState state;

        StubBullet(int bulletId, int ownerIndex, int victimIndex, double power, BulletState state) {
            this.bulletId = bulletId;
            this.ownerIndex = ownerIndex;
            this.victimIndex = victimIndex;
            this.power = power;
            this.state = state;
        }

        public BulletState getState() {
            return state;
        }

        public double getPower() {
            return power;
        }

        public double getX() {
            return 0;
        }

        public double getY() {
            return 0;
        }

        public double getPaintX() {
            return 0;
        }

        public double getPaintY() {
            return 0;
        }

        public int getColor() {
            return 0;
        }

        public int getFrame() {
            return 0;
        }

        public boolean isExplosion() {
            return state == BulletState.HIT_VICTIM;
        }

        public int getExplosionImageIndex() {
            return 0;
        }

        public int getBulletId() {
            return bulletId;
        }

        public double getHeading() {
            return 0;
        }

        public int getOwnerIndex() {
            return ownerIndex;
        }

        public int getVictimIndex() {
            return victimIndex;
        }
    }

    // ========== Debug Property Stub ==========

    private static final class StubDebugProperty implements IDebugProperty {
        private final String key;
        private final String value;

        StubDebugProperty(String key, String value) {
            this.key = key;
            this.value = value;
        }

        public String getKey() {
            return key;
        }

        public String getValue() {
            return value;
        }
    }
}

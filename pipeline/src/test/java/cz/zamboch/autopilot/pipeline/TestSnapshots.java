package cz.zamboch.autopilot.pipeline;

import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IDebugProperty;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.IScoreSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;

/**
 * Factory for lightweight snapshot test doubles. All unused interface methods
 * return safe defaults (0, null, false). No external mocking framework needed.
 */
final class TestSnapshots {

    private TestSnapshots() {}

    /** Create a turn snapshot with the given tick, robots, and optional bullets. */
    static ITurnSnapshot turn(int tick, IRobotSnapshot[] robots, IBulletSnapshot... bullets) {
        return new StubTurn(tick, robots, bullets);
    }

    /** Create a turn snapshot with two robots and no bullets. */
    static ITurnSnapshot turn(int tick, IRobotSnapshot robotA, IRobotSnapshot robotB) {
        return turn(tick, new IRobotSnapshot[]{robotA, robotB});
    }

    /** Create a robot snapshot with full parameters. */
    static IRobotSnapshot robot(double x, double y, double bodyHeading,
                                double velocity, double energy, double gunHeat,
                                double gunHeading, double radarHeading,
                                int contestantIndex, RobotState state, String shortName) {
        return new StubRobot(x, y, bodyHeading, velocity, energy, gunHeat,
                gunHeading, radarHeading, contestantIndex, state, shortName);
    }

    /** Create a simple active robot at a position. */
    static IRobotSnapshot robot(double x, double y, int contestantIndex) {
        return robot(x, y, 0, 0, 100, 0, 0, 0, contestantIndex, RobotState.ACTIVE,
                "Robot" + contestantIndex);
    }

    /** Create a bullet snapshot. */
    static IBulletSnapshot bullet(int id, int owner, int victim, double power, BulletState state) {
        return new StubBullet(id, owner, victim, power, state);
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

        public IRobotSnapshot[] getRobots() { return robots; }
        public IBulletSnapshot[] getBullets() { return bullets; }
        public int getTPS() { return 0; }
        public int getRound() { return 0; }
        public int getTurn() { return tick; }
        public IScoreSnapshot[] getSortedTeamScores() { return null; }
        public IScoreSnapshot[] getIndexedTeamScores() { return null; }
    }

    private static final class StubRobot implements IRobotSnapshot {
        private final double x, y, bodyHeading, velocity, energy, gunHeat;
        private final double gunHeading, radarHeading;
        private final int contestantIndex;
        private final RobotState state;
        private final String shortName;

        StubRobot(double x, double y, double bodyHeading, double velocity,
                  double energy, double gunHeat, double gunHeading, double radarHeading,
                  int contestantIndex, RobotState state, String shortName) {
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
        }

        public double getX() { return x; }
        public double getY() { return y; }
        public double getBodyHeading() { return bodyHeading; }
        public double getGunHeading() { return gunHeading; }
        public double getRadarHeading() { return radarHeading; }
        public double getVelocity() { return velocity; }
        public double getEnergy() { return energy; }
        public double getGunHeat() { return gunHeat; }
        public int getContestantIndex() { return contestantIndex; }
        public RobotState getState() { return state; }
        public String getShortName() { return shortName; }

        // Unused interface methods
        public String getName() { return shortName; }
        public String getVeryShortName() { return shortName; }
        public String getTeamName() { return null; }
        public int getRobotIndex() { return contestantIndex; }
        public int getTeamIndex() { return -1; }
        public int getBodyColor() { return 0; }
        public int getGunColor() { return 0; }
        public int getRadarColor() { return 0; }
        public int getScanColor() { return 0; }
        public boolean isDroid() { return false; }
        public boolean isSentryRobot() { return false; }
        public boolean isPaintRobot() { return false; }
        public boolean isPaintEnabled() { return false; }
        public boolean isSGPaintEnabled() { return false; }
        public IDebugProperty[] getDebugProperties() { return null; }
        public String getOutputStreamSnapshot() { return null; }
        public IScoreSnapshot getScoreSnapshot() { return null; }
    }

    private static final class StubBullet implements IBulletSnapshot {
        private final int bulletId, ownerIndex, victimIndex;
        private final double power;
        private final BulletState state;

        StubBullet(int bulletId, int ownerIndex, int victimIndex,
                   double power, BulletState state) {
            this.bulletId = bulletId;
            this.ownerIndex = ownerIndex;
            this.victimIndex = victimIndex;
            this.power = power;
            this.state = state;
        }

        public BulletState getState() { return state; }
        public double getPower() { return power; }
        public int getBulletId() { return bulletId; }
        public int getOwnerIndex() { return ownerIndex; }
        public int getVictimIndex() { return victimIndex; }

        // Unused interface methods
        public double getX() { return 0; }
        public double getY() { return 0; }
        public double getPaintX() { return 0; }
        public double getPaintY() { return 0; }
        public int getColor() { return 0; }
        public int getFrame() { return 0; }
        public boolean isExplosion() { return false; }
        public int getExplosionImageIndex() { return 0; }
        public double getHeading() { return 0; }
    }
}

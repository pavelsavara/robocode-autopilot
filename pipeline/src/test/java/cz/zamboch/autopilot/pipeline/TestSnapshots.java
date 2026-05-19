package cz.zamboch.autopilot.pipeline;

import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IDebugProperty;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.IScoreSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
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
            return null;
        }

        public String getOutputStreamSnapshot() {
            return null;
        }

        public IScoreSnapshot getScoreSnapshot() {
            return null;
        }
    }
}

package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import robocode.Rules;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class DamageDetectorTest {

        private DamageDetector detector;
        private Perspective[] perspectives;
        private Whiteboard wb0;
        private Whiteboard wb1;

        @BeforeEach
        void setUp() {
                detector = new DamageDetector();
                wb0 = new Whiteboard();
                wb1 = new Whiteboard();
                perspectives = Perspective.createPair(wb0, wb1);
        }

        @Test
        void detectsBulletHitFromOwnerToVictim() {
                // Robot 0 hits Robot 1 with power 2.0
                ITurnSnapshot turn = TestSnapshots.turn(5,
                                TestSnapshots.robot(100, 100, 0, "A"),
                                TestSnapshots.robot(300, 300, 1, "B"),
                                TestSnapshots.bullet(1, 0, 1, 2.0, BulletState.HIT_VICTIM));

                detector.detectBulletHits(turn, perspectives);
                detector.flushToWhiteboard(perspectives);

                // Perspective 0 (owner) should see OUR_BULLET_DAMAGE_TO_OPPONENT
                double expectedDamage = Rules.getBulletDamage(2.0); // 4*2 + 2*(2-1) = 10
                assertEquals(expectedDamage, wb0.getFeature(Feature.OUR_BULLET_DAMAGE_TO_OPPONENT), 1e-9);

                // Perspective 1 (victim) should see OPPONENT_BULLET_ENERGY_GAIN
                double expectedGain = Rules.getBulletHitBonus(2.0); // 3*2 = 6
                assertEquals(expectedGain, wb1.getFeature(Feature.OPPONENT_BULLET_ENERGY_GAIN), 1e-9);
        }

        @Test
        void doesNotDoubleCountSameBullet() {
                ITurnSnapshot turn1 = TestSnapshots.turn(5,
                                TestSnapshots.robot(100, 100, 0, "A"),
                                TestSnapshots.robot(300, 300, 1, "B"),
                                TestSnapshots.bullet(42, 0, 1, 1.5, BulletState.HIT_VICTIM));

                detector.detectBulletHits(turn1, perspectives);
                detector.flushToWhiteboard(perspectives);

                // Same bullet persists in next turn snapshot
                ITurnSnapshot turn2 = TestSnapshots.turn(6,
                                TestSnapshots.robot(100, 100, 0, "A"),
                                TestSnapshots.robot(300, 300, 1, "B"),
                                TestSnapshots.bullet(42, 0, 1, 1.5, BulletState.HIT_VICTIM));

                detector.detectBulletHits(turn2, perspectives);
                detector.flushToWhiteboard(perspectives);

                // Should only count once
                double expectedDamage = Rules.getBulletDamage(1.5);
                assertEquals(expectedDamage, wb0.getFeature(Feature.OUR_BULLET_DAMAGE_TO_OPPONENT), 1e-9);
        }

        @Test
        void accumulatesMultipleBulletHits() {
                ITurnSnapshot turn = TestSnapshots.turn(5,
                                TestSnapshots.robot(100, 100, 0, "A"),
                                TestSnapshots.robot(300, 300, 1, "B"),
                                TestSnapshots.bullet(1, 0, 1, 1.0, BulletState.HIT_VICTIM),
                                TestSnapshots.bullet(2, 0, 1, 2.0, BulletState.HIT_VICTIM));

                detector.detectBulletHits(turn, perspectives);
                detector.flushToWhiteboard(perspectives);

                double expectedDamage = Rules.getBulletDamage(1.0) + Rules.getBulletDamage(2.0);
                assertEquals(expectedDamage, wb0.getFeature(Feature.OUR_BULLET_DAMAGE_TO_OPPONENT), 1e-9);
        }

        @Test
        void ignoresNonHitBullets() {
                ITurnSnapshot turn = TestSnapshots.turn(5,
                                TestSnapshots.robot(100, 100, 0, "A"),
                                TestSnapshots.robot(300, 300, 1, "B"),
                                TestSnapshots.bullet(1, 0, 1, 2.0, BulletState.FIRED));

                detector.detectBulletHits(turn, perspectives);

                assertTrue(Double.isNaN(wb0.getFeature(Feature.OUR_BULLET_DAMAGE_TO_OPPONENT)));
        }

        @Test
        void detectsRamFromRobotState() {
                IRobotSnapshot robotA = TestSnapshots.robot(
                                100, 100, 0, 0, 100, 0, 0, 0, 0, RobotState.HIT_ROBOT, "A");
                IRobotSnapshot robotB = TestSnapshots.robot(
                                118, 100, 0, 0, 100, 0, 0, 0, 1, RobotState.ACTIVE, "B");

                detector.detectRams(new IRobotSnapshot[] { robotA, robotB }, perspectives);
                detector.flushToWhiteboard(perspectives);

                // Robot A hit Robot B → both perspectives accumulate ram damage
                assertEquals(Rules.ROBOT_HIT_DAMAGE, wb0.getFeature(Feature.RAM_DAMAGE_TO_OPPONENT), 1e-9);
                assertEquals(Rules.ROBOT_HIT_DAMAGE, wb1.getFeature(Feature.RAM_DAMAGE_TO_OPPONENT), 1e-9);
        }

        @Test
        void noRamWhenBothActive() {
                IRobotSnapshot robotA = TestSnapshots.robot(
                                100, 100, 0, 0, 100, 0, 0, 0, 0, RobotState.ACTIVE, "A");
                IRobotSnapshot robotB = TestSnapshots.robot(
                                300, 300, 0, 0, 100, 0, 0, 0, 1, RobotState.ACTIVE, "B");

                detector.detectRams(new IRobotSnapshot[] { robotA, robotB }, perspectives);

                assertTrue(Double.isNaN(wb0.getFeature(Feature.RAM_DAMAGE_TO_OPPONENT)));
                assertTrue(Double.isNaN(wb1.getFeature(Feature.RAM_DAMAGE_TO_OPPONENT)));
        }

        @Test
        void resetClearsBulletIdTracking() {
                ITurnSnapshot turn1 = TestSnapshots.turn(5,
                                TestSnapshots.robot(100, 100, 0, "A"),
                                TestSnapshots.robot(300, 300, 1, "B"),
                                TestSnapshots.bullet(1, 0, 1, 2.0, BulletState.HIT_VICTIM));

                detector.detectBulletHits(turn1, perspectives);
                detector.flushToWhiteboard(perspectives);
                detector.reset();

                // After reset, same bullet ID should be counted again
                ITurnSnapshot turn2 = TestSnapshots.turn(10,
                                TestSnapshots.robot(100, 100, 0, "A"),
                                TestSnapshots.robot(300, 300, 1, "B"),
                                TestSnapshots.bullet(1, 0, 1, 2.0, BulletState.HIT_VICTIM));

                detector.detectBulletHits(turn2, perspectives);
                detector.flushToWhiteboard(perspectives);

                // reset() zeroes accumulators, so only the second hit is in the current flush
                double expectedDamage = Rules.getBulletDamage(2.0);
                assertEquals(expectedDamage, wb0.getFeature(Feature.OUR_BULLET_DAMAGE_TO_OPPONENT), 1e-9);
        }
}

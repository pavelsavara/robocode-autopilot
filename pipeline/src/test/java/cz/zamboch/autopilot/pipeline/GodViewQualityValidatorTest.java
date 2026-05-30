package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.Whiteboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class GodViewQualityValidatorTest {

        private static final double BF_WIDTH = 800;
        private static final double BF_HEIGHT = 600;

        private GodViewQualityValidator validator;

        @BeforeEach
        void setUp() {
                validator = new GodViewQualityValidator(BF_WIDTH, BF_HEIGHT);
        }

        // ========== Layer 1: Spatial ==========

        @Test
        void spatialChecks_perfectMatch() {
                Whiteboard wb = new Whiteboard();
                double selfX = 100, selfY = 200, selfHeading = 1.0, selfVelocity = 6.0;
                double selfEnergy = 80, gunHeat = 0.5, gunHeading = 1.5, radarHeading = 2.0;
                double oppX = 500, oppY = 400, oppHeading = 0.3, oppVelocity = -4.0, oppEnergy = 90;

                // Set whiteboard features
                wb.setFeature(Feature.OUR_X, selfX);
                wb.setFeature(Feature.OUR_Y, selfY);
                wb.setFeature(Feature.OUR_HEADING, selfHeading);
                wb.setFeature(Feature.OUR_VELOCITY, selfVelocity);
                wb.setFeature(Feature.OUR_ENERGY, selfEnergy);
                wb.setFeature(Feature.GUN_HEAT, gunHeat);
                wb.setFeature(Feature.GUN_HEADING, gunHeading);
                wb.setFeature(Feature.RADAR_HEADING, radarHeading);
                wb.setFeature(Feature.OPPONENT_X, oppX);
                wb.setFeature(Feature.OPPONENT_Y, oppY);
                wb.setFeature(Feature.OPPONENT_HEADING, oppHeading);
                wb.setFeature(Feature.OPPONENT_VELOCITY, oppVelocity);
                wb.setFeature(Feature.OPPONENT_ENERGY, oppEnergy);

                double dx = oppX - selfX;
                double dy = oppY - selfY;
                wb.setFeature(Feature.DISTANCE, Math.hypot(dx, dy));
                double absBearing = Math.atan2(dx, dy);
                wb.setFeature(Feature.BEARING_RADIANS, RoboMath.normalRelativeAngle(absBearing - selfHeading));
                wb.setFeature(Feature.OPPONENT_LATERAL_VELOCITY,
                                oppVelocity * Math.sin(oppHeading - absBearing - Math.PI));
                wb.setFeature(Feature.OPPONENT_ADVANCING_VELOCITY,
                                oppVelocity * Math.cos(oppHeading - absBearing - Math.PI));
                wb.setFeature(Feature.BATTLEFIELD_WIDTH, BF_WIDTH);
                wb.setFeature(Feature.BATTLEFIELD_HEIGHT, BF_HEIGHT);
                wb.setFeature(Feature.TICK, 42);
                wb.setFeature(Feature.LAST_SCAN_TICK, 42);
                wb.setFeature(Feature.TICKS_SINCE_SCAN, 0);

                IRobotSnapshot self = TestSnapshots.robot(selfX, selfY, selfHeading, selfVelocity,
                                selfEnergy, gunHeat, gunHeading, radarHeading, 0, RobotState.ACTIVE, "AutoBot");
                IRobotSnapshot opp = TestSnapshots.robot(oppX, oppY, oppHeading, oppVelocity,
                                oppEnergy, 0, 0, 0, 1, RobotState.ACTIVE, "Target");
                ITurnSnapshot turn = TestSnapshots.turn(42, self, opp);

                validator.validateSpatial(0, wb, self, opp, turn);

                assertTrue(validator.getSpatialChecks() > 0, "Should have performed checks");
                assertEquals(0, validator.getSpatialMismatches(), "All features match exactly");
        }

        @Test
        void spatialChecks_perFeatureMismatch() {
                Whiteboard wb = new Whiteboard();
                wb.setFeature(Feature.OUR_X, 100);
                wb.setFeature(Feature.OUR_Y, 200);
                wb.setFeature(Feature.OUR_HEADING, 1.0);
                wb.setFeature(Feature.OUR_VELOCITY, 6.0);
                wb.setFeature(Feature.OUR_ENERGY, 80);
                wb.setFeature(Feature.GUN_HEAT, 0.5);
                wb.setFeature(Feature.GUN_HEADING, 1.5);
                wb.setFeature(Feature.RADAR_HEADING, 2.0);
                // OPPONENT_X deliberately wrong
                wb.setFeature(Feature.OPPONENT_X, 999);
                wb.setFeature(Feature.OPPONENT_Y, 400);
                wb.setFeature(Feature.OPPONENT_HEADING, 0.3);
                wb.setFeature(Feature.OPPONENT_VELOCITY, -4.0);
                wb.setFeature(Feature.OPPONENT_ENERGY, 90);
                wb.setFeature(Feature.DISTANCE, 100);
                wb.setFeature(Feature.BEARING_RADIANS, 0);
                wb.setFeature(Feature.OPPONENT_LATERAL_VELOCITY, 0);
                wb.setFeature(Feature.OPPONENT_ADVANCING_VELOCITY, 0);
                wb.setFeature(Feature.BATTLEFIELD_WIDTH, BF_WIDTH);
                wb.setFeature(Feature.BATTLEFIELD_HEIGHT, BF_HEIGHT);
                wb.setFeature(Feature.TICK, 10);
                wb.setFeature(Feature.LAST_SCAN_TICK, 10);
                wb.setFeature(Feature.TICKS_SINCE_SCAN, 0);

                IRobotSnapshot self = TestSnapshots.robot(100, 200, 1.0, 6.0, 80, 0.5, 1.5, 2.0,
                                0, RobotState.ACTIVE, "A");
                IRobotSnapshot opp = TestSnapshots.robot(500, 400, 0.3, -4.0, 90, 0, 0, 0,
                                1, RobotState.ACTIVE, "B");
                ITurnSnapshot turn = TestSnapshots.turn(10, self, opp);

                validator.validateSpatial(0, wb, self, opp, turn);

                assertTrue(validator.getSpatialMismatches(Feature.OPPONENT_X) > 0,
                                "OPPONENT_X should be mismatched");
                assertEquals(0, validator.getSpatialMismatches(Feature.OUR_X),
                                "OUR_X should match exactly");
        }

        @Test
        void spatialChecks_selfValidatedButOpponentSkippedWhenNotScanTick() {
                Whiteboard wb = new Whiteboard();
                wb.setFeature(Feature.TICK, 42);
                wb.setFeature(Feature.LAST_SCAN_TICK, 40); // not a scan tick
                wb.setFeature(Feature.OUR_X, 100);
                wb.setFeature(Feature.OUR_Y, 200);
                wb.setFeature(Feature.OUR_HEADING, 0);
                wb.setFeature(Feature.OUR_VELOCITY, 0);
                wb.setFeature(Feature.OUR_ENERGY, 100);
                wb.setFeature(Feature.GUN_HEADING, 0);
                wb.setFeature(Feature.RADAR_HEADING, 0);
                wb.setFeature(Feature.GUN_HEAT, 0);
                wb.setFeature(Feature.BATTLEFIELD_WIDTH, 800);
                wb.setFeature(Feature.BATTLEFIELD_HEIGHT, 600);

                IRobotSnapshot self = TestSnapshots.robot(100, 200, 0, "A");
                IRobotSnapshot opp = TestSnapshots.robot(500, 400, 1, "B");
                ITurnSnapshot turn = TestSnapshots.turn(42, self, opp);

                validator.validateSpatial(0, wb, self, opp, turn);

                assertTrue(validator.getSpatialChecks(Feature.OUR_X) > 0,
                                "Self features should be checked on non-scan ticks");
                assertEquals(0, validator.getSpatialMismatches(Feature.OUR_X),
                                "Self features should match");
                assertEquals(0, validator.getSpatialChecks(Feature.OPPONENT_X),
                                "Opponent features should NOT be checked on non-scan ticks");
        }

        // ========== Layer 2: Fire Detection ==========

        @Test
        void fireDetection_perfectMatch() {
                validator.recordGodViewFire(0, 0, 3.0, 100.0, 200.0, 1.5, 10);
                validator.recordRobotSideFire(0, 0, 3.0, 100.0, 200.0, 10);

                assertEquals(1, validator.getGodViewFires(0));
                assertEquals(1, validator.getRobotSideFires(0));
                assertEquals(0.0, validator.getFirePositionMAE(0), 1e-9);
                assertEquals(0.0, validator.getFirePowerMAE(0), 1e-9);
                assertEquals(0.0, validator.getFireDetectionLatency(0), 1e-9);
        }

        @Test
        void fireDetection_positionAndPowerError() {
                validator.recordGodViewFire(1, 0, 2.0, 100.0, 200.0, 0, 5);
                validator.recordRobotSideFire(1, 0, 2.5, 103.0, 204.0, 6);

                // position error = sqrt(3^2 + 4^2) = 5.0
                assertEquals(5.0, validator.getFirePositionMAE(1), 1e-6);
                assertEquals(0.5, validator.getFirePowerMAE(1), 1e-6);
                assertEquals(1.0, validator.getFireDetectionLatency(1), 1e-6);
        }

        @Test
        void fireDetection_multipleFires_idPairing() {
                // God-view fires (bullet ids 1 and 2)
                validator.recordGodViewFire(0, 1, 1.0, 0.0, 0.0, 0, 10);
                validator.recordGodViewFire(0, 2, 2.0, 10.0, 0.0, 0, 20);

                // Robot-side fires arrive in REVERSE order but pair by bullet id
                validator.recordRobotSideFire(0, 2, 2.0, 10.0, 0.0, 20); // matches god-view id 2
                validator.recordRobotSideFire(0, 1, 1.0, 0.0, 0.0, 10); // matches god-view id 1

                assertEquals(2, validator.getGodViewFires(0));
                assertEquals(2, validator.getRobotSideFires(0));
                assertEquals(0.0, validator.getFirePositionMAE(0), 1e-9);
                assertEquals(0.0, validator.getFirePowerMAE(0), 1e-9);
        }

        @Test
        void fireDetection_NaN_whenNoFires() {
                assertTrue(Double.isNaN(validator.getFirePositionMAE(0)));
                assertTrue(Double.isNaN(validator.getFirePowerMAE(0)));
                assertTrue(Double.isNaN(validator.getFireDetectionLatency(0)));
        }

        // ========== Layer 2 (their): Incoming-Fire Detection ==========

        @Test
        void theirFireDetection_originTimingPowerExact_angleGapMeasured() {
                // god-view: true muzzle (300,400) at fire tick 8, power 2.0, true
                // flight heading 1.5 rad. Robot-side recovers origin/timing/power
                // exactly but only assumes a head-on bearing of 1.4 rad.
                validator.recordGodViewTheirFire(0, 2.0, 300.0, 400.0, 1.5, 8);
                validator.recordRobotSideTheirFire(0, 2.0, 300.0, 400.0, 1.4, 8);

                assertEquals(1, validator.getTheirGodViewFires(0));
                assertEquals(1, validator.getTheirRobotSideFires(0));
                assertEquals(0.0, validator.getTheirFirePositionMAE(0), 1e-9);
                assertEquals(0.0, validator.getTheirFirePowerMAE(0), 1e-9);
                assertEquals(0.0, validator.getTheirFireDetectionLatency(0), 1e-9);
                // The muzzle-angle gap (lead angle) is the sole irreducible unknown.
                assertEquals(0.1, validator.getTheirFireAngleMAE(0), 1e-9);
        }

        @Test
        void theirFireDetection_pairsByFireTickRegardlessOfArrivalOrder() {
                // Two incoming fires at ticks 10 and 20; robot-side arrives reversed.
                validator.recordGodViewTheirFire(0, 1.0, 0.0, 0.0, 0.0, 10);
                validator.recordGodViewTheirFire(0, 2.0, 50.0, 0.0, 0.0, 20);
                validator.recordRobotSideTheirFire(0, 2.0, 53.0, 4.0, 0.0, 20); // pairs tick 20
                validator.recordRobotSideTheirFire(0, 1.0, 0.0, 0.0, 0.0, 10); // pairs tick 10

                assertEquals(2, validator.getTheirGodViewFires(0));
                assertEquals(2, validator.getTheirRobotSideFires(0));
                // tick10 pos err 0; tick20 pos err sqrt(3^2+4^2)=5 → MAE = 2.5
                assertEquals(2.5, validator.getTheirFirePositionMAE(0), 1e-9);
        }

        @Test
        void theirFireDetection_NaN_whenNoFires() {
                assertTrue(Double.isNaN(validator.getTheirFirePositionMAE(0)));
                assertTrue(Double.isNaN(validator.getTheirFirePowerMAE(0)));
                assertTrue(Double.isNaN(validator.getTheirFireDetectionLatency(0)));
                assertTrue(Double.isNaN(validator.getTheirFireAngleMAE(0)));
        }

        // ========== Layer 3: Wave Precision ==========

        @Test
        void waveMatchRate_fullMatch() {
                validator.recordGodViewWaveResolution(0);
                validator.recordGodViewWaveResolution(0);
                validator.recordRobotSideWaveResolution(0);
                validator.recordRobotSideWaveResolution(0);

                assertEquals(1.0, validator.getWaveMatchRate(0), 1e-9);
        }

        @Test
        void waveMatchRate_partialMatch() {
                validator.recordGodViewWaveResolution(0);
                validator.recordGodViewWaveResolution(0);
                validator.recordGodViewWaveResolution(0);
                validator.recordRobotSideWaveResolution(0);
                validator.recordRobotSideWaveResolution(0);

                assertEquals(2.0 / 3.0, validator.getWaveMatchRate(0), 1e-9);
        }

        @Test
        void breakTickMAE_computed() {
                validator.compareWaveBreak(0, 0.5, 0.5, 100, 102);
                validator.compareWaveBreak(0, -0.3, -0.3, 200, 204);

                // Break tick errors: |100-102|=2, |200-204|=4, MAE = (2+4)/2 = 3
                assertEquals(3.0, validator.getBreakTickMAE(0), 1e-9);
        }

        @Test
        void gfError_computed() {
                validator.compareWaveBreak(0, 0.5, 0.4, 100, 100);
                validator.compareWaveBreak(0, -0.3, -0.5, 200, 200);

                // GF errors: |0.5-0.4|=0.1, |-0.3-(-0.5)|=0.2, MAE = (0.1+0.2)/2 = 0.15
                assertEquals(0.15, validator.getGfMeanAbsoluteError(0), 1e-9);
                assertEquals(0.2, validator.getGfMaxError(0), 1e-9);
        }

        @Test
        void waveMatchRate_NaN_whenNoResolutions() {
                assertTrue(Double.isNaN(validator.getWaveMatchRate(0)));
                assertTrue(Double.isNaN(validator.getBreakTickMAE(0)));
        }

        // ========== Layer 4: Energy Accounting ==========

        @Test
        void energyAccounting_noEvent_noDiscrepancy() {
                IRobotSnapshot self = TestSnapshots.robot(0, 0, 0, 0, 100, 0, 0, 0,
                                0, RobotState.ACTIVE, "A");
                IRobotSnapshot opp = TestSnapshots.robot(0, 0, 0, 0, 100, 0, 0, 0,
                                1, RobotState.ACTIVE, "B");
                ITurnSnapshot turn = TestSnapshots.turn(1, self, opp);

                // First tick — just initializes
                validator.accountEnergy(0, turn.getRobots(), turn.getBullets());

                // Second tick — same energy
                validator.accountEnergy(0, turn.getRobots(), turn.getBullets());

                assertEquals(1, validator.getEnergyChecks(0));
                assertEquals(0, validator.getEnergyDiscrepancies(0));
        }

        @Test
        void energyAccounting_fireReducesEnergy() {
                IRobotSnapshot robot100 = TestSnapshots.robot(0, 0, 0, 0, 100, 0, 0, 0,
                                0, RobotState.ACTIVE, "A");
                IRobotSnapshot robot97 = TestSnapshots.robot(0, 0, 0, 0, 97, 0, 0, 0,
                                0, RobotState.ACTIVE, "A");
                IRobotSnapshot opp = TestSnapshots.robot(0, 0, 0, 0, 100, 0, 0, 0,
                                1, RobotState.ACTIVE, "B");

                ITurnSnapshot t1 = TestSnapshots.turn(1, robot100, opp);
                validator.accountEnergy(0, t1.getRobots(), t1.getBullets());

                // Tick 2: fired power=3.0 so energy drops by 3.0 to 97
                ITurnSnapshot t2 = TestSnapshots.turn(2, robot97, opp,
                                TestSnapshots.bullet(1, 0, -1, 3.0, BulletState.FIRED));
                validator.accountEnergy(0, t2.getRobots(), t2.getBullets());

                assertEquals(1, validator.getEnergyChecks(0));
                assertEquals(0, validator.getEnergyDiscrepancies(0));
        }

        @Test
        void energyAccounting_hitBonusAddsEnergy() {
                IRobotSnapshot robot100 = TestSnapshots.robot(0, 0, 0, 0, 100, 0, 0, 0,
                                0, RobotState.ACTIVE, "A");
                // Hit bonus from power 2.0 = 3*2.0 = 6.0
                IRobotSnapshot robot106 = TestSnapshots.robot(0, 0, 0, 0, 106, 0, 0, 0,
                                0, RobotState.ACTIVE, "A");
                IRobotSnapshot opp = TestSnapshots.robot(0, 0, 0, 0, 100, 0, 0, 0,
                                1, RobotState.ACTIVE, "B");

                ITurnSnapshot t1 = TestSnapshots.turn(1, robot100, opp);
                validator.accountEnergy(0, t1.getRobots(), t1.getBullets());

                ITurnSnapshot t2 = TestSnapshots.turn(2, robot106, opp,
                                TestSnapshots.bullet(1, 0, 1, 2.0, BulletState.HIT_VICTIM));
                validator.accountEnergy(0, t2.getRobots(), t2.getBullets());

                assertEquals(0, validator.getEnergyDiscrepancies(0));
        }

        @Test
        void energyAccounting_combinedFireAndHitInSameTick() {
                // Start at 100, fire power=2.0 (-2), hit with power=1.0 (+3) → expected = 101
                IRobotSnapshot robot100 = TestSnapshots.robot(0, 0, 0, 0, 100, 0, 0, 0,
                                0, RobotState.ACTIVE, "A");
                IRobotSnapshot robot101 = TestSnapshots.robot(0, 0, 0, 0, 101, 0, 0, 0,
                                0, RobotState.ACTIVE, "A");
                IRobotSnapshot opp = TestSnapshots.robot(0, 0, 0, 0, 100, 0, 0, 0,
                                1, RobotState.ACTIVE, "B");

                ITurnSnapshot t1 = TestSnapshots.turn(1, robot100, opp);
                validator.accountEnergy(0, t1.getRobots(), t1.getBullets());

                ITurnSnapshot t2 = TestSnapshots.turn(2, robot101, opp,
                                TestSnapshots.bullet(1, 0, -1, 2.0, BulletState.FIRED),
                                TestSnapshots.bullet(2, 0, 1, 1.0, BulletState.HIT_VICTIM));
                validator.accountEnergy(0, t2.getRobots(), t2.getBullets());

                assertEquals(0, validator.getEnergyDiscrepancies(0));
        }

        @Test
        void energyAccounting_discrepancyDetected() {
                IRobotSnapshot robot100 = TestSnapshots.robot(0, 0, 0, 0, 100, 0, 0, 0,
                                0, RobotState.ACTIVE, "A");
                // Energy changes to 50 with no explanation
                IRobotSnapshot robot50 = TestSnapshots.robot(0, 0, 0, 0, 50, 0, 0, 0,
                                0, RobotState.ACTIVE, "A");
                IRobotSnapshot opp = TestSnapshots.robot(0, 0, 0, 0, 100, 0, 0, 0,
                                1, RobotState.ACTIVE, "B");

                ITurnSnapshot t1 = TestSnapshots.turn(1, robot100, opp);
                validator.accountEnergy(0, t1.getRobots(), t1.getBullets());

                ITurnSnapshot t2 = TestSnapshots.turn(2, robot50, opp);
                validator.accountEnergy(0, t2.getRobots(), t2.getBullets());

                assertEquals(1, validator.getEnergyDiscrepancies(0));
        }

        @Test
        void energyAccounting_wallHitDamage() {
                // wallDamage(velocity) = max(abs(velocity)/2 - 1, 0)
                // At velocity=6: wallDamage = max(3-1, 0) = 2.0
                IRobotSnapshot robot100v6 = TestSnapshots.robot(100, 100, 0, 6, 100, 0, 0, 0,
                                0, RobotState.ACTIVE, "A");
                // After wall hit: velocity=0 (post-impact), energy=98
                IRobotSnapshot robot98wall = TestSnapshots.robot(100, 100, 0, 0, 98, 0, 0, 0,
                                0, RobotState.HIT_WALL, "A");
                IRobotSnapshot opp = TestSnapshots.robot(500, 500, 0, 0, 100, 0, 0, 0,
                                1, RobotState.ACTIVE, "B");

                ITurnSnapshot t1 = TestSnapshots.turn(1, robot100v6, opp);
                validator.accountEnergy(0, t1.getRobots(), t1.getBullets());

                ITurnSnapshot t2 = TestSnapshots.turn(2, robot98wall, opp);
                validator.accountEnergy(0, t2.getRobots(), t2.getBullets());

                assertEquals(1, validator.getEnergyChecks(0));
                assertEquals(0, validator.getEnergyDiscrepancies(0));
        }

        @Test
        void energyAccounting_ramDamage() {
                // RAM_DAMAGE = 0.6
                IRobotSnapshot robot100 = TestSnapshots.robot(100, 100, 0, 0, 100, 0, 0, 0,
                                0, RobotState.ACTIVE, "A");
                IRobotSnapshot robot99_4 = TestSnapshots.robot(100, 100, 0, 0, 99.4, 0, 0, 0,
                                0, RobotState.HIT_ROBOT, "A");
                IRobotSnapshot opp = TestSnapshots.robot(100, 100, 0, 0, 100, 0, 0, 0,
                                1, RobotState.ACTIVE, "B");

                ITurnSnapshot t1 = TestSnapshots.turn(1, robot100, opp);
                validator.accountEnergy(0, t1.getRobots(), t1.getBullets());

                ITurnSnapshot t2 = TestSnapshots.turn(2, robot99_4, opp);
                validator.accountEnergy(0, t2.getRobots(), t2.getBullets());

                assertEquals(1, validator.getEnergyChecks(0));
                assertEquals(0, validator.getEnergyDiscrepancies(0));
        }

        @Test
        void energyAccounting_hitByOpponentBullet() {
                // bulletDamage(power=2.0) = 4*2 + 2*(2-1) = 10
                IRobotSnapshot robot100 = TestSnapshots.robot(100, 100, 0, 0, 100, 0, 0, 0,
                                0, RobotState.ACTIVE, "A");
                IRobotSnapshot robot90 = TestSnapshots.robot(100, 100, 0, 0, 90, 0, 0, 0,
                                0, RobotState.ACTIVE, "A");
                IRobotSnapshot opp = TestSnapshots.robot(500, 500, 0, 0, 100, 0, 0, 0,
                                1, RobotState.ACTIVE, "B");

                ITurnSnapshot t1 = TestSnapshots.turn(1, robot100, opp);
                validator.accountEnergy(0, t1.getRobots(), t1.getBullets());

                // Opponent (index=1) bullet hits us (victim=0)
                ITurnSnapshot t2 = TestSnapshots.turn(2, robot90, opp,
                                TestSnapshots.bullet(1, 1, 0, 2.0, BulletState.HIT_VICTIM));
                validator.accountEnergy(0, t2.getRobots(), t2.getBullets());

                assertEquals(1, validator.getEnergyChecks(0));
                assertEquals(0, validator.getEnergyDiscrepancies(0));
        }

        // ========== assertNonVacuous ==========

        @Test
        void assertNonVacuous_passes_whenAllLayersFed() {
                // Feed Layer 1
                Whiteboard wb = new Whiteboard();
                wb.setFeature(Feature.OUR_X, 100);
                wb.setFeature(Feature.OUR_Y, 200);
                wb.setFeature(Feature.OUR_HEADING, 0);
                wb.setFeature(Feature.OUR_VELOCITY, 0);
                wb.setFeature(Feature.OUR_ENERGY, 100);
                wb.setFeature(Feature.GUN_HEAT, 0);
                wb.setFeature(Feature.GUN_HEADING, 0);
                wb.setFeature(Feature.RADAR_HEADING, 0);
                wb.setFeature(Feature.OPPONENT_X, 500);
                wb.setFeature(Feature.OPPONENT_Y, 400);
                wb.setFeature(Feature.OPPONENT_HEADING, 0);
                wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
                wb.setFeature(Feature.OPPONENT_ENERGY, 100);
                wb.setFeature(Feature.DISTANCE, 500);
                wb.setFeature(Feature.BEARING_RADIANS, 0);
                wb.setFeature(Feature.OPPONENT_LATERAL_VELOCITY, 0);
                wb.setFeature(Feature.OPPONENT_ADVANCING_VELOCITY, 0);
                wb.setFeature(Feature.BATTLEFIELD_WIDTH, BF_WIDTH);
                wb.setFeature(Feature.BATTLEFIELD_HEIGHT, BF_HEIGHT);
                wb.setFeature(Feature.TICK, 1);
                wb.setFeature(Feature.LAST_SCAN_TICK, 1);
                wb.setFeature(Feature.TICKS_SINCE_SCAN, 0);

                IRobotSnapshot self = TestSnapshots.robot(100, 200, 0, 0, 100, 0, 0, 0,
                                0, RobotState.ACTIVE, "A");
                IRobotSnapshot opp = TestSnapshots.robot(500, 400, 0, 0, 100, 0, 0, 0,
                                1, RobotState.ACTIVE, "B");
                ITurnSnapshot turn = TestSnapshots.turn(1, self, opp);
                validator.validateSpatial(0, wb, self, opp, turn);

                // Feed Layer 2
                validator.recordGodViewFire(0, 0, 1.0, 100, 200, 0, 1);

                // Feed Layer 3
                validator.compareWaveBreak(0, 0.5, 0.5, 100, 100);

                // Feed Layer 4
                validator.accountEnergy(0, turn.getRobots(), turn.getBullets());
                validator.accountEnergy(0, turn.getRobots(), turn.getBullets());

                assertDoesNotThrow(() -> validator.assertNonVacuous());
        }

        @Test
        void assertNonVacuous_throwsForEmptyLayer1() {
                // Feed layers 2-4 only
                validator.recordGodViewFire(0, 0, 1.0, 0, 0, 0, 1);
                validator.compareWaveBreak(0, 0.5, 0.5, 10, 10);
                IRobotSnapshot r = TestSnapshots.robot(0, 0, 0, 0, 100, 0, 0, 0, 0, RobotState.ACTIVE, "A");
                IRobotSnapshot o = TestSnapshots.robot(0, 0, 0, 0, 100, 0, 0, 0, 1, RobotState.ACTIVE, "B");
                ITurnSnapshot t = TestSnapshots.turn(1, r, o);
                validator.accountEnergy(0, t.getRobots(), t.getBullets());
                validator.accountEnergy(0, t.getRobots(), t.getBullets());

                IllegalStateException ex = assertThrows(IllegalStateException.class,
                                () -> validator.assertNonVacuous());
                assertTrue(ex.getMessage().contains("Layer 1"));
        }

        @Test
        void assertNonVacuous_throwsForEmptyLayer2() {
                // Feed Layer 1
                Whiteboard wb = new Whiteboard();
                wb.setFeature(Feature.TICK, 1);
                wb.setFeature(Feature.LAST_SCAN_TICK, 1);
                wb.setFeature(Feature.OUR_X, 100);
                wb.setFeature(Feature.OUR_Y, 200);
                wb.setFeature(Feature.OUR_HEADING, 0);
                wb.setFeature(Feature.OUR_VELOCITY, 0);
                wb.setFeature(Feature.OUR_ENERGY, 100);
                wb.setFeature(Feature.GUN_HEAT, 0);
                wb.setFeature(Feature.GUN_HEADING, 0);
                wb.setFeature(Feature.RADAR_HEADING, 0);
                wb.setFeature(Feature.OPPONENT_X, 500);
                wb.setFeature(Feature.OPPONENT_Y, 400);
                wb.setFeature(Feature.OPPONENT_HEADING, 0);
                wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
                wb.setFeature(Feature.OPPONENT_ENERGY, 100);
                wb.setFeature(Feature.DISTANCE, 500);
                wb.setFeature(Feature.BEARING_RADIANS, 0);
                wb.setFeature(Feature.OPPONENT_LATERAL_VELOCITY, 0);
                wb.setFeature(Feature.OPPONENT_ADVANCING_VELOCITY, 0);
                wb.setFeature(Feature.BATTLEFIELD_WIDTH, BF_WIDTH);
                wb.setFeature(Feature.BATTLEFIELD_HEIGHT, BF_HEIGHT);
                wb.setFeature(Feature.TICKS_SINCE_SCAN, 0);

                IRobotSnapshot self = TestSnapshots.robot(100, 200, 0, 0, 100, 0, 0, 0, 0, RobotState.ACTIVE, "A");
                IRobotSnapshot opp = TestSnapshots.robot(500, 400, 0, 0, 100, 0, 0, 0, 1, RobotState.ACTIVE, "B");
                ITurnSnapshot turn = TestSnapshots.turn(1, self, opp);
                validator.validateSpatial(0, wb, self, opp, turn);

                // No Layer 2 fires
                validator.compareWaveBreak(0, 0.5, 0.5, 10, 10);
                validator.accountEnergy(0, turn.getRobots(), turn.getBullets());
                validator.accountEnergy(0, turn.getRobots(), turn.getBullets());

                IllegalStateException ex = assertThrows(IllegalStateException.class,
                                () -> validator.assertNonVacuous());
                assertTrue(ex.getMessage().contains("Layer 2"));
        }

        @Test
        void assertNonVacuous_passesWithEmptyLayer3() {
                Whiteboard wb = new Whiteboard();
                wb.setFeature(Feature.TICK, 1);
                wb.setFeature(Feature.LAST_SCAN_TICK, 1);
                wb.setFeature(Feature.OUR_X, 100);
                wb.setFeature(Feature.OUR_Y, 200);
                wb.setFeature(Feature.OUR_HEADING, 0);
                wb.setFeature(Feature.OUR_VELOCITY, 0);
                wb.setFeature(Feature.OUR_ENERGY, 100);
                wb.setFeature(Feature.GUN_HEAT, 0);
                wb.setFeature(Feature.GUN_HEADING, 0);
                wb.setFeature(Feature.RADAR_HEADING, 0);
                wb.setFeature(Feature.OPPONENT_X, 500);
                wb.setFeature(Feature.OPPONENT_Y, 400);
                wb.setFeature(Feature.OPPONENT_HEADING, 0);
                wb.setFeature(Feature.OPPONENT_VELOCITY, 0);
                wb.setFeature(Feature.OPPONENT_ENERGY, 100);
                wb.setFeature(Feature.DISTANCE, 500);
                wb.setFeature(Feature.BEARING_RADIANS, 0);
                wb.setFeature(Feature.OPPONENT_LATERAL_VELOCITY, 0);
                wb.setFeature(Feature.OPPONENT_ADVANCING_VELOCITY, 0);
                wb.setFeature(Feature.BATTLEFIELD_WIDTH, BF_WIDTH);
                wb.setFeature(Feature.BATTLEFIELD_HEIGHT, BF_HEIGHT);
                wb.setFeature(Feature.TICKS_SINCE_SCAN, 0);

                IRobotSnapshot self = TestSnapshots.robot(100, 200, 0, 0, 100, 0, 0, 0, 0, RobotState.ACTIVE, "A");
                IRobotSnapshot opp = TestSnapshots.robot(500, 400, 0, 0, 100, 0, 0, 0, 1, RobotState.ACTIVE, "B");
                ITurnSnapshot turn = TestSnapshots.turn(1, self, opp);
                validator.validateSpatial(0, wb, self, opp, turn);

                validator.recordGodViewFire(0, 0, 1.0, 0, 0, 0, 1);
                // No Layer 3 wave comparisons — not required for non-vacuous
                validator.accountEnergy(0, turn.getRobots(), turn.getBullets());
                validator.accountEnergy(0, turn.getRobots(), turn.getBullets());

                // Layer 3 is not required for assertNonVacuous (observer fires independently)
                assertDoesNotThrow(() -> validator.assertNonVacuous());
        }
}

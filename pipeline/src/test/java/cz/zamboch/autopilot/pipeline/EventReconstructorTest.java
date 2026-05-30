package cz.zamboch.autopilot.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import robocode.*;
import robocode.control.snapshot.*;

import java.util.List;

import static cz.zamboch.autopilot.pipeline.TestSnapshots.*;
import static org.junit.jupiter.api.Assertions.*;

class EventReconstructorTest {

        private static final double BF_WIDTH = 800;
        private static final double BF_HEIGHT = 600;
        private static final int MY_INDEX = 0;
        private static final int OPP_INDEX = 1;

        private EventReconstructor recon;

        @BeforeEach
        void setUp() {
                recon = new EventReconstructor();
        }

        @Test
        void noEvents_whenNothingHappens() {
                // Tick 0 - just initializes state (no prevRadar yet)
                ITurnSnapshot t0 = turn(0,
                                robot(400, 300, 0, 0, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(500, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                TickEvents ev0 = recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                // Tick 1 - still nothing (no radar movement)
                ITurnSnapshot t1 = turn(1,
                                robot(400, 300, 0, 0, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(500, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                TickEvents ev1 = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                assertTrue(ev1.isEmpty());
        }

        @Test
        void scannedRobotEvent_whenRadarSweepsOverOpponent() {
                // Tick 0: radar at 0
                ITurnSnapshot t0 = turn(0,
                                robot(400, 300, 0, 0, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(500, 300, 0, 0, 80, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                // Tick 1: radar sweeps to PI/2 (90°) — opponent is at bearing ~0 (directly east
                // in robocode coords)
                // Robocode heading: 0=north, PI/2=east
                // Opponent dx=100, dy=0 → angle = atan2(100,0) = PI/2
                // Bearing from body heading (0) = PI/2
                // Radar swept from 0 to PI/2, should scan the opponent
                ITurnSnapshot t1 = turn(1,
                                robot(400, 300, 0, 0, 100, 0, 0, Math.PI / 2, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(500, 300, 0, 2, 80, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                TickEvents delivered = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                assertFalse(delivered.isEmpty());
                Event first = delivered.events().stream()
                                .filter(e -> e instanceof ScannedRobotEvent).findFirst().orElse(null);
                assertNotNull(first, "Expected ScannedRobotEvent");
                ScannedRobotEvent sre = (ScannedRobotEvent) first;
                assertEquals("Enemy", sre.getName());
                assertEquals(80, sre.getEnergy(), 0.01);
                assertEquals(100, sre.getDistance(), 1.0);
                assertEquals(2, sre.getVelocity(), 0.01);
        }

        @Test
        void noScanEvent_whenRadarDoesNotSweepOverOpponent() {
                // Tick 0: radar at 0
                ITurnSnapshot t0 = turn(0,
                                robot(400, 300, 0, 0, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(400, 500, 0, 0, 80, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                // Tick 1: radar sweeps slightly to east (PI/4), opponent is north (dy=200)
                // Opponent angle = atan2(0, 200) = 0 (north), radar sweeps from 0 to PI/4
                // The opponent is at angle 0 which is the start of the arc; might trigger.
                // Let's put opponent due south instead — angle = PI (atan2(0,-200)=PI)
                ITurnSnapshot t1 = turn(1,
                                robot(400, 300, 0, 0, 100, 0, 0, Math.PI / 4, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(400, 100, 0, 0, 80, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                TickEvents delivered = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                boolean hasScanned = delivered.events().stream().anyMatch(e -> e instanceof ScannedRobotEvent);
                assertFalse(hasScanned);
        }

        @Test
        void bulletHitEvent_whenOurBulletHitsOpponent() {
                // Tick 0: normal state
                ITurnSnapshot t0 = turn(0,
                                robot(400, 300, 0, 0, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(500, 300, 0, 0, 80, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                // Tick 1: our bullet (id=1) hit the opponent
                ITurnSnapshot t1 = turn(1,
                                robot(400, 300, 0, 0, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(500, 300, 0, 0, 72, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"),
                                bullet(1, MY_INDEX, OPP_INDEX, 2.0, BulletState.HIT_VICTIM));
                TickEvents delivered = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                BulletHitEvent hit = delivered.events().stream()
                                .filter(e -> e instanceof BulletHitEvent)
                                .map(e -> (BulletHitEvent) e)
                                .findFirst().orElse(null);
                assertNotNull(hit);
                assertEquals("Enemy", hit.getName());
                assertEquals(2.0, hit.getBullet().getPower(), 0.01);
        }

        @Test
        void hitByBulletEvent_whenOpponentBulletHitsUs() {
                // Tick 0
                ITurnSnapshot t0 = turn(0,
                                robot(400, 300, 0, 0, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(500, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                // Tick 1: opponent's bullet (id=5) hits us
                ITurnSnapshot t1 = turn(1,
                                robot(400, 300, 0, 0, 92, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(500, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"),
                                bullet(5, OPP_INDEX, MY_INDEX, 2.0, BulletState.HIT_VICTIM));
                TickEvents delivered = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                HitByBulletEvent hbbe = delivered.events().stream()
                                .filter(e -> e instanceof HitByBulletEvent)
                                .map(e -> (HitByBulletEvent) e)
                                .findFirst().orElse(null);
                assertNotNull(hbbe);
                assertEquals(2.0, hbbe.getPower(), 0.01);
        }

        @Test
        void bulletMissedEvent_whenOurBulletHitsWall() {
                ITurnSnapshot t0 = turn(0,
                                robot(400, 300, 0, 0, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(500, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                // Our bullet (id=7) hit a wall
                ITurnSnapshot t1 = turn(1,
                                robot(400, 300, 0, 0, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(500, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"),
                                bullet(7, MY_INDEX, -1, 1.5, BulletState.HIT_WALL));
                TickEvents delivered = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                BulletMissedEvent bme = delivered.events().stream()
                                .filter(e -> e instanceof BulletMissedEvent)
                                .map(e -> (BulletMissedEvent) e)
                                .findFirst().orElse(null);
                assertNotNull(bme);
                assertEquals(1.5, bme.getBullet().getPower(), 0.01);
        }

        @Test
        void hitWallEvent_onStateTransition() {
                // Tick 0: active, moving toward wall
                ITurnSnapshot t0 = turn(0,
                                robot(18, 300, 0, -4, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(500, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                // Tick 1: hit the wall
                ITurnSnapshot t1 = turn(1,
                                robot(18, 300, 0, 0, 99, 0, 0, 0, MY_INDEX, RobotState.HIT_WALL, "MyBot"),
                                robot(500, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                TickEvents delivered = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                HitWallEvent hwe = delivered.events().stream()
                                .filter(e -> e instanceof HitWallEvent)
                                .map(e -> (HitWallEvent) e)
                                .findFirst().orElse(null);
                assertNotNull(hwe);
        }

        @Test
        void noWallHitEvent_whenAlreadyInHitWallState() {
                // Tick 0: already in HIT_WALL state
                ITurnSnapshot t0 = turn(0,
                                robot(18, 300, 0, 0, 99, 0, 0, 0, MY_INDEX, RobotState.HIT_WALL, "MyBot"),
                                robot(500, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                // Tick 1: still HIT_WALL (no transition)
                ITurnSnapshot t1 = turn(1,
                                robot(18, 300, 0, 0, 99, 0, 0, 0, MY_INDEX, RobotState.HIT_WALL, "MyBot"),
                                robot(500, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                TickEvents delivered = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                boolean hasWallHit = delivered.events().stream().anyMatch(e -> e instanceof HitWallEvent);
                assertFalse(hasWallHit);
        }

        @Test
        void hitRobotEvent_onRamTransition() {
                // Tick 0: active, approaching opponent
                ITurnSnapshot t0 = turn(0,
                                robot(400, 300, Math.PI / 2, 4, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(436, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                // Tick 1: ram!
                ITurnSnapshot t1 = turn(1,
                                robot(418, 300, Math.PI / 2, 0, 99.4, 0, 0, 0, MY_INDEX, RobotState.HIT_ROBOT, "MyBot"),
                                robot(436, 300, 0, 0, 99.4, 0, 0, 0, OPP_INDEX, RobotState.HIT_ROBOT, "Enemy"));
                TickEvents delivered = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                HitRobotEvent hre = delivered.events().stream()
                                .filter(e -> e instanceof HitRobotEvent)
                                .map(e -> (HitRobotEvent) e)
                                .findFirst().orElse(null);
                assertNotNull(hre);
                assertEquals("Enemy", hre.getName());
                assertTrue(hre.isMyFault()); // we were moving forward
        }

        @Test
        void deathEvent_whenWeDie() {
                ITurnSnapshot t0 = turn(0,
                                robot(400, 300, 0, 0, 5, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(500, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                // Tick 1: we die
                ITurnSnapshot t1 = turn(1,
                                robot(400, 300, 0, 0, 0, 0, 0, 0, MY_INDEX, RobotState.DEAD, "MyBot"),
                                robot(500, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                TickEvents delivered = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                assertTrue(delivered.events().stream().anyMatch(e -> e instanceof DeathEvent));
        }

        @Test
        void winEvent_whenOpponentDies() {
                ITurnSnapshot t0 = turn(0,
                                robot(400, 300, 0, 0, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(500, 300, 0, 0, 3, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                // Tick 1: opponent dies
                ITurnSnapshot t1 = turn(1,
                                robot(400, 300, 0, 0, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(500, 300, 0, 0, 0, 0, 0, 0, OPP_INDEX, RobotState.DEAD, "Enemy"));
                TickEvents delivered = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                assertTrue(delivered.events().stream().anyMatch(e -> e instanceof WinEvent));
                assertTrue(delivered.events().stream().anyMatch(e -> e instanceof RobotDeathEvent));
        }

        @Test
        void bulletDeduplication_sameBulletNotProcessedTwice() {
                ITurnSnapshot t0 = turn(0,
                                robot(400, 300, 0, 0, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(500, 300, 0, 0, 80, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                // Tick 1: bullet 1 hits
                ITurnSnapshot t1 = turn(1,
                                robot(400, 300, 0, 0, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(500, 300, 0, 0, 72, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"),
                                bullet(1, MY_INDEX, OPP_INDEX, 2.0, BulletState.HIT_VICTIM));
                TickEvents delivered1 = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                assertEquals(1, delivered1.events().stream().filter(e -> e instanceof BulletHitEvent).count());

                // Tick 2: same bullet id=1 still in snapshot (shouldn't fire again)
                ITurnSnapshot t2 = turn(2,
                                robot(400, 300, 0, 0, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(500, 300, 0, 0, 72, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"),
                                bullet(1, MY_INDEX, OPP_INDEX, 2.0, BulletState.HIT_VICTIM));
                TickEvents delivered2 = recon.reconstruct(t2, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                assertEquals(0, delivered2.events().stream().filter(e -> e instanceof BulletHitEvent).count());
        }

        // ==================== BulletHitBulletEvent Tests ====================

        @Test
        void bulletHitBulletEvent_whenOurBulletCollidesWithOpponentBullet() {
                ITurnSnapshot t0 = turn(0,
                                robot(400, 300, 0, 0, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(500, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                // Tick 1: our bullet (id=10) and opponent's bullet (id=20) collide
                ITurnSnapshot t1 = turn(1,
                                robot(400, 300, 0, 0, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(500, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"),
                                bullet(10, MY_INDEX, -1, 2.0, BulletState.HIT_BULLET),
                                bullet(20, OPP_INDEX, -1, 1.5, BulletState.HIT_BULLET));
                TickEvents delivered = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                BulletHitBulletEvent bhbe = delivered.events().stream()
                                .filter(e -> e instanceof BulletHitBulletEvent)
                                .map(e -> (BulletHitBulletEvent) e)
                                .findFirst().orElse(null);
                assertNotNull(bhbe, "Expected BulletHitBulletEvent");
                assertEquals(2.0, bhbe.getBullet().getPower(), 0.01); // our bullet
                assertEquals(1.5, bhbe.getHitBullet().getPower(), 0.01); // their bullet
        }

        @Test
        void noBulletHitBulletEvent_whenOpponentBulletOnly() {
                // Only opponent's bullet has HIT_BULLET — we shouldn't get an event
                ITurnSnapshot t0 = turn(0,
                                robot(400, 300, 0, 0, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(500, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                // Only opponent's bullet has HIT_BULLET state (shouldn't happen without ours
                // too,
                // but tests the owner filter)
                ITurnSnapshot t1 = turn(1,
                                robot(400, 300, 0, 0, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(500, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"),
                                bullet(20, OPP_INDEX, -1, 1.5, BulletState.HIT_BULLET));
                TickEvents delivered = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                boolean hasBHBE = delivered.events().stream().anyMatch(e -> e instanceof BulletHitBulletEvent);
                assertFalse(hasBHBE);
        }

        // ==================== Wall Bearing Tests ====================

        @Test
        void hitWallEvent_leftWall_correctBearing() {
                // Robot heading north (0), hits left wall → bearing should be
                // normalRelAngle(3π/2 - 0) = -π/2
                ITurnSnapshot t0 = turn(0,
                                robot(18, 300, 0, -4, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(500, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                // At left wall: x=18 (closest to left: 18-18=0)
                ITurnSnapshot t1 = turn(1,
                                robot(18, 300, 0, 0, 99, 0, 0, 0, MY_INDEX, RobotState.HIT_WALL, "MyBot"),
                                robot(500, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                TickEvents delivered = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                HitWallEvent hwe = delivered.events().stream()
                                .filter(e -> e instanceof HitWallEvent)
                                .map(e -> (HitWallEvent) e)
                                .findFirst().orElse(null);
                assertNotNull(hwe);
                // Left wall: normalRelAngle(3π/2 - 0) = -π/2
                assertEquals(-Math.PI / 2, hwe.getBearingRadians(), 0.01);
        }

        @Test
        void hitWallEvent_rightWall_correctBearing() {
                // Robot heading north (0), hits right wall → bearing = normalRelAngle(π/2 - 0)
                // = π/2
                ITurnSnapshot t0 = turn(0,
                                robot(782, 300, 0, 4, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(400, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                // At right wall: x=782 (closest to right: 800-18-782=0)
                ITurnSnapshot t1 = turn(1,
                                robot(782, 300, 0, 0, 99, 0, 0, 0, MY_INDEX, RobotState.HIT_WALL, "MyBot"),
                                robot(400, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                TickEvents delivered = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                HitWallEvent hwe = delivered.events().stream()
                                .filter(e -> e instanceof HitWallEvent)
                                .map(e -> (HitWallEvent) e)
                                .findFirst().orElse(null);
                assertNotNull(hwe);
                assertEquals(Math.PI / 2, hwe.getBearingRadians(), 0.01);
        }

        @Test
        void hitWallEvent_bottomWall_correctBearing() {
                // Robot heading north (0), hits bottom wall → bearing = normalRelAngle(π - 0) =
                // π (≈ -π)
                ITurnSnapshot t0 = turn(0,
                                robot(400, 18, 0, 0, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(400, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                // At bottom wall: y=18 (closest to bottom: 18-18=0)
                ITurnSnapshot t1 = turn(1,
                                robot(400, 18, 0, 0, 99, 0, 0, 0, MY_INDEX, RobotState.HIT_WALL, "MyBot"),
                                robot(400, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                TickEvents delivered = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                HitWallEvent hwe = delivered.events().stream()
                                .filter(e -> e instanceof HitWallEvent)
                                .map(e -> (HitWallEvent) e)
                                .findFirst().orElse(null);
                assertNotNull(hwe);
                // normalRelAngle(π - 0) = π or -π (both valid, they're equivalent)
                assertEquals(Math.PI, Math.abs(hwe.getBearingRadians()), 0.01);
        }

        @Test
        void hitWallEvent_topWall_correctBearing() {
                // Robot heading north (0), hits top wall → bearing = normalRelAngle(0 - 0) = 0
                ITurnSnapshot t0 = turn(0,
                                robot(400, 582, 0, 4, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(400, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                // At top wall: y=582 (closest to top: 600-18-582=0)
                ITurnSnapshot t1 = turn(1,
                                robot(400, 582, 0, 0, 99, 0, 0, 0, MY_INDEX, RobotState.HIT_WALL, "MyBot"),
                                robot(400, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                TickEvents delivered = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                HitWallEvent hwe = delivered.events().stream()
                                .filter(e -> e instanceof HitWallEvent)
                                .map(e -> (HitWallEvent) e)
                                .findFirst().orElse(null);
                assertNotNull(hwe);
                assertEquals(0, hwe.getBearingRadians(), 0.01);
        }

        @Test
        void hitWallEvent_withNonZeroHeading_correctBearing() {
                // Robot heading east (π/2), hits right wall → bearing = normalRelAngle(π/2 -
                // π/2) = 0
                ITurnSnapshot t0 = turn(0,
                                robot(782, 300, Math.PI / 2, 4, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(400, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                ITurnSnapshot t1 = turn(1,
                                robot(782, 300, Math.PI / 2, 0, 99, 0, 0, 0, MY_INDEX, RobotState.HIT_WALL, "MyBot"),
                                robot(400, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                TickEvents delivered = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                HitWallEvent hwe = delivered.events().stream()
                                .filter(e -> e instanceof HitWallEvent)
                                .map(e -> (HitWallEvent) e)
                                .findFirst().orElse(null);
                assertNotNull(hwe);
                // Right wall, heading east: normalRelAngle(π/2 - π/2) = 0
                assertEquals(0, hwe.getBearingRadians(), 0.01);
        }

        // ==================== At-Fault Logic Tests ====================

        @Test
        void hitRobotEvent_movingForwardToward_isAtFault() {
                // Heading east (π/2), moving forward (velocity>0), opponent is east → at fault
                ITurnSnapshot t0 = turn(0,
                                robot(400, 300, Math.PI / 2, 4, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(436, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                ITurnSnapshot t1 = turn(1,
                                robot(418, 300, Math.PI / 2, 0, 99.4, 0, 0, 0, MY_INDEX, RobotState.HIT_ROBOT, "MyBot"),
                                robot(436, 300, 0, 0, 99.4, 0, 0, 0, OPP_INDEX, RobotState.HIT_ROBOT, "Enemy"));
                TickEvents delivered = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                HitRobotEvent hre = delivered.events().stream()
                                .filter(e -> e instanceof HitRobotEvent)
                                .map(e -> (HitRobotEvent) e)
                                .findFirst().orElse(null);
                assertNotNull(hre);
                assertTrue(hre.isMyFault());
        }

        @Test
        void hitRobotEvent_movingBackwardToward_isAtFault() {
                // Heading east (π/2), moving backward (velocity<0), opponent is WEST (behind
                // us) → at fault
                // Opponent is at bearing ≈ -π (behind), velocity < 0 and |bearing| > π/2 → at
                // fault
                ITurnSnapshot t0 = turn(0,
                                robot(400, 300, Math.PI / 2, -4, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(364, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                ITurnSnapshot t1 = turn(1,
                                robot(382, 300, Math.PI / 2, 0, 99.4, 0, 0, 0, MY_INDEX, RobotState.HIT_ROBOT, "MyBot"),
                                robot(364, 300, 0, 0, 99.4, 0, 0, 0, OPP_INDEX, RobotState.HIT_ROBOT, "Enemy"));
                TickEvents delivered = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                HitRobotEvent hre = delivered.events().stream()
                                .filter(e -> e instanceof HitRobotEvent)
                                .map(e -> (HitRobotEvent) e)
                                .findFirst().orElse(null);
                assertNotNull(hre);
                assertTrue(hre.isMyFault());
        }

        @Test
        void hitRobotEvent_movingForwardAway_notAtFault() {
                // Heading east (π/2), moving forward (velocity>0), opponent is WEST (bearing ≈
                // -π)
                // velocity > 0 but bearing is NOT in (-π/2, π/2) → not at fault
                ITurnSnapshot t0 = turn(0,
                                robot(400, 300, Math.PI / 2, 4, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(364, 300, 0, -4, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                // Opponent rammed us from behind — opponent has HIT_ROBOT, not us
                ITurnSnapshot t1 = turn(1,
                                robot(400, 300, Math.PI / 2, 4, 99.4, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(382, 300, 0, 0, 99.4, 0, 0, 0, OPP_INDEX, RobotState.HIT_ROBOT, "Enemy"));
                TickEvents delivered = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                HitRobotEvent hre = delivered.events().stream()
                                .filter(e -> e instanceof HitRobotEvent)
                                .map(e -> (HitRobotEvent) e)
                                .findFirst().orElse(null);
                assertNotNull(hre, "Should detect ram from opponent's HIT_ROBOT state");
                assertFalse(hre.isMyFault());
        }

        @Test
        void hitRobotEvent_zeroVelocity_notAtFault() {
                // Stationary robot (velocity=0) → never at fault
                ITurnSnapshot t0 = turn(0,
                                robot(400, 300, 0, 0, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(418, 300, Math.PI, -4, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                // Opponent rams us (they have HIT_ROBOT), we're stationary
                ITurnSnapshot t1 = turn(1,
                                robot(400, 300, 0, 0, 99.4, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(418, 300, Math.PI, 0, 99.4, 0, 0, 0, OPP_INDEX, RobotState.HIT_ROBOT, "Enemy"));
                TickEvents delivered = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                HitRobotEvent hre = delivered.events().stream()
                                .filter(e -> e instanceof HitRobotEvent)
                                .map(e -> (HitRobotEvent) e)
                                .findFirst().orElse(null);
                assertNotNull(hre, "Should detect ram from opponent's HIT_ROBOT state");
                assertFalse(hre.isMyFault());
        }

        // ==================== Non-at-fault Ram Detection Tests ====================

        @Test
        void hitRobotEvent_detectedFromOpponentState() {
                // We are stationary, opponent rams us → opponent gets HIT_ROBOT, we don't
                // We should still get a HitRobotEvent with atFault=false
                ITurnSnapshot t0 = turn(0,
                                robot(400, 300, 0, 0, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(400, 336, Math.PI, -8, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                // Opponent transitions to HIT_ROBOT (they rammed us), we stay ACTIVE
                ITurnSnapshot t1 = turn(1,
                                robot(400, 300, 0, 0, 99.4, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(400, 320, Math.PI, 0, 99.4, 0, 0, 0, OPP_INDEX, RobotState.HIT_ROBOT, "Enemy"));
                TickEvents delivered = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                HitRobotEvent hre = delivered.events().stream()
                                .filter(e -> e instanceof HitRobotEvent)
                                .map(e -> (HitRobotEvent) e)
                                .findFirst().orElse(null);
                assertNotNull(hre, "Must detect collision from opponent's HIT_ROBOT transition");
                assertEquals("Enemy", hre.getName());
                assertFalse(hre.isMyFault());
        }

        @Test
        void hitRobotEvent_headOnCollision_deliversBothFaultEvents() {
                // Engine ground truth (RobotPeer.checkRobotCollision): the collision check
                // runs once per robot in the update loop. In a head-on collision BOTH robots
                // are at fault, so each robot's check delivers a HitRobotEvent to itself
                // (atFault=true) and to the other robot (atFault=false). Our robot therefore
                // receives TWO HitRobotEvents on the same tick: one true, one false.
                // Both carry bearing = direction-to-opponent relative to our heading and the
                // opponent's post-collision energy (engine uses otherRobot.energy for our
                // own-fault event and the at-fault robot's energy for the victim event — in a
                // symmetric head-on these are the same opponent energy).
                ITurnSnapshot t0 = turn(0,
                                robot(400, 300, Math.PI / 2, 4, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(436, 300, 3 * Math.PI / 2, -4, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE,
                                                "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                // Me at (418,300) heading east (π/2); opponent at (436,300). Direction to
                // opponent: atan2(18, 0) = π/2; bearing = normalRelativeAngle(π/2 - π/2) = 0.
                ITurnSnapshot t1 = turn(1,
                                robot(418, 300, Math.PI / 2, 0, 99.4, 0, 0, 0, MY_INDEX, RobotState.HIT_ROBOT, "MyBot"),
                                robot(436, 300, 3 * Math.PI / 2, 0, 99.4, 0, 0, 0, OPP_INDEX, RobotState.HIT_ROBOT,
                                                "Enemy"));
                TickEvents delivered = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                List<HitRobotEvent> rams = delivered.events().stream()
                                .filter(e -> e instanceof HitRobotEvent)
                                .map(e -> (HitRobotEvent) e)
                                .toList();
                assertEquals(2, rams.size(),
                                "Head-on collision delivers two HitRobotEvents (one per robot's collision check)");
                assertEquals(1, rams.stream().filter(HitRobotEvent::isMyFault).count(),
                                "Exactly one event marks us at fault");
                assertEquals(1, rams.stream().filter(e -> !e.isMyFault()).count(),
                                "Exactly one event marks the opponent at fault");
                for (HitRobotEvent hre : rams) {
                        assertEquals("Enemy", hre.getName());
                        assertEquals(0.0, hre.getBearingRadians(), 0.01,
                                        "Bearing is direction to opponent relative to our heading");
                        assertEquals(99.4, hre.getEnergy(), 0.01, "Carries opponent's post-collision energy");
                }
        }

        // ==================== Event Ordering Test ====================

        @Test
        void eventOrdering_sortedByPriorityDescending() {
                // Multi-event tick: bullet hit + ram + scan + death all on same tick
                // Engine dispatch order is highest priority first:
                // WinEvent(100) > RobotDeathEvent(70) > BulletHitEvent(50) > HitRobotEvent(40)
                // > ScannedRobotEvent(10)
                ITurnSnapshot t0 = turn(0,
                                robot(400, 300, Math.PI / 2, 4, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(436, 300, 0, 0, 5, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                // Tick 1: our bullet kills opponent, we ram them, they die
                ITurnSnapshot t1 = turn(1,
                                robot(418, 300, Math.PI / 2, 0, 106, 0, 0, Math.PI / 2, MY_INDEX, RobotState.HIT_ROBOT,
                                                "MyBot"),
                                robot(436, 300, 0, 0, 0, 0, 0, 0, OPP_INDEX, RobotState.DEAD, "Enemy"),
                                bullet(1, MY_INDEX, OPP_INDEX, 2.0, BulletState.HIT_VICTIM));
                TickEvents delivered = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                List<Event> evList = delivered.events();
                int winIdx = -1, robotDeathIdx = -1, bulletHitIdx = -1, hitRobotIdx = -1;
                for (int i = 0; i < evList.size(); i++) {
                        Event e = evList.get(i);
                        if (e instanceof WinEvent)
                                winIdx = i;
                        else if (e instanceof RobotDeathEvent)
                                robotDeathIdx = i;
                        else if (e instanceof BulletHitEvent)
                                bulletHitIdx = i;
                        else if (e instanceof HitRobotEvent)
                                hitRobotIdx = i;
                }

                assertTrue(winIdx >= 0, "Should have WinEvent");
                assertTrue(robotDeathIdx >= 0, "Should have RobotDeathEvent");
                assertTrue(bulletHitIdx >= 0, "Should have BulletHitEvent");
                assertTrue(hitRobotIdx >= 0, "Should have HitRobotEvent");

                // Engine dispatch order: highest priority first
                assertTrue(winIdx < robotDeathIdx, "WinEvent(100) before RobotDeathEvent(70)");
                assertTrue(robotDeathIdx < bulletHitIdx, "RobotDeathEvent(70) before BulletHitEvent(50)");
                assertTrue(bulletHitIdx < hitRobotIdx, "BulletHitEvent(50) before HitRobotEvent(40)");
        }

        // ==================== Event Time Tests ====================

        @Test
        void eventTime_setToTurnNumber() {
                ITurnSnapshot t0 = turn(0,
                                robot(400, 300, 0, 0, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(500, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                ITurnSnapshot t1 = turn(42,
                                robot(400, 300, 0, 0, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(500, 300, 0, 0, 72, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"),
                                bullet(1, MY_INDEX, OPP_INDEX, 2.0, BulletState.HIT_VICTIM));
                TickEvents delivered = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                for (Event e : delivered.events()) {
                        assertEquals(42, e.getTime(), "Event time should match turn number");
                }
        }

        // ==================== Dead Scan Guard Tests ====================

        @Test
        void noScanEvent_whenWeAreDead() {
                // Tick 0: alive, radar at 0
                ITurnSnapshot t0 = turn(0,
                                robot(400, 300, 0, 0, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                                robot(500, 300, 0, 0, 80, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                // Tick 1: we are dead, radar sweeps over opponent (should NOT scan)
                ITurnSnapshot t1 = turn(1,
                                robot(400, 300, 0, 0, 0, 0, 0, Math.PI / 2, MY_INDEX, RobotState.DEAD, "MyBot"),
                                robot(500, 300, 0, 0, 80, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                TickEvents delivered = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                boolean hasScanned = delivered.events().stream().anyMatch(e -> e instanceof ScannedRobotEvent);
                assertFalse(hasScanned, "Dead robot should not generate scan events");
        }

        // ==================== Corner Wall Hit Tests ====================

        @Test
        void hitWallEvent_cornerHit_yWallTakesPrecedence() {
                // Robot at bottom-left corner (both X and Y at boundary) — Y wall should win
                // Engine processes X first then Y overwrites, so bottom wall bearing is used
                ITurnSnapshot t0 = turn(0,
                                robot(18, 18, Math.PI + Math.PI / 4, -4, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE,
                                                "MyBot"),
                                robot(500, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                recon.reconstruct(t0, MY_INDEX, BF_WIDTH, BF_HEIGHT);

                // At corner: x=18 (dist to left=0), y=18 (dist to bottom=0) — tied
                ITurnSnapshot t1 = turn(1,
                                robot(18, 18, Math.PI + Math.PI / 4, 0, 99, 0, 0, 0, MY_INDEX, RobotState.HIT_WALL,
                                                "MyBot"),
                                robot(500, 300, 0, 0, 100, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
                TickEvents delivered = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
                HitWallEvent hwe = delivered.events().stream()
                                .filter(e -> e instanceof HitWallEvent)
                                .map(e -> (HitWallEvent) e)
                                .findFirst().orElse(null);
                assertNotNull(hwe);
                // Bottom wall: normalRelAngle(π - heading) where heading = π + π/4 = 5π/4
                // = normalRelAngle(π - 5π/4) = normalRelAngle(-π/4) = -π/4
                double expectedBearing = cz.zamboch.autopilot.core.RoboMath.normalRelativeAngle(
                                Math.PI - (Math.PI + Math.PI / 4));
                assertEquals(expectedBearing, hwe.getBearingRadians(), 0.01,
                                "Corner hit should use bottom wall (Y takes precedence over X)");
        }
}

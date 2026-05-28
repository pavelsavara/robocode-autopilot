package cz.zamboch.autopilot.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import robocode.*;
import robocode.control.snapshot.*;

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

        // Tick 1: radar sweeps to PI/2 (90°) — opponent is at bearing ~0 (directly east in robocode coords)
        // Robocode heading: 0=north, PI/2=east
        // Opponent dx=100, dy=0 → angle = atan2(100,0) = PI/2
        // Bearing from body heading (0) = PI/2
        // Radar swept from 0 to PI/2, should scan the opponent
        ITurnSnapshot t1 = turn(1,
                robot(400, 300, 0, 0, 100, 0, 0, Math.PI / 2, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                robot(500, 300, 0, 2, 80, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"));
        TickEvents ev1 = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);

        assertFalse(ev1.isEmpty());
        Event first = ev1.events().stream()
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
        TickEvents ev1 = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);

        boolean hasScanned = ev1.events().stream().anyMatch(e -> e instanceof ScannedRobotEvent);
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
        TickEvents ev1 = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);

        BulletHitEvent hit = ev1.events().stream()
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
        TickEvents ev1 = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);

        HitByBulletEvent hbbe = ev1.events().stream()
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
        TickEvents ev1 = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);

        BulletMissedEvent bme = ev1.events().stream()
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
        TickEvents ev1 = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);

        HitWallEvent hwe = ev1.events().stream()
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
        TickEvents ev1 = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);

        boolean hasWallHit = ev1.events().stream().anyMatch(e -> e instanceof HitWallEvent);
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
        TickEvents ev1 = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);

        HitRobotEvent hre = ev1.events().stream()
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
        TickEvents ev1 = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);

        assertTrue(ev1.events().stream().anyMatch(e -> e instanceof DeathEvent));
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
        TickEvents ev1 = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);

        assertTrue(ev1.events().stream().anyMatch(e -> e instanceof WinEvent));
        assertTrue(ev1.events().stream().anyMatch(e -> e instanceof RobotDeathEvent));
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
        TickEvents ev1 = recon.reconstruct(t1, MY_INDEX, BF_WIDTH, BF_HEIGHT);
        assertEquals(1, ev1.events().stream().filter(e -> e instanceof BulletHitEvent).count());

        // Tick 2: same bullet id=1 still in snapshot (shouldn't fire again)
        ITurnSnapshot t2 = turn(2,
                robot(400, 300, 0, 0, 100, 0, 0, 0, MY_INDEX, RobotState.ACTIVE, "MyBot"),
                robot(500, 300, 0, 0, 72, 0, 0, 0, OPP_INDEX, RobotState.ACTIVE, "Enemy"),
                bullet(1, MY_INDEX, OPP_INDEX, 2.0, BulletState.HIT_VICTIM));
        TickEvents ev2 = recon.reconstruct(t2, MY_INDEX, BF_WIDTH, BF_HEIGHT);
        assertEquals(0, ev2.events().stream().filter(e -> e instanceof BulletHitEvent).count());
    }
}

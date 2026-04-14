package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Transformer;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.pipeline.features.EnergyOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.MovementOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.SpatialOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.TimingOfflineFeatures;
import org.junit.jupiter.api.Test;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * God-view validation of bullet events and energy-drop fire detection.
 * Verifies that inferred opponent fires match actual bullet data from snapshots.
 */
class BulletValidationTest {

    private static final Path RECORDINGS_DIR = Paths.get("..", "recordings", "24360294214");

    @Test
    void bulletHitsSetWeHitOpponentFlag() throws Exception {
        File brFile = findFirstRecording();
        Loader loader = new Loader(brFile.toPath());
        Whiteboard wbA = new Whiteboard();
        Whiteboard wbB = new Whiteboard();
        Player player = new Player();
        player.replay(loader, wbA, wbB);

        // After processing a full battle, at least one side should have dealt damage
        double totalDamage = wbA.getDamageDealt() + wbB.getDamageDealt();
        assertTrue(totalDamage > 0, "At least one robot should have dealt bullet damage");
    }

    @Test
    void shotsFiredCountMatchesGodView() throws Exception {
        File brFile = findFirstRecording();
        final Loader loader = new Loader(brFile.toPath());

        // Count actual NEW bullets from god-view per owner
        // (FIRED state is not stored in recordings; detect new bulletIds instead)
        final Map<Integer, Integer> godViewShotsByOwner = new HashMap<Integer, Integer>();
        final Map<Integer, BulletState> prevStates = new HashMap<Integer, BulletState>();

        loader.forEachTurn(new Loader.TurnConsumer() {
            public void accept(int roundIndex, ITurnSnapshot turn) {
                IBulletSnapshot[] bullets = turn.getBullets();
                if (bullets == null) return;
                Map<Integer, BulletState> currentStates = new HashMap<Integer, BulletState>();
                for (IBulletSnapshot b : bullets) {
                    BulletState state = b.getState();
                    if (state == BulletState.INACTIVE || state == BulletState.EXPLODED) continue;
                    int bulletId = b.getBulletId();
                    currentStates.put(bulletId, state);
                    // New bullet = not seen in previous tick
                    if (!prevStates.containsKey(bulletId)) {
                        int owner = b.getOwnerIndex();
                        Integer count = godViewShotsByOwner.get(owner);
                        godViewShotsByOwner.put(owner, count == null ? 1 : count + 1);
                    }
                }
                prevStates.clear();
                prevStates.putAll(currentStates);
            }
        });

        // Now process through Player
        Loader loader2 = new Loader(brFile.toPath());
        Whiteboard wbA = new Whiteboard();
        Whiteboard wbB = new Whiteboard();
        Player player = new Player();
        player.replay(loader2, wbA, wbB);

        // Both robots should have fired
        int totalGodViewShots = 0;
        for (Integer count : godViewShotsByOwner.values()) {
            totalGodViewShots += count;
        }
        assertTrue(totalGodViewShots > 0, "God-view should show bullets fired");

        int totalPlayerShots = wbA.getOurShotsFired() + wbB.getOurShotsFired();
        assertEquals(totalGodViewShots, totalPlayerShots,
                "Total shots fired from Player should match god-view");
    }

    @Test
    void energyDropDetectionAlignsWithGodViewFires() throws Exception {
        // This test verifies that on ticks where energy drop detection flags
        // opponent_fired=true, a FIRED bullet from the opponent actually exists
        // in the god-view data (allowing for 1-tick offset due to timing)
        File brFile = findFirstRecording();
        final Loader loader = new Loader(brFile.toPath());
        final Transformer transformer = createTransformer();
        final Whiteboard wbA = new Whiteboard();

        // Collect god-view fire ticks for robot B (opponent of A)
        // Detect new bullets by bulletId (FIRED state not stored in recordings)
        final Map<Long, Double> godViewFiresByTick = new HashMap<Long, Double>();
        final Map<Integer, BulletState> prevBulletStates = new HashMap<Integer, BulletState>();

        loader.forEachTurn(new Loader.TurnConsumer() {
            public void accept(int roundIndex, ITurnSnapshot turn) {
                IRobotSnapshot[] robots = turn.getRobots();
                if (robots.length < 2) return;
                int indexB = robots[1].getContestantIndex();

                IBulletSnapshot[] bullets = turn.getBullets();
                if (bullets == null) return;
                Map<Integer, BulletState> currentStates = new HashMap<Integer, BulletState>();
                for (IBulletSnapshot b : bullets) {
                    BulletState state = b.getState();
                    if (state == BulletState.INACTIVE || state == BulletState.EXPLODED) continue;
                    int bulletId = b.getBulletId();
                    currentStates.put(bulletId, state);
                    // New bullet from B
                    if (!prevBulletStates.containsKey(bulletId) && b.getOwnerIndex() == indexB) {
                        godViewFiresByTick.put((long) turn.getTurn(), b.getPower());
                    }
                }
                prevBulletStates.clear();
                prevBulletStates.putAll(currentStates);
            }
        });

        // Now process through full pipeline and check fire detection
        final Loader loader2 = new Loader(brFile.toPath());
        final int[] detectedFires = {0};
        final int[] confirmedByGodView = {0};

        loader2.forEachTurn(new Loader.TurnConsumer() {
            private int lastRound = -1;
            private double prevRadarHeading = Double.NaN;

            public void accept(int roundIndex, ITurnSnapshot turn) {
                IRobotSnapshot[] robots = turn.getRobots();
                if (robots.length < 2) return;

                if (roundIndex != lastRound) {
                    robocode.BattleRules rules = loader2.getRecordInfo().battleRules;
                    wbA.onRoundStart(roundIndex, rules.getBattlefieldWidth(),
                            rules.getBattlefieldHeight(), rules.getGunCoolingRate(),
                            loader2.getRecordInfo().roundsCount);
                    prevRadarHeading = Double.NaN;
                    lastRound = roundIndex;
                }

                IRobotSnapshot us = robots[0];
                IRobotSnapshot opp = robots[1];

                wbA.setTick(turn.getTurn());
                wbA.setOurState(us.getX(), us.getY(), us.getBodyHeading(),
                        us.getGunHeading(), us.getRadarHeading(),
                        us.getVelocity(), us.getEnergy(), us.getGunHeat());

                // Process bullet events
                processBulletsForA(turn, robots, wbA);

                boolean isFirstTick = (turn.getTurn() == 0);
                if (opp.getState() != robocode.control.snapshot.RobotState.DEAD) {
                    if (isFirstTick || Player.radarSweepIntersects(
                            us.getX(), us.getY(), prevRadarHeading,
                            us.getRadarHeading(), opp.getX(), opp.getY())) {
                        wbA.setOpponentScan(opp.getX(), opp.getY(),
                                opp.getBodyHeading(), opp.getVelocity(), opp.getEnergy());
                    }
                }

                prevRadarHeading = us.getRadarHeading();
                transformer.process(wbA);

                if (wbA.hasFeature(Feature.OPPONENT_FIRED)
                        && wbA.getFeature(Feature.OPPONENT_FIRED) > 0.5) {
                    detectedFires[0]++;
                    long tick = turn.getTurn();
                    // Check god-view: fire may be on this tick or previous tick
                    if (godViewFiresByTick.containsKey(tick)
                            || godViewFiresByTick.containsKey(tick - 1)) {
                        confirmedByGodView[0]++;
                    }
                }

                wbA.advanceTick();
            }

            private final Map<Integer, BulletState> prevStates = new HashMap<Integer, BulletState>();

            private void processBulletsForA(ITurnSnapshot turn, IRobotSnapshot[] robots, Whiteboard wb) {
                IBulletSnapshot[] bullets = turn.getBullets();
                if (bullets == null) return;
                int indexA = robots[0].getContestantIndex();
                int indexB = robots[1].getContestantIndex();

                Map<Integer, BulletState> currentStates = new HashMap<Integer, BulletState>();
                for (IBulletSnapshot b : bullets) {
                    BulletState state = b.getState();
                    if (state == BulletState.INACTIVE || state == BulletState.EXPLODED) continue;
                    int bulletId = b.getBulletId();
                    currentStates.put(bulletId, state);

                    if (state == BulletState.HIT_VICTIM) {
                        BulletState prev = prevStates.get(bulletId);
                        if (prev != BulletState.HIT_VICTIM) {
                            if (b.getOwnerIndex() == indexA && b.getVictimIndex() == indexB) {
                                wb.setWeHitOpponentThisTick(true);
                            }
                        }
                    }
                }
                prevStates.clear();
                prevStates.putAll(currentStates);
            }
        });

        assertTrue(detectedFires[0] > 0, "Should detect at least some opponent fires");
        // Allow some tolerance — energy drops can be caused by other events
        // that aren't fires. At least 70% of detections should be confirmed.
        double accuracy = (double) confirmedByGodView[0] / detectedFires[0];
        assertTrue(accuracy >= 0.7,
                "At least 70% of detected fires should be confirmed by god-view. "
                + "Detected: " + detectedFires[0] + ", Confirmed: " + confirmedByGodView[0]);
    }

    @Test
    void damageDealtMatchesBulletHits() throws Exception {
        File brFile = findFirstRecording();
        Loader loader = new Loader(brFile.toPath());
        Whiteboard wbA = new Whiteboard();
        Whiteboard wbB = new Whiteboard();
        Player player = new Player();
        player.replay(loader, wbA, wbB);

        // Verify damage dealt by A = damage received by B and vice versa
        assertEquals(wbA.getDamageDealt(), wbB.getDamageReceived(), 0.01,
                "A's damage dealt should equal B's damage received");
        assertEquals(wbB.getDamageDealt(), wbA.getDamageReceived(), 0.01,
                "B's damage dealt should equal A's damage received");
    }

    private Transformer createTransformer() {
        Transformer t = new Transformer();
        t.register(new SpatialOfflineFeatures());
        t.register(new MovementOfflineFeatures());
        t.register(new EnergyOfflineFeatures());
        t.register(new TimingOfflineFeatures());
        t.resolveDependencies();
        return t;
    }

    private File findFirstRecording() {
        File dir = RECORDINGS_DIR.toFile();
        assertTrue(dir.exists(), "Recordings directory must exist: " + dir.getAbsolutePath());
        File[] brFiles = dir.listFiles(new java.io.FilenameFilter() {
            public boolean accept(File d, String name) {
                return name.endsWith(".br");
            }
        });
        assertNotNull(brFiles);
        assertTrue(brFiles.length > 0, "Must have at least one .br file");
        return brFiles[0];
    }
}

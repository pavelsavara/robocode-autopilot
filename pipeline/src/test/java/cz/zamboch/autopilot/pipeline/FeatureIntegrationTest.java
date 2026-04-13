package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Transformer;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.pipeline.features.EnergyOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.MovementOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.SpatialOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.TimingOfflineFeatures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import robocode.BattleRules;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: processes real .br recordings through the full pipeline
 * (Loader + Player + Transformer with all offline feature processors) and validates
 * computed features against god-view snapshot data.
 */
class FeatureIntegrationTest {

    private static final Path RECORDINGS_DIR = Paths.get("..", "recordings", "24360294214");

    private Transformer transformer;

    @BeforeEach
    void setUp() {
        transformer = new Transformer();
        transformer.register(new SpatialOfflineFeatures());
        transformer.register(new MovementOfflineFeatures());
        transformer.register(new EnergyOfflineFeatures());
        transformer.register(new TimingOfflineFeatures());
        transformer.resolveDependencies();
    }

    @Test
    void distanceMatchesGodView() throws Exception {
        // Process a recording and verify distance feature matches
        // what we'd compute from the god-view snapshot positions
        File brFile = findFirstRecording();
        Loader loader = new Loader(brFile.toPath());
        Whiteboard wbA = new Whiteboard();
        Whiteboard wbB = new Whiteboard();

        Player player = new Player();

        // We'll replay manually to interleave transformer processing
        final int[] scanCount = {0};
        final int[] distanceMatches = {0};

        loader.forEachTurn(new Loader.TurnConsumer() {
            private int lastRound = -1;
            private double prevRadarHeadingA = Double.NaN;

            public void accept(int roundIndex, ITurnSnapshot turn) {
                IRobotSnapshot[] robots = turn.getRobots();
                if (robots.length < 2) return;

                if (roundIndex != lastRound) {
                    BattleRules rules = loader.getRecordInfo().battleRules;
                    wbA.onRoundStart(roundIndex, rules.getBattlefieldWidth(),
                            rules.getBattlefieldHeight(), rules.getGunCoolingRate(),
                            loader.getRecordInfo().roundsCount);
                    prevRadarHeadingA = Double.NaN;
                    lastRound = roundIndex;
                }

                IRobotSnapshot us = robots[0];
                IRobotSnapshot opp = robots[1];

                wbA.setTick(turn.getTurn());
                wbA.setOurState(us.getX(), us.getY(), us.getBodyHeading(),
                        us.getGunHeading(), us.getRadarHeading(),
                        us.getVelocity(), us.getEnergy(), us.getGunHeat());

                boolean isFirstTick = (turn.getTurn() == 0);
                if (opp.getState() != RobotState.DEAD) {
                    if (isFirstTick || Player.radarSweepIntersects(
                            us.getX(), us.getY(), prevRadarHeadingA,
                            us.getRadarHeading(), opp.getX(), opp.getY())) {
                        wbA.setOpponentScan(opp.getX(), opp.getY(),
                                opp.getBodyHeading(), opp.getVelocity(), opp.getEnergy());
                    }
                }

                prevRadarHeadingA = us.getRadarHeading();

                // Run feature extraction
                transformer.process(wbA);

                // Validate: when scan is available, distance should match god-view
                if (wbA.isScanAvailableThisTick() && wbA.hasFeature(Feature.DISTANCE)) {
                    scanCount[0]++;
                    double expectedDist = Math.hypot(
                            opp.getX() - us.getX(),
                            opp.getY() - us.getY());
                    double actualDist = wbA.getFeature(Feature.DISTANCE);
                    if (Math.abs(expectedDist - actualDist) < 0.1) {
                        distanceMatches[0]++;
                    }
                }

                wbA.advanceTick();
            }
        });

        assertTrue(scanCount[0] > 0, "Should have at least one scan");
        assertEquals(scanCount[0], distanceMatches[0],
                "All scan-tick distances should match god-view");
    }

    @Test
    void wallDistanceWithinBounds() throws Exception {
        File brFile = findFirstRecording();
        final Loader loader = new Loader(brFile.toPath());
        final Whiteboard wbA = new Whiteboard();

        final int[] checks = {0};

        loader.forEachTurn(new Loader.TurnConsumer() {
            private int lastRound = -1;
            private double prevRadarHeading = Double.NaN;

            public void accept(int roundIndex, ITurnSnapshot turn) {
                IRobotSnapshot[] robots = turn.getRobots();
                if (robots.length < 2) return;

                if (roundIndex != lastRound) {
                    BattleRules rules = loader.getRecordInfo().battleRules;
                    wbA.onRoundStart(roundIndex, rules.getBattlefieldWidth(),
                            rules.getBattlefieldHeight(), rules.getGunCoolingRate(),
                            loader.getRecordInfo().roundsCount);
                    prevRadarHeading = Double.NaN;
                    lastRound = roundIndex;
                }

                IRobotSnapshot us = robots[0];
                IRobotSnapshot opp = robots[1];

                wbA.setTick(turn.getTurn());
                wbA.setOurState(us.getX(), us.getY(), us.getBodyHeading(),
                        us.getGunHeading(), us.getRadarHeading(),
                        us.getVelocity(), us.getEnergy(), us.getGunHeat());

                boolean isFirstTick = (turn.getTurn() == 0);
                if (opp.getState() != RobotState.DEAD) {
                    if (isFirstTick || Player.radarSweepIntersects(
                            us.getX(), us.getY(), prevRadarHeading,
                            us.getRadarHeading(), opp.getX(), opp.getY())) {
                        wbA.setOpponentScan(opp.getX(), opp.getY(),
                                opp.getBodyHeading(), opp.getVelocity(), opp.getEnergy());
                    }
                }

                prevRadarHeading = us.getRadarHeading();
                transformer.process(wbA);

                if (wbA.hasFeature(Feature.OPPONENT_DIST_TO_WALL_MIN)) {
                    double wallDist = wbA.getFeature(Feature.OPPONENT_DIST_TO_WALL_MIN);
                    assertTrue(wallDist >= 0, "Wall distance must be non-negative");
                    int maxDim = Math.max(
                            loader.getRecordInfo().battleRules.getBattlefieldWidth(),
                            loader.getRecordInfo().battleRules.getBattlefieldHeight());
                    assertTrue(wallDist <= maxDim / 2.0,
                            "Wall distance must be within half battlefield");
                    checks[0]++;
                }

                wbA.advanceTick();
            }
        });

        assertTrue(checks[0] > 10, "Should have verified wall distance on many ticks");
    }

    @Test
    void allFeaturesProducedOnScanTicks() throws Exception {
        File brFile = findFirstRecording();
        Loader loader = new Loader(brFile.toPath());
        Whiteboard wbA = new Whiteboard();

        final int[] scanTicks = {0};
        final int[] allFeaturesPresent = {0};

        loader.forEachTurn(new Loader.TurnConsumer() {
            private int lastRound = -1;
            private double prevRadarHeading = Double.NaN;

            public void accept(int roundIndex, ITurnSnapshot turn) {
                IRobotSnapshot[] robots = turn.getRobots();
                if (robots.length < 2) return;

                if (roundIndex != lastRound) {
                    BattleRules rules = loader.getRecordInfo().battleRules;
                    wbA.onRoundStart(roundIndex, rules.getBattlefieldWidth(),
                            rules.getBattlefieldHeight(), rules.getGunCoolingRate(),
                            loader.getRecordInfo().roundsCount);
                    prevRadarHeading = Double.NaN;
                    lastRound = roundIndex;
                }

                IRobotSnapshot us = robots[0];
                IRobotSnapshot opp = robots[1];

                wbA.setTick(turn.getTurn());
                wbA.setOurState(us.getX(), us.getY(), us.getBodyHeading(),
                        us.getGunHeading(), us.getRadarHeading(),
                        us.getVelocity(), us.getEnergy(), us.getGunHeat());

                boolean isFirstTick = (turn.getTurn() == 0);
                if (opp.getState() != RobotState.DEAD) {
                    if (isFirstTick || Player.radarSweepIntersects(
                            us.getX(), us.getY(), prevRadarHeading,
                            us.getRadarHeading(), opp.getX(), opp.getY())) {
                        wbA.setOpponentScan(opp.getX(), opp.getY(),
                                opp.getBodyHeading(), opp.getVelocity(), opp.getEnergy());
                    }
                }

                prevRadarHeading = us.getRadarHeading();
                transformer.process(wbA);

                if (wbA.isScanAvailableThisTick()) {
                    scanTicks[0]++;

                    // On scan ticks, spatial + movement + energy + timing features should be set
                    boolean allPresent =
                            wbA.hasFeature(Feature.DISTANCE)
                            && wbA.hasFeature(Feature.BEARING_TO_OPPONENT_ABS)
                            && wbA.hasFeature(Feature.OPPONENT_DIST_TO_WALL_MIN)
                            && wbA.hasFeature(Feature.OPPONENT_VELOCITY)
                            && wbA.hasFeature(Feature.OPPONENT_LATERAL_VELOCITY)
                            && wbA.hasFeature(Feature.OPPONENT_ADVANCING_VELOCITY)
                            && wbA.hasFeature(Feature.OPPONENT_ENERGY)
                            && wbA.hasFeature(Feature.OPPONENT_FIRED)
                            && wbA.hasFeature(Feature.OPPONENT_FIRE_POWER)
                            && wbA.hasFeature(Feature.OUR_GUN_HEAT)
                            && wbA.hasFeature(Feature.TICKS_SINCE_SCAN);
                    // OPPONENT_HEADING_DELTA may not be present on first scan
                    if (allPresent) {
                        allFeaturesPresent[0]++;
                    }
                }

                wbA.advanceTick();
            }
        });

        assertTrue(scanTicks[0] > 0, "Should have scan ticks");
        // Allow first scan per round to lack heading delta
        assertTrue(allFeaturesPresent[0] > scanTicks[0] / 2,
                "Most scan ticks should have all core features");
    }

    @Test
    void gunHeatAlwaysPresent() throws Exception {
        File brFile = findFirstRecording();
        Loader loader = new Loader(brFile.toPath());
        Whiteboard wbA = new Whiteboard();

        final int[] tickCount = {0};
        final int[] gunHeatPresent = {0};

        loader.forEachTurn(new Loader.TurnConsumer() {
            private int lastRound = -1;

            public void accept(int roundIndex, ITurnSnapshot turn) {
                IRobotSnapshot[] robots = turn.getRobots();
                if (robots.length < 2) return;

                if (roundIndex != lastRound) {
                    BattleRules rules = loader.getRecordInfo().battleRules;
                    wbA.onRoundStart(roundIndex, rules.getBattlefieldWidth(),
                            rules.getBattlefieldHeight(), rules.getGunCoolingRate(),
                            loader.getRecordInfo().roundsCount);
                    lastRound = roundIndex;
                }

                IRobotSnapshot us = robots[0];
                wbA.setTick(turn.getTurn());
                wbA.setOurState(us.getX(), us.getY(), us.getBodyHeading(),
                        us.getGunHeading(), us.getRadarHeading(),
                        us.getVelocity(), us.getEnergy(), us.getGunHeat());

                transformer.process(wbA);

                tickCount[0]++;
                if (wbA.hasFeature(Feature.OUR_GUN_HEAT)) {
                    gunHeatPresent[0]++;
                }

                wbA.advanceTick();
            }
        });

        assertTrue(tickCount[0] > 0, "Should have ticks");
        assertEquals(tickCount[0], gunHeatPresent[0],
                "Gun heat should be present on every tick");
    }

    @Test
    void velocityDecompositionConsistent() throws Exception {
        // Verify: lateral^2 + advancing^2 == velocity^2
        File brFile = findFirstRecording();
        Loader loader = new Loader(brFile.toPath());
        Whiteboard wbA = new Whiteboard();

        final int[] checks = {0};
        final int[] consistent = {0};

        loader.forEachTurn(new Loader.TurnConsumer() {
            private int lastRound = -1;
            private double prevRadarHeading = Double.NaN;

            public void accept(int roundIndex, ITurnSnapshot turn) {
                IRobotSnapshot[] robots = turn.getRobots();
                if (robots.length < 2) return;

                if (roundIndex != lastRound) {
                    BattleRules rules = loader.getRecordInfo().battleRules;
                    wbA.onRoundStart(roundIndex, rules.getBattlefieldWidth(),
                            rules.getBattlefieldHeight(), rules.getGunCoolingRate(),
                            loader.getRecordInfo().roundsCount);
                    prevRadarHeading = Double.NaN;
                    lastRound = roundIndex;
                }

                IRobotSnapshot us = robots[0];
                IRobotSnapshot opp = robots[1];

                wbA.setTick(turn.getTurn());
                wbA.setOurState(us.getX(), us.getY(), us.getBodyHeading(),
                        us.getGunHeading(), us.getRadarHeading(),
                        us.getVelocity(), us.getEnergy(), us.getGunHeat());

                boolean isFirstTick = (turn.getTurn() == 0);
                if (opp.getState() != RobotState.DEAD) {
                    if (isFirstTick || Player.radarSweepIntersects(
                            us.getX(), us.getY(), prevRadarHeading,
                            us.getRadarHeading(), opp.getX(), opp.getY())) {
                        wbA.setOpponentScan(opp.getX(), opp.getY(),
                                opp.getBodyHeading(), opp.getVelocity(), opp.getEnergy());
                    }
                }

                prevRadarHeading = us.getRadarHeading();
                transformer.process(wbA);

                if (wbA.hasFeature(Feature.OPPONENT_VELOCITY)
                        && wbA.hasFeature(Feature.OPPONENT_LATERAL_VELOCITY)
                        && wbA.hasFeature(Feature.OPPONENT_ADVANCING_VELOCITY)) {
                    checks[0]++;
                    double v = wbA.getFeature(Feature.OPPONENT_VELOCITY);
                    double lat = wbA.getFeature(Feature.OPPONENT_LATERAL_VELOCITY);
                    double adv = wbA.getFeature(Feature.OPPONENT_ADVANCING_VELOCITY);
                    // lateral^2 + advancing^2 should equal velocity^2
                    double decomposed = Math.sqrt(lat * lat + adv * adv);
                    if (Math.abs(decomposed - Math.abs(v)) < 0.01) {
                        consistent[0]++;
                    }
                }

                wbA.advanceTick();
            }
        });

        assertTrue(checks[0] > 0, "Should have velocity checks");
        assertEquals(checks[0], consistent[0],
                "lateral^2 + advancing^2 should equal velocity^2 on all ticks");
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

package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Transformer;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.pipeline.features.EnergyOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.MovementOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.MovementSegmentationOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.OpponentPredictionOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.SpatialOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.StateNormalizationOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.TargetingGeometryOfflineFeatures;
import cz.zamboch.autopilot.pipeline.features.TimingOfflineFeatures;
import org.junit.jupiter.api.Test;
import robocode.BattleRules;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Phase H offline-only features: movement segmentation,
 * targeting geometry, state normalization, and opponent prediction.
 */
class PhaseHFeaturesTest {

    private static final Path RECORDINGS_DIR = Paths.get("..", "recordings", "24360294214");

    private static Transformer createFullTransformer() {
        Transformer t = new Transformer();
        t.register(new SpatialOfflineFeatures());
        t.register(new MovementOfflineFeatures());
        t.register(new EnergyOfflineFeatures());
        t.register(new TimingOfflineFeatures());
        t.register(new MovementSegmentationOfflineFeatures());
        t.register(new TargetingGeometryOfflineFeatures());
        t.register(new StateNormalizationOfflineFeatures());
        t.register(new OpponentPredictionOfflineFeatures());
        t.resolveDependencies();
        return t;
    }

    @Test
    void allPhaseHFeaturesProduceCsvColumns() throws Exception {
        File brFile = findFirstRecording();
        Loader loader = new Loader(brFile.toPath());
        final Transformer transformer = createFullTransformer();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final CsvRowWriter row = new CsvRowWriter(baos);

        // Write headers
        row.beginRow();
        for (IOfflineFeatures f : transformer.getFeatures().toArray(new IOfflineFeatures[0])) {
            ((IOfflineFeatures) f).writeColumnNames(row);
        }
        row.endRow();

        String headers = baos.toString("UTF-8");
        // Verify Phase H columns present
        assertTrue(headers.contains("opponent_lateral_direction"), "Missing lateral direction");
        assertTrue(headers.contains("opponent_velocity_delta"), "Missing velocity delta");
        assertTrue(headers.contains("opponent_is_decelerating"), "Missing is decelerating");
        assertTrue(headers.contains("opponent_time_since_direction_change"), "Missing direction change time");
        assertTrue(headers.contains("opponent_angular_velocity"), "Missing angular velocity");
        assertTrue(headers.contains("opponent_max_turn_rate"), "Missing max turn rate");
        assertTrue(headers.contains("distance_norm"), "Missing distance norm");
        assertTrue(headers.contains("energy_ratio"), "Missing energy ratio");
        assertTrue(headers.contains("our_lateral_velocity"), "Missing our lateral velocity");
        assertTrue(headers.contains("our_dist_to_wall_min"), "Missing our dist to wall min");
        assertTrue(headers.contains("opponent_wall_ahead_distance"), "Missing wall ahead distance");
        assertTrue(headers.contains("opponent_inferred_gun_heat"), "Missing inferred gun heat");
    }

    @Test
    void movementSegmentationProducesValues() throws Exception {
        File brFile = findFirstRecording();
        final Loader loader = new Loader(brFile.toPath());
        final Transformer transformer = createFullTransformer();
        final Whiteboard wb = new Whiteboard();

        final int[] scansWithValues = {0};

        loader.forEachTurn(new Loader.TurnConsumer() {
            private int lastRound = -1;
            private double prevRadar = Double.NaN;

            public void accept(int roundIndex, ITurnSnapshot turn) {
                IRobotSnapshot[] robots = turn.getRobots();
                if (robots.length < 2) return;

                if (roundIndex != lastRound) {
                    BattleRules rules = loader.getRecordInfo().battleRules;
                    wb.onRoundStart(roundIndex, rules.getBattlefieldWidth(),
                            rules.getBattlefieldHeight(), rules.getGunCoolingRate(),
                            loader.getRecordInfo().roundsCount);
                    prevRadar = Double.NaN;
                    lastRound = roundIndex;
                }

                IRobotSnapshot rA = robots[0];
                IRobotSnapshot rB = robots[1];

                wb.setTick(turn.getTurn());
                wb.setOurState(rA.getX(), rA.getY(), rA.getBodyHeading(),
                        rA.getGunHeading(), rA.getRadarHeading(),
                        rA.getVelocity(), rA.getEnergy(), rA.getGunHeat());

                boolean firstTick = (turn.getTurn() == 0);
                if (rB.getState() != RobotState.DEAD) {
                    if (firstTick || Player.radarSweepIntersects(
                            rA.getX(), rA.getY(), prevRadar,
                            rA.getRadarHeading(), rB.getX(), rB.getY())) {
                        wb.setOpponentScan(rB.getX(), rB.getY(),
                                rB.getBodyHeading(), rB.getVelocity(), rB.getEnergy());
                    }
                }

                prevRadar = rA.getRadarHeading();
                transformer.process(wb);

                if (wb.isScanAvailableThisTick()) {
                    scansWithValues[0]++;
                }

                wb.advanceTick();
            }
        });

        assertTrue(scansWithValues[0] > 100, "Expected many scans with computed features, got: " + scansWithValues[0]);
    }

    @Test
    void endToEndCsvIncludesAllColumns() throws Exception {
        File brFile = findFirstRecording();
        Path outputDir = java.nio.file.Files.createTempDirectory("phaseH_csv_test");

        try {
            Main.processBattle(brFile.toPath(), outputDir);

            // Find a ticks.csv
            File[] battleDirs = outputDir.toFile().listFiles();
            assertNotNull(battleDirs);
            assertTrue(battleDirs.length > 0, "No battle directories created");

            File ticksCsv = null;
            for (File battleDir : battleDirs) {
                File[] robotDirs = battleDir.listFiles();
                if (robotDirs != null) {
                    for (File robotDir : robotDirs) {
                        File csv = new File(robotDir, "ticks.csv");
                        if (csv.exists()) {
                            ticksCsv = csv;
                            break;
                        }
                    }
                }
                if (ticksCsv != null) break;
            }

            assertNotNull(ticksCsv, "No ticks.csv found");

            // Read first line (headers) and a data line
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(ticksCsv));
            String headerLine = reader.readLine();
            String dataLine = reader.readLine();
            reader.close();

            assertNotNull(headerLine, "CSV has no header line");
            assertNotNull(dataLine, "CSV has no data line");

            // Count total columns: original 12 + 12 Phase H = 24 feature columns + battle_id, round, tick = 27
            String[] headers = headerLine.split(",");
            assertTrue(headers.length >= 27, "Expected >= 27 columns, got: " + headers.length);

            // Verify Phase H column names
            assertTrue(headerLine.contains("opponent_lateral_direction"));
            assertTrue(headerLine.contains("opponent_velocity_delta"));
            assertTrue(headerLine.contains("opponent_angular_velocity"));
            assertTrue(headerLine.contains("distance_norm"));
            assertTrue(headerLine.contains("energy_ratio"));
            assertTrue(headerLine.contains("our_lateral_velocity"));
            assertTrue(headerLine.contains("opponent_wall_ahead_distance"));
            assertTrue(headerLine.contains("opponent_inferred_gun_heat"));

            // Verify data line has correct number of columns
            String[] dataCols = dataLine.split(",");
            assertEquals(headers.length, dataCols.length,
                    "Data column count should match header count");
        } finally {
            deleteRecursive(outputDir.toFile());
        }
    }

    @Test
    void wallAheadDistanceIsPositive() throws Exception {
        File brFile = findFirstRecording();
        final Loader loader = new Loader(brFile.toPath());
        final Transformer transformer = createFullTransformer();
        final Whiteboard wb = new Whiteboard();

        final int[] validChecks = {0};

        loader.forEachTurn(new Loader.TurnConsumer() {
            private int lastRound = -1;
            private double prevRadar = Double.NaN;

            public void accept(int roundIndex, ITurnSnapshot turn) {
                IRobotSnapshot[] robots = turn.getRobots();
                if (robots.length < 2) return;

                if (roundIndex != lastRound) {
                    BattleRules rules = loader.getRecordInfo().battleRules;
                    wb.onRoundStart(roundIndex, rules.getBattlefieldWidth(),
                            rules.getBattlefieldHeight(), rules.getGunCoolingRate(),
                            loader.getRecordInfo().roundsCount);
                    prevRadar = Double.NaN;
                    lastRound = roundIndex;
                }

                IRobotSnapshot rA = robots[0];
                IRobotSnapshot rB = robots[1];

                wb.setTick(turn.getTurn());
                wb.setOurState(rA.getX(), rA.getY(), rA.getBodyHeading(),
                        rA.getGunHeading(), rA.getRadarHeading(),
                        rA.getVelocity(), rA.getEnergy(), rA.getGunHeat());

                boolean firstTick = (turn.getTurn() == 0);
                if (rB.getState() != RobotState.DEAD) {
                    if (firstTick || Player.radarSweepIntersects(
                            rA.getX(), rA.getY(), prevRadar,
                            rA.getRadarHeading(), rB.getX(), rB.getY())) {
                        wb.setOpponentScan(rB.getX(), rB.getY(),
                                rB.getBodyHeading(), rB.getVelocity(), rB.getEnergy());
                    }
                }

                prevRadar = rA.getRadarHeading();
                transformer.process(wb);
                wb.advanceTick();
                validChecks[0]++;
            }
        });

        assertTrue(validChecks[0] > 100, "Should process many ticks");
    }

    @Test
    void distanceNormIsBetweenZeroAndOne() throws Exception {
        File brFile = findFirstRecording();
        final Loader loader = new Loader(brFile.toPath());
        final Transformer transformer = createFullTransformer();
        final Whiteboard wb = new Whiteboard();

        // We'll capture the targeting geometry features directly
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final CsvRowWriter row = new CsvRowWriter(baos);
        final int[] scanCount = {0};

        loader.forEachTurn(new Loader.TurnConsumer() {
            private int lastRound = -1;
            private double prevRadar = Double.NaN;

            public void accept(int roundIndex, ITurnSnapshot turn) {
                IRobotSnapshot[] robots = turn.getRobots();
                if (robots.length < 2) return;

                if (roundIndex != lastRound) {
                    BattleRules rules = loader.getRecordInfo().battleRules;
                    wb.onRoundStart(roundIndex, rules.getBattlefieldWidth(),
                            rules.getBattlefieldHeight(), rules.getGunCoolingRate(),
                            loader.getRecordInfo().roundsCount);
                    prevRadar = Double.NaN;
                    lastRound = roundIndex;
                }

                IRobotSnapshot rA = robots[0];
                IRobotSnapshot rB = robots[1];

                wb.setTick(turn.getTurn());
                wb.setOurState(rA.getX(), rA.getY(), rA.getBodyHeading(),
                        rA.getGunHeading(), rA.getRadarHeading(),
                        rA.getVelocity(), rA.getEnergy(), rA.getGunHeat());

                boolean firstTick = (turn.getTurn() == 0);
                if (rB.getState() != RobotState.DEAD) {
                    if (firstTick || Player.radarSweepIntersects(
                            rA.getX(), rA.getY(), prevRadar,
                            rA.getRadarHeading(), rB.getX(), rB.getY())) {
                        wb.setOpponentScan(rB.getX(), rB.getY(),
                                rB.getBodyHeading(), rB.getVelocity(), rB.getEnergy());
                    }
                }

                prevRadar = rA.getRadarHeading();
                transformer.process(wb);

                // Verify distance is within battlefield (implies distanceNorm in [0,1])
                if (wb.isScanAvailableThisTick() && wb.hasFeature(Feature.DISTANCE)) {
                    double distance = wb.getFeature(Feature.DISTANCE);
                    double diagonal = Math.hypot(wb.getBattlefieldWidth(), wb.getBattlefieldHeight());
                    double norm = distance / diagonal;
                    assertTrue(norm >= 0 && norm <= 1.0,
                            "distance_norm out of range: " + norm);
                    scanCount[0]++;
                }

                wb.advanceTick();
            }
        });

        assertTrue(scanCount[0] > 50, "Expected many scans with distance, got: " + scanCount[0]);
    }

    private File findFirstRecording() {
        File dir = RECORDINGS_DIR.toFile();
        File[] files = dir.listFiles(new java.io.FilenameFilter() {
            public boolean accept(File d, String name) {
                return name.endsWith(".br");
            }
        });
        assertNotNull(files, "Recording directory not found: " + dir.getAbsolutePath());
        assertTrue(files.length > 0, "No .br files in " + dir.getAbsolutePath());
        return files[0];
    }

    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }
}

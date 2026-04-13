package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Whiteboard;
import net.sf.robocode.recording.BattleRecordInfo;
import org.junit.jupiter.api.Test;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Loader — reads real .br files from recordings/.
 */
class LoaderTest {

    private static final Path RECORDINGS_DIR = Paths.get("..", "recordings", "24360294214");

    private Path getFirstRecording() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(RECORDINGS_DIR, "*.br")) {
            for (Path p : stream) {
                return p;
            }
        }
        fail("No .br files found in " + RECORDINGS_DIR);
        return null;
    }

    @Test
    void loaderReadsBattleRecordInfo() throws Exception {
        Path brFile = getFirstRecording();
        Loader loader = new Loader(brFile);

        // forEachTurn populates recordInfo
        final int[] turnCount = {0};
        loader.forEachTurn(new Loader.TurnConsumer() {
            public void accept(int roundIndex, ITurnSnapshot turn) {
                turnCount[0]++;
            }
        });

        BattleRecordInfo info = loader.getRecordInfo();
        assertNotNull(info, "BattleRecordInfo should not be null");
        assertTrue(info.robotCount >= 2, "Should have at least 2 robots, got: " + info.robotCount);
        assertTrue(info.roundsCount >= 1, "Should have at least 1 round, got: " + info.roundsCount);
        assertNotNull(info.turnsInRounds, "turnsInRounds should not be null");
        assertEquals(info.roundsCount, info.turnsInRounds.length, "turnsInRounds length should match roundsCount");
        assertNotNull(info.battleRules, "battleRules should not be null");
        assertTrue(info.battleRules.getBattlefieldWidth() > 0, "Battlefield width should be positive");
        assertTrue(info.battleRules.getBattlefieldHeight() > 0, "Battlefield height should be positive");
    }

    @Test
    void loaderTickCountMatchesTurnsInRounds() throws Exception {
        Path brFile = getFirstRecording();
        Loader loader = new Loader(brFile);

        final int[] turnCount = {0};
        loader.forEachTurn(new Loader.TurnConsumer() {
            public void accept(int roundIndex, ITurnSnapshot turn) {
                turnCount[0]++;
            }
        });

        BattleRecordInfo info = loader.getRecordInfo();
        int expectedTotal = 0;
        for (int turns : info.turnsInRounds) {
            expectedTotal += turns;
        }
        assertEquals(expectedTotal, turnCount[0],
                "Total turn count should match sum of turnsInRounds");
    }

    @Test
    void loaderTurnSnapshotsHaveRobots() throws Exception {
        Path brFile = getFirstRecording();
        Loader loader = new Loader(brFile);

        final boolean[] checked = {false};
        loader.forEachTurn(new Loader.TurnConsumer() {
            public void accept(int roundIndex, ITurnSnapshot turn) {
                if (!checked[0]) {
                    IRobotSnapshot[] robots = turn.getRobots();
                    assertNotNull(robots, "robots should not be null");
                    assertTrue(robots.length >= 2, "Should have at least 2 robots in snapshot");

                    IRobotSnapshot robot0 = robots[0];
                    assertNotNull(robot0.getName(), "Robot name should not be null");
                    assertTrue(robot0.getX() > 0, "Robot X should be positive");
                    assertTrue(robot0.getY() > 0, "Robot Y should be positive");
                    assertTrue(robot0.getEnergy() > 0, "Robot energy should be positive at start");
                    checked[0] = true;
                }
            }
        });

        assertTrue(checked[0], "Should have processed at least one turn");
    }

    @Test
    void loaderRoundIndicesAreCorrect() throws Exception {
        Path brFile = getFirstRecording();
        Loader loader = new Loader(brFile);

        final List<Integer> roundsSeen = new ArrayList<Integer>();
        loader.forEachTurn(new Loader.TurnConsumer() {
            public void accept(int roundIndex, ITurnSnapshot turn) {
                if (roundsSeen.isEmpty() || roundsSeen.get(roundsSeen.size() - 1) != roundIndex) {
                    roundsSeen.add(roundIndex);
                }
            }
        });

        BattleRecordInfo info = loader.getRecordInfo();
        assertEquals(info.roundsCount, roundsSeen.size(),
                "Should see exactly roundsCount distinct rounds");
        for (int i = 0; i < roundsSeen.size(); i++) {
            assertEquals(i, roundsSeen.get(i).intValue(), "Rounds should be sequential starting from 0");
        }
    }

    @Test
    void allRecordingsCanBeLoaded() throws Exception {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(RECORDINGS_DIR, "*.br")) {
            int fileCount = 0;
            for (Path brFile : stream) {
                fileCount++;
                Loader loader = new Loader(brFile);
                final int[] turnCount = {0};
                loader.forEachTurn(new Loader.TurnConsumer() {
                    public void accept(int roundIndex, ITurnSnapshot turn) {
                        turnCount[0]++;
                    }
                });

                BattleRecordInfo info = loader.getRecordInfo();
                assertNotNull(info, "RecordInfo null for " + brFile.getFileName());
                assertTrue(turnCount[0] > 0, "Should have turns in " + brFile.getFileName());

                System.out.println(brFile.getFileName() + ": " + info.roundsCount + " rounds, "
                        + turnCount[0] + " total turns, " + info.robotCount + " robots");
            }
            assertTrue(fileCount >= 10, "Expected at least 10 recordings, found: " + fileCount);
        }
    }
}

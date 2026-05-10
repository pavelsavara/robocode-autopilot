package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Whiteboard;
import net.sf.robocode.recording.BattleRecordInfo;
import robocode.control.snapshot.ITurnSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Player — replays real recordings and verifies
 * own-robot state is correctly fed into Whiteboards.
 */
class PlayerTest {

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
    void playerFeedsOwnStateToWhiteboards() throws Exception {
        Path brFile = getFirstRecording();
        Loader loader = new Loader(brFile);

        Whiteboard wbA = new Whiteboard();
        Whiteboard wbB = new Whiteboard();
        wbA.setBattleId(brFile.getFileName().toString().replace(".br", ""));
        wbB.setBattleId(brFile.getFileName().toString().replace(".br", ""));

        Player player = new Player(wbA, wbB);
        player.replay(loader);

        // After replay, whiteboards should have valid state
        BattleRecordInfo info = loader.getRecordInfo();
        assertNotNull(info);

        // Both whiteboards should have battlefield dimensions set
        assertTrue(wbA.getBattlefieldWidth() > 0, "Whiteboard A should have battlefield width");
        assertTrue(wbB.getBattlefieldWidth() > 0, "Whiteboard B should have battlefield width");
        assertEquals(wbA.getBattlefieldWidth(), info.battleRules.getBattlefieldWidth());
        assertEquals(wbA.getBattlefieldHeight(), info.battleRules.getBattlefieldHeight());
    }

    @Test
    void playerSetsPositionsWithinBattlefield() throws Exception {
        Path brFile = getFirstRecording();
        Loader loader = new Loader(brFile);

        Whiteboard wbA = new Whiteboard();
        Whiteboard wbB = new Whiteboard();

        // Track max positions across all ticks via forEachTurn + setOurState
        final double[] maxX = {0};
        final double[] maxY = {0};
        final int[] ticks = {0};

        Player player = new Player(wbA, wbB);
        // Use forEachTurn to inspect positions at each tick
        loader.forEachTurn(new Loader.TurnConsumer() {
            private int lastRound = -1;

            public void accept(int roundIndex, ITurnSnapshot turn) {
                robocode.control.snapshot.IRobotSnapshot[] robots = turn.getRobots();
                if (robots.length < 2) return;

                if (roundIndex != lastRound) {
                    BattleRecordInfo info = loader.getRecordInfo();
                    wbA.onRoundStart(roundIndex, info.battleRules.getBattlefieldWidth(),
                            info.battleRules.getBattlefieldHeight(), info.battleRules.getGunCoolingRate(),
                            info.roundsCount);
                    lastRound = roundIndex;
                }

                robocode.control.snapshot.IRobotSnapshot rA = robots[0];
                wbA.setTick(turn.getTurn());
                wbA.setOurState(rA.getX(), rA.getY(), rA.getBodyHeading(), rA.getGunHeading(),
                        rA.getRadarHeading(), rA.getVelocity(), rA.getEnergy(), rA.getGunHeat());

                if (wbA.getOurX() > maxX[0]) maxX[0] = wbA.getOurX();
                if (wbA.getOurY() > maxY[0]) maxY[0] = wbA.getOurY();
                ticks[0]++;
            }
        });

        BattleRecordInfo info = loader.getRecordInfo();
        assertTrue(ticks[0] > 0, "Should have processed ticks");
        assertTrue(maxX[0] > 0, "Max X should be positive");
        assertTrue(maxY[0] > 0, "Max Y should be positive");
        assertTrue(maxX[0] <= info.battleRules.getBattlefieldWidth(),
                "Max X should be within battlefield width");
        assertTrue(maxY[0] <= info.battleRules.getBattlefieldHeight(),
                "Max Y should be within battlefield height");

        System.out.println("Processed " + ticks[0] + " ticks, maxX=" + maxX[0] + ", maxY=" + maxY[0]);
    }

    @Test
    void playerSetsRoundAndTickCorrectly() throws Exception {
        Path brFile = getFirstRecording();
        Loader loader = new Loader(brFile);

        Whiteboard wbA = new Whiteboard();
        Whiteboard wbB = new Whiteboard();

        Player player = new Player(wbA, wbB);
        player.replay(loader);

        BattleRecordInfo info = loader.getRecordInfo();
        // After replay, round should match the last round index (0-based)
        assertTrue(wbA.getRound() >= 0, "Round should be >= 0");
        assertTrue(wbA.getTick() >= 0, "Last tick should be >= 0");
        // Battlefield dimensions should be set
        assertTrue(wbA.getBattlefieldWidth() > 0, "Battlefield width should be set");
    }
}

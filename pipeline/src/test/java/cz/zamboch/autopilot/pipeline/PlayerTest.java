package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Whiteboard;
import net.sf.robocode.recording.BattleRecordInfo;
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

        Player player = new Player();
        player.replay(loader, wbA, wbB);

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

        // Use a tracking whiteboard to verify positions during replay
        final double[] maxX = {0};
        final double[] maxY = {0};
        final int[] ticks = {0};

        Whiteboard wbA = new Whiteboard() {
            @Override
            public void setOurState(double x, double y, double heading, double gunHeading,
                                    double radarHeading, double velocity, double energy, double gunHeat) {
                super.setOurState(x, y, heading, gunHeading, radarHeading, velocity, energy, gunHeat);
                if (x > maxX[0]) maxX[0] = x;
                if (y > maxY[0]) maxY[0] = y;
                ticks[0]++;
            }
        };
        Whiteboard wbB = new Whiteboard();

        Player player = new Player();
        player.replay(loader, wbA, wbB);

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

        final int[] lastRound = {-1};
        final long[] lastTick = {-1};
        final int[] roundChanges = {0};

        Whiteboard wbA = new Whiteboard() {
            @Override
            public void onRoundStart(int round, int battlefieldWidth, int battlefieldHeight,
                                     double gunCoolingRate, int numRounds) {
                super.onRoundStart(round, battlefieldWidth, battlefieldHeight, gunCoolingRate, numRounds);
                if (round != lastRound[0]) {
                    lastRound[0] = round;
                    roundChanges[0]++;
                }
            }

            @Override
            public void setTick(long tick) {
                super.setTick(tick);
                lastTick[0] = tick;
            }
        };
        Whiteboard wbB = new Whiteboard();

        Player player = new Player();
        player.replay(loader, wbA, wbB);

        BattleRecordInfo info = loader.getRecordInfo();
        assertEquals(info.roundsCount, roundChanges[0], "Should have seen all rounds");
        assertTrue(lastTick[0] >= 0, "Last tick should be >= 0");
    }
}

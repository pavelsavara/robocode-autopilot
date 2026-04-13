package cz.zamboch.autopilot.pipeline;

import net.sf.robocode.recording.BattleRecordInfo;
import robocode.control.snapshot.ITurnSnapshot;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.file.Path;
import java.util.zip.ZipInputStream;

/**
 * Reads .br recording files (BINARY_ZIP format).
 * Opens ZIP entry, deserializes BattleRecordInfo header + TurnSnapshot objects
 * via ObjectInputStream. Direct reading, no temp files.
 */
public class Loader implements Closeable {
    private final Path brFile;
    private BattleRecordInfo recordInfo;

    public Loader(Path brFile) {
        this.brFile = brFile;
    }

    public Path getPath() {
        return brFile;
    }

    public BattleRecordInfo getRecordInfo() {
        return recordInfo;
    }

    /**
     * Reads the .br file and invokes the consumer for each turn snapshot.
     * The consumer receives (roundIndex, turnSnapshot).
     * This method opens and closes all streams internally.
     */
    public void forEachTurn(TurnConsumer consumer) throws IOException, ClassNotFoundException {
        FileInputStream fis = null;
        BufferedInputStream bis = null;
        ZipInputStream zis = null;
        ObjectInputStream ois = null;

        try {
            fis = new FileInputStream(brFile.toFile());
            bis = new BufferedInputStream(fis, 1024 * 1024);
            zis = new ZipInputStream(bis);
            zis.getNextEntry();
            ois = new ObjectInputStream(zis);

            recordInfo = (BattleRecordInfo) ois.readObject();

            if (recordInfo.turnsInRounds != null) {
                for (int roundIdx = 0; roundIdx < recordInfo.turnsInRounds.length; roundIdx++) {
                    int turnsInRound = recordInfo.turnsInRounds[roundIdx];
                    for (int t = 0; t < turnsInRound; t++) {
                        ITurnSnapshot turn = (ITurnSnapshot) ois.readObject();
                        consumer.accept(roundIdx, turn);
                    }
                }
            }
        } finally {
            closeQuietly(ois);
            closeQuietly(zis);
            closeQuietly(bis);
            closeQuietly(fis);
        }
    }

    public void close() throws IOException {
        // All resources are opened/closed within forEachTurn
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    @FunctionalInterface
    public interface TurnConsumer {
        void accept(int roundIndex, ITurnSnapshot turn);
    }
}

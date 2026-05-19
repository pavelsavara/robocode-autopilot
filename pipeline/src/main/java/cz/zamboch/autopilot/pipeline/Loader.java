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
 * Reads Robocode .br recording files (BINARY_ZIP format).
 * Iterates turn snapshots via callback.
 */
public final class Loader implements Closeable {
    private final Path brFile;
    private BattleRecordInfo recordInfo;
    private ZipInputStream zis;
    private ObjectInputStream ois;

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
     * Iterate all turns in the recording. Calls consumer for each
     * (roundIndex, turnSnapshot) pair.
     */
    public void forEachTurn(TurnConsumer consumer) throws IOException, ClassNotFoundException {
        FileInputStream fis = new FileInputStream(brFile.toFile());
        BufferedInputStream bis = new BufferedInputStream(fis, 1024 * 1024);
        zis = new ZipInputStream(bis);
        zis.getNextEntry();
        ois = new ObjectInputStream(zis);

        recordInfo = (BattleRecordInfo) ois.readObject();

        for (int roundIdx = 0; roundIdx < recordInfo.turnsInRounds.length; roundIdx++) {
            for (int t = 0; t < recordInfo.turnsInRounds[roundIdx]; t++) {
                ITurnSnapshot turn = (ITurnSnapshot) ois.readObject();
                consumer.accept(roundIdx, turn);
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (ois != null) {
            ois.close();
        }
    }

    @FunctionalInterface
    public interface TurnConsumer {
        void accept(int roundIndex, ITurnSnapshot turn);
    }
}

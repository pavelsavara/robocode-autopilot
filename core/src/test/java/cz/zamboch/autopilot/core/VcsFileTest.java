package cz.zamboch.autopilot.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

final class VcsFileTest {

    @TempDir
    File tempDir;

    @Test
    void loadFromNonExistentFileReturnsEmptyStore() {
        File f = new File(tempDir, "nonexistent.dat");
        VcsStore store = VcsFile.loadForOpponent(f, 12345);
        assertNotNull(store);
        assertEquals(GuessFactor.ZERO_BIN, store.getBestBin(0, 0));
    }

    @Test
    void saveAndLoadSingleOpponent() throws IOException {
        File f = new File(tempDir, "vcs.dat");
        int hash = RoboMath.fnv1a32("TestBot");

        VcsStore store = new VcsStore();
        store.increment(0, 0, 20);
        store.increment(0, 0, 20);
        VcsFile.saveForOpponent(f, hash, store);

        VcsStore loaded = VcsFile.loadForOpponent(f, hash);
        assertEquals(2, loaded.getCount(0, 0, 20));
        assertEquals(20, loaded.getBestBin(0, 0));
    }

    @Test
    void multipleOpponentsCoexist() throws IOException {
        File f = new File(tempDir, "vcs.dat");
        int hash1 = RoboMath.fnv1a32("Bot1");
        int hash2 = RoboMath.fnv1a32("Bot2");

        VcsStore store1 = new VcsStore();
        store1.increment(0, 0, 5);
        VcsFile.saveForOpponent(f, hash1, store1);

        VcsStore store2 = new VcsStore();
        store2.increment(1, 1, 25);
        VcsFile.saveForOpponent(f, hash2, store2);

        // Both survive
        VcsStore loaded1 = VcsFile.loadForOpponent(f, hash1);
        assertEquals(1, loaded1.getCount(0, 0, 5));

        VcsStore loaded2 = VcsFile.loadForOpponent(f, hash2);
        assertEquals(1, loaded2.getCount(1, 1, 25));
    }

    @Test
    void upsertExistingOpponent() throws IOException {
        File f = new File(tempDir, "vcs.dat");
        int hash = RoboMath.fnv1a32("Updater");

        VcsStore store = new VcsStore();
        store.increment(0, 0, 10);
        VcsFile.saveForOpponent(f, hash, store);

        // Update
        store.increment(0, 0, 10);
        store.increment(2, 2, 30);
        VcsFile.saveForOpponent(f, hash, store);

        VcsStore loaded = VcsFile.loadForOpponent(f, hash);
        assertEquals(2, loaded.getCount(0, 0, 10));
        assertEquals(1, loaded.getCount(2, 2, 30));
    }

    @Test
    void unknownHashReturnsEmptyStore() throws IOException {
        File f = new File(tempDir, "vcs.dat");
        int hash = RoboMath.fnv1a32("Known");

        VcsStore store = new VcsStore();
        store.increment(0, 0, 5);
        VcsFile.saveForOpponent(f, hash, store);

        int unknownHash = RoboMath.fnv1a32("Unknown");
        VcsStore loaded = VcsFile.loadForOpponent(f, unknownHash);
        assertEquals(GuessFactor.ZERO_BIN, loaded.getBestBin(0, 0));
    }

    @Test
    void evictsOldestWhenOverSizeLimit() throws IOException {
        File f = new File(tempDir, "vcs.dat");
        // Each entry = 8 (header) + serializedSize bytes
        int entrySize = 8 + VcsStore.serializedSize();
        int maxEntries = VcsFile.MAX_FILE_SIZE / entrySize;

        // Write enough opponents to exceed limit
        for (int i = 0; i < maxEntries + 5; i++) {
            VcsStore s = new VcsStore();
            s.increment(0, 0, i % GuessFactor.NUM_BINS);
            VcsFile.saveForOpponent(f, i, s);
        }

        // File should not exceed limit
        assertTrue(f.length() <= VcsFile.MAX_FILE_SIZE,
                "File size " + f.length() + " exceeds limit " + VcsFile.MAX_FILE_SIZE);

        // Most recent opponent should still be loadable
        int lastHash = maxEntries + 4;
        VcsStore last = VcsFile.loadForOpponent(f, lastHash);
        assertEquals(1, last.getCount(0, 0, lastHash % GuessFactor.NUM_BINS));

        // First opponent should have been evicted
        VcsStore first = VcsFile.loadForOpponent(f, 0);
        assertEquals(GuessFactor.ZERO_BIN, first.getBestBin(0, 0));
    }
}

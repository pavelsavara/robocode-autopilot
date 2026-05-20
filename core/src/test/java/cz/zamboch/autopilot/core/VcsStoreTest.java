package cz.zamboch.autopilot.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

final class VcsStoreTest {

    @Test
    void incrementAndGetBestBin() {
        VcsStore store = new VcsStore();
        // Initially all zero → returns ZERO_BIN
        assertEquals(GuessFactor.ZERO_BIN, store.getBestBin(0, 0));

        // Increment a bin
        store.increment(0, 0, 20);
        store.increment(0, 0, 20);
        store.increment(0, 0, 15);

        // Bin 20 has 2 hits, bin 15 has 1
        assertEquals(20, store.getBestBin(0, 0));
        assertEquals(2, store.getCount(0, 0, 20));
        assertEquals(1, store.getCount(0, 0, 15));
    }

    @Test
    void differentSegmentsAreIndependent() {
        VcsStore store = new VcsStore();
        store.increment(0, 0, 5);
        store.increment(1, 0, 10);
        store.increment(0, 1, 25);

        assertEquals(5, store.getBestBin(0, 0));
        assertEquals(10, store.getBestBin(1, 0));
        assertEquals(25, store.getBestBin(0, 1));
        // Untouched segment → ZERO_BIN
        assertEquals(GuessFactor.ZERO_BIN, store.getBestBin(2, 2));
    }

    @Test
    void clear() {
        VcsStore store = new VcsStore();
        store.increment(2, 3, 10);
        store.clear();
        assertEquals(0, store.getCount(2, 3, 10));
        assertEquals(GuessFactor.ZERO_BIN, store.getBestBin(2, 3));
    }

    @Test
    void saveLoadRoundTrip() throws IOException {
        VcsStore store = new VcsStore();
        store.increment(1, 2, 7);
        store.increment(1, 2, 7);
        store.increment(4, 4, 30);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        store.save(new DataOutputStream(baos));

        VcsStore loaded = new VcsStore();
        loaded.load(new DataInputStream(new ByteArrayInputStream(baos.toByteArray())));

        assertEquals(2, loaded.getCount(1, 2, 7));
        assertEquals(1, loaded.getCount(4, 4, 30));
        assertEquals(7, loaded.getBestBin(1, 2));
    }

    @Test
    void serializedSizeIsCorrect() {
        assertEquals(5 * 5 * 31 * 4, VcsStore.serializedSize());
    }
}

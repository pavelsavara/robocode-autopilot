package cz.zamboch.autopilot.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

final class VcsStoreTest {

    // Representative lag-1 developing-GF values that the VcsStore bins into the
    // three lag-1 slices: < -0.15 → slice 0, |gf| ≤ 0.15 → slice 1 (center),
    // > 0.15 → slice 2.
    private static final double LAG1_LEFT = -1.0;
    private static final double LAG1_CENTER = 0.0;
    private static final double LAG1_RIGHT = 1.0;

    @Test
    void incrementAndGetBestBin() {
        VcsStore store = new VcsStore();
        // Initially all zero → returns ZERO_BIN
        assertEquals(GuessFactor.ZERO_BIN, store.getBestBin(0, 0, LAG1_CENTER));

        // Increment a bin
        store.increment(0, 0, LAG1_CENTER, 20);
        store.increment(0, 0, LAG1_CENTER, 20);
        store.increment(0, 0, LAG1_CENTER, 15);

        // Bin 20 has 2 hits, bin 15 has 1
        assertEquals(20, store.getBestBin(0, 0, LAG1_CENTER));
        assertEquals(2, store.getCount(0, 0, LAG1_CENTER, 20));
        assertEquals(1, store.getCount(0, 0, LAG1_CENTER, 15));
    }

    @Test
    void differentSegmentsAreIndependent() {
        VcsStore store = new VcsStore();
        store.increment(0, 0, LAG1_CENTER, 5);
        store.increment(1, 0, LAG1_CENTER, 10);
        store.increment(0, 1, LAG1_CENTER, 25);

        assertEquals(5, store.getBestBin(0, 0, LAG1_CENTER));
        assertEquals(10, store.getBestBin(1, 0, LAG1_CENTER));
        assertEquals(25, store.getBestBin(0, 1, LAG1_CENTER));
        // Untouched segment → ZERO_BIN
        assertEquals(GuessFactor.ZERO_BIN, store.getBestBin(2, 2, LAG1_CENTER));
    }

    @Test
    void lag1SlicesAreIndependent() {
        VcsStore store = new VcsStore();
        store.increment(0, 0, LAG1_LEFT, 5);
        store.increment(0, 0, LAG1_RIGHT, 25);
        assertEquals(5, store.getBestBin(0, 0, LAG1_LEFT));
        assertEquals(25, store.getBestBin(0, 0, LAG1_RIGHT));
        assertEquals(1, store.getCount(0, 0, LAG1_LEFT, 5));
        assertEquals(0, store.getCount(0, 0, LAG1_RIGHT, 5));
    }

    @Test
    void emptyLag1SliceFallsBackToAggregate() {
        VcsStore store = new VcsStore();
        // Populate only the negative and positive lag-1 slices.
        store.increment(0, 0, LAG1_LEFT, 7);
        store.increment(0, 0, LAG1_LEFT, 7);
        store.increment(0, 0, LAG1_RIGHT, 7);
        // The center slice is empty → aggregate over slices picks bin 7.
        assertEquals(7, store.getBestBin(0, 0, LAG1_CENTER));
    }

    @Test
    void clear() {
        VcsStore store = new VcsStore();
        store.increment(2, 3, LAG1_CENTER, 10);
        store.clear();
        assertEquals(0, store.getCount(2, 3, LAG1_CENTER, 10));
        assertEquals(GuessFactor.ZERO_BIN, store.getBestBin(2, 3, LAG1_CENTER));
    }

    @Test
    void saveLoadRoundTrip() throws IOException {
        VcsStore store = new VcsStore();
        store.increment(1, 2, LAG1_CENTER, 7);
        store.increment(1, 2, LAG1_CENTER, 7);
        store.increment(4, 4, LAG1_RIGHT, 30);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        store.save(new DataOutputStream(baos));

        VcsStore loaded = new VcsStore();
        loaded.load(new DataInputStream(new ByteArrayInputStream(baos.toByteArray())));

        assertEquals(2, loaded.getCount(1, 2, LAG1_CENTER, 7));
        assertEquals(1, loaded.getCount(4, 4, LAG1_RIGHT, 30));
        assertEquals(7, loaded.getBestBin(1, 2, LAG1_CENTER));
    }

    @Test
    void serializedSizeIsCorrect() {
        assertEquals(5 * 5 * 3 * 31 * 4, VcsStore.serializedSize());
    }
}

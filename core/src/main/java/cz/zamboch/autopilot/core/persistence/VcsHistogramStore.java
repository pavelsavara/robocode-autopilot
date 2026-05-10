package cz.zamboch.autopilot.core.persistence;

import cz.zamboch.autopilot.core.Whiteboard;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists VCS (Visit Count Statistics) histograms per opponent across battles.
 * Keyed by opponent bot ID hash (FNV-1a of the name before the first space).
 *
 * <p>On battle start against a known opponent, the stored histograms are
 * copied into Whiteboard's gun/move VCS arrays, giving the robot a
 * warm-start instead of learning from scratch in every battle.</p>
 *
 * <p>Uses LRU eviction: keeps up to {@link #MAX_ENTRIES} opponents.
 * Oldest entry is evicted when the store is full.</p>
 *
 * <p>Section data per entry: botIdHash (4) + gunVcs (12×61×4=2928) +
 * moveVcs (12×61×4=2928) = 5860 bytes. At MAX_ENTRIES=30 ≈ 176 KB.</p>
 */
public final class VcsHistogramStore implements IPersistable {

    public static final int SECTION_ID = 4;
    private static final int MAX_ENTRIES = 30;

    private static final int SEGMENTS = Whiteboard.VCS_SEGMENTS;
    private static final int BINS = Whiteboard.VCS_BINS;

    /** LRU map: botIdHash → {gunVcs, moveVcs}. Access order for LRU. */
    private final LinkedHashMap<Integer, int[][]> store =
            new LinkedHashMap<Integer, int[][]>(MAX_ENTRIES + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, int[][]> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    /**
     * Load stored histograms into Whiteboard if we have data for this opponent.
     *
     * @param botIdHash FNV-1a hash of opponent bot ID
     * @param wb        whiteboard to populate
     * @return true if histograms were loaded (known opponent)
     */
    public boolean loadInto(int botIdHash, Whiteboard wb) {
        int[][] data = store.get(botIdHash);
        if (data == null) {
            return false;
        }
        // data[0..SEGMENTS-1] = gunVcs, data[SEGMENTS..2*SEGMENTS-1] = moveVcs
        for (int s = 0; s < SEGMENTS; s++) {
            int[] gunSeg = wb.getGunVcsSegment(s);
            int[] moveSeg = wb.getMoveVcsSegment(s);
            System.arraycopy(data[s], 0, gunSeg, 0, BINS);
            System.arraycopy(data[SEGMENTS + s], 0, moveSeg, 0, BINS);
        }
        return true;
    }

    /**
     * Save current Whiteboard VCS histograms for this opponent.
     *
     * @param botIdHash FNV-1a hash of opponent bot ID
     * @param wb        whiteboard to read from
     */
    public void saveFrom(int botIdHash, Whiteboard wb) {
        int[][] data = new int[SEGMENTS * 2][BINS];
        for (int s = 0; s < SEGMENTS; s++) {
            System.arraycopy(wb.getGunVcsSegment(s), 0, data[s], 0, BINS);
            System.arraycopy(wb.getMoveVcsSegment(s), 0, data[SEGMENTS + s], 0, BINS);
        }
        store.put(botIdHash, data);
    }

    /** Number of opponents stored. */
    public int size() {
        return store.size();
    }

    // === IPersistable ===

    @Override
    public int getSectionId() { return SECTION_ID; }

    @Override
    public void writeTo(DataOutputStream out) throws IOException {
        out.writeInt(store.size());
        for (Map.Entry<Integer, int[][]> entry : store.entrySet()) {
            out.writeInt(entry.getKey()); // botIdHash
            int[][] data = entry.getValue();
            for (int s = 0; s < SEGMENTS * 2; s++) {
                for (int b = 0; b < BINS; b++) {
                    out.writeShort(Math.max(-32768, Math.min(32767, data[s][b])));
                }
            }
        }
    }

    @Override
    public void readFrom(DataInputStream in, int length) throws IOException {
        int count = in.readInt();
        store.clear();
        for (int i = 0; i < count; i++) {
            int hash = in.readInt();
            int[][] data = new int[SEGMENTS * 2][BINS];
            for (int s = 0; s < SEGMENTS * 2; s++) {
                for (int b = 0; b < BINS; b++) {
                    data[s][b] = in.readShort();
                }
            }
            store.put(hash, data);
        }
    }
}

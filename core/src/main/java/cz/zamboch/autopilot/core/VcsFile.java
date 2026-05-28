package cz.zamboch.autopilot.core;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Multi-opponent VCS persistence in a single binary file.
 * Format: repeated [int opponentHash][int dataLength][byte[] vcsData]
 * Keyed by FNV-1a hash of opponent name.
 * <p>
 * When the file would exceed {@link #MAX_FILE_SIZE} bytes, the oldest
 * entries (front of file) are evicted to make room.
 */
public final class VcsFile {

    /** Maximum file size in bytes (200 KB). */
    static final int MAX_FILE_SIZE = 200 * 1024;

    /** Per-entry header overhead: hash (4 bytes) + length (4 bytes). */
    private static final int ENTRY_HEADER = 8;

    private VcsFile() {
    }

    /**
     * Load VcsStore for a specific opponent from file. Returns a new empty
     * VcsStore if file doesn't exist or opponent not found.
     */
    public static VcsStore loadForOpponent(File dataFile, int opponentHash) {
        VcsStore store = new VcsStore();
        if (dataFile == null || !dataFile.exists() || dataFile.length() == 0) {
            return store;
        }
        try (DataInputStream in = new DataInputStream(new FileInputStream(dataFile))) {
            while (in.available() > 0) {
                int hash = in.readInt();
                int length = in.readInt();
                if (hash == opponentHash && length == VcsStore.serializedSize()) {
                    store.load(in);
                    return store;
                } else {
                    in.skipBytes(length);
                }
            }
        } catch (IOException e) {
            // Corrupted file — return empty store
        }
        return store;
    }

    /**
     * Save/upsert VcsStore for a specific opponent into file.
     * Rewrites the entire file to update or append the entry.
     */
    public static void saveForOpponent(File dataFile, int opponentHash, VcsStore store) throws IOException {
        // Read all existing entries except the one we're updating
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream bufOut = new DataOutputStream(buffer);

        if (dataFile.exists() && dataFile.length() > 0) {
            try (DataInputStream in = new DataInputStream(new FileInputStream(dataFile))) {
                while (in.available() > 0) {
                    int hash = in.readInt();
                    int length = in.readInt();
                    byte[] data = new byte[length];
                    in.readFully(data);
                    if (hash != opponentHash) {
                        bufOut.writeInt(hash);
                        bufOut.writeInt(length);
                        bufOut.write(data);
                    }
                }
            } catch (IOException e) {
                // Corrupted — start fresh with just our entry
                buffer.reset();
            }
        }

        // Append updated entry
        ByteArrayOutputStream entryBuf = new ByteArrayOutputStream();
        DataOutputStream entryOut = new DataOutputStream(entryBuf);
        store.save(entryOut);
        entryOut.flush();
        byte[] entryData = entryBuf.toByteArray();

        bufOut.writeInt(opponentHash);
        bufOut.writeInt(entryData.length);
        bufOut.write(entryData);
        bufOut.flush();

        // Evict oldest entries (from front) if over size limit
        byte[] allBytes = buffer.toByteArray();
        allBytes = evict(allBytes);

        // Write atomically
        try (FileOutputStream fos = new FileOutputStream(dataFile)) {
            fos.write(allBytes);
        }
    }

    /**
     * Drops entries from the front (oldest/least-recently-updated) until
     * the total size is within {@link #MAX_FILE_SIZE}.
     */
    static byte[] evict(byte[] data) {
        if (data.length <= MAX_FILE_SIZE) {
            return data;
        }
        // Walk entries to find boundary sizes
        int offset = 0;
        while (offset < data.length && data.length - offset > MAX_FILE_SIZE) {
            if (offset + ENTRY_HEADER > data.length)
                break;
            int length = ((data[offset + 4] & 0xFF) << 24)
                    | ((data[offset + 5] & 0xFF) << 16)
                    | ((data[offset + 6] & 0xFF) << 8)
                    | (data[offset + 7] & 0xFF);
            int entrySize = ENTRY_HEADER + length;
            if (offset + entrySize > data.length)
                break;
            offset += entrySize;
        }
        if (offset == 0) {
            return data;
        }
        byte[] trimmed = new byte[data.length - offset];
        System.arraycopy(data, offset, trimmed, 0, trimmed.length);
        return trimmed;
    }
}

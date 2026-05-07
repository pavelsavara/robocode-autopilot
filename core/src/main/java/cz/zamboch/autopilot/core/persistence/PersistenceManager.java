package cz.zamboch.autopilot.core.persistence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages cross-battle state persistence via a binary data file.
 *
 * <p>File format:</p>
 * <pre>
 * [MAGIC:4][VERSION:4][SECTION_COUNT:4]
 * For each section:
 *   [SECTION_ID:4][DATA_LENGTH:4][DATA:length]
 * </pre>
 *
 * <p>Subsystems register via {@link #register} and implement {@link IPersistable}.
 * The file is shared across all opponents. Version constant auto-invalidates
 * stale data on schema change.</p>
 */
public final class PersistenceManager {

    /** Magic bytes: "RBAP" (Robocode Autopilot). */
    private static final int MAGIC = 0x52424150;

    /** Bump this when the serialisation schema changes. */
    public static final int VERSION = 1;

    private final List<IPersistable> sections = new ArrayList<IPersistable>();

    /** Register a subsystem for cross-battle persistence. */
    public void register(IPersistable section) {
        sections.add(section);
    }

    /**
     * Save all registered sections to a byte array.
     * Caller writes the result to the robot's data file.
     */
    public byte[] save() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
            DataOutputStream out = new DataOutputStream(baos);
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(sections.size());

            for (IPersistable section : sections) {
                ByteArrayOutputStream sectionBuf = new ByteArrayOutputStream(1024);
                DataOutputStream sectionOut = new DataOutputStream(sectionBuf);
                section.writeTo(sectionOut);
                sectionOut.flush();
                byte[] data = sectionBuf.toByteArray();

                out.writeInt(section.getSectionId());
                out.writeInt(data.length);
                out.write(data);
            }

            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            // ByteArrayOutputStream never throws — shouldn't happen
            return new byte[0];
        }
    }

    /**
     * Load sections from a byte array. Sections with unknown IDs are skipped.
     * Returns false if the data is corrupt, wrong version, or empty.
     */
    public boolean load(byte[] data) {
        if (data == null || data.length < 12) {
            return false;
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            int magic = in.readInt();
            if (magic != MAGIC) return false;

            int version = in.readInt();
            if (version != VERSION) return false;

            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int sectionId = in.readInt();
                int length = in.readInt();

                if (length < 0 || length > data.length) return false;

                byte[] sectionData = new byte[length];
                int read = in.read(sectionData, 0, length);
                if (read != length) return false;

                // Find matching section handler
                for (IPersistable section : sections) {
                    if (section.getSectionId() == sectionId) {
                        DataInputStream sectionIn = new DataInputStream(
                                new ByteArrayInputStream(sectionData));
                        section.readFrom(sectionIn, length);
                        break;
                    }
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Convenience: save to a file. */
    public void saveToFile(File file) {
        byte[] data = save();
        try {
            FileOutputStream fos = new FileOutputStream(file);
            try {
                fos.write(data);
            } finally {
                fos.close();
            }
        } catch (IOException e) {
            // Silently fail — persistence is best-effort
        }
    }

    /** Convenience: load from a file. */
    public boolean loadFromFile(File file) {
        if (file == null || !file.exists() || file.length() == 0) {
            return false;
        }
        try {
            byte[] data = new byte[(int) file.length()];
            FileInputStream fis = new FileInputStream(file);
            try {
                int offset = 0;
                while (offset < data.length) {
                    int read = fis.read(data, offset, data.length - offset);
                    if (read < 0) break;
                    offset += read;
                }
            } finally {
                fis.close();
            }
            return load(data);
        } catch (IOException e) {
            return false;
        }
    }
}

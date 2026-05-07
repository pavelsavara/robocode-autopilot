package cz.zamboch.autopilot.core.persistence;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Interface for subsystems that persist state across battles
 * via the robot's data file (200KB limit).
 */
public interface IPersistable {

    /** Unique section ID for this subsystem's data block. */
    int getSectionId();

    /** Write this subsystem's state to the output stream. */
    void writeTo(DataOutputStream out) throws IOException;

    /** Read this subsystem's state from the input stream. */
    void readFrom(DataInputStream in, int length) throws IOException;
}

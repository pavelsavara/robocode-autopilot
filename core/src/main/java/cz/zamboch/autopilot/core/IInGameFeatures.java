package cz.zamboch.autopilot.core;

/**
 * Feature computation interface. Implementations must be stateless —
 * all inter-tick state lives in the Whiteboard.
 */
public interface IInGameFeatures {
    /** Features this class reads from the Whiteboard. */
    Feature[] getDependencies();

    /** Features this class writes to the Whiteboard. */
    Feature[] getOutputFeatures();

    /** Which CSV file this feature class writes to. */
    FileType getFileType();

    /** Compute features for the current tick. */
    void process(Whiteboard wb);
}

package cz.zamboch.autopilot.core;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Feature processor SPI. Implementations compute feature values from Whiteboard state
 * and know how to write their own CSV columns.
 */
public interface IFeatureProcessor {

    /** Which CSV file this processor contributes to. */
    FileType getFileType();

    /** Feature enum values this processor produces. */
    Feature[] getOutputFeatures();

    /** Feature enum values this processor depends on (must run first). */
    Feature[] getDependencies();

    /** Compute feature value(s) and write to whiteboard. */
    void process(Whiteboard wb);

    /** Write CSV column header(s) for this processor's features. */
    void writeColumnNames(OutputStream out) throws IOException;

    /** Write CSV column value(s) for the current tick/wave/round. */
    void writeRowValues(OutputStream out, Whiteboard wb) throws IOException;
}

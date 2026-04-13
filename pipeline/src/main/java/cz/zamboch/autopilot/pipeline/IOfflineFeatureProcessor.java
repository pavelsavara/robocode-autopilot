package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Offline feature processor interface. Extends in-game features with CSV writing
 * and file-type classification. Used only in the pipeline — not shipped with the robot.
 */
public interface IOfflineFeatureProcessor extends IInGameFeatures {

    /** Which CSV file this processor contributes to. */
    FileType getFileType();

    /** Write CSV column header(s) for this processor's features. */
    void writeColumnNames(OutputStream out) throws IOException;

    /** Write CSV column value(s) for the current tick/wave/round. */
    void writeRowValues(OutputStream out, Whiteboard wb) throws IOException;
}

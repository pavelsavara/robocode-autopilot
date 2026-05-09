package cz.zamboch.distilled;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.ml.FeatureMapping;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;

/**
 * Diagnostic feature logger for comparing Java feature vectors with Python.
 *
 * <p>When enabled via system property {@code autopilot.featureLog=true},
 * writes one CSV row per tick with feature values, predicted value, and
 * actual value for each model. Output goes to Robocode's data directory
 * via {@code getDataFile()}.</p>
 *
 * <p>When disabled (the default), the only cost is a single boolean check
 * per tick — zero allocation, zero I/O.</p>
 */
public final class FeatureLogger {

    /** System property key. Set -Dautopilot.featureLog=true to enable. */
    private static final String PROP_KEY = "autopilot.featureLog";

    /** Checked once at class load. Immutable after that. */
    private static final boolean ENABLED;
    static {
        boolean flag = false;
        try {
            flag = "true".equalsIgnoreCase(System.getProperty(PROP_KEY));
        } catch (SecurityException e) {
            // Robocode sandbox may block system property reads — stay disabled
        }
        ENABLED = flag;
    }

    /** @return true if logging is active (zero-cost check when false). */
    public static boolean isEnabled() { return ENABLED; }

    private PrintStream out;
    private boolean headerWritten;
    private final String modelName;
    private final String[] featureNames;

    /**
     * @param modelName short identifier for the CSV file (e.g. "fire_power")
     * @param featureNames ordered feature names matching the model input
     */
    public FeatureLogger(String modelName, String[] featureNames) {
        this.modelName = modelName;
        this.featureNames = featureNames;
    }

    /**
     * Open the output file. Call once after robot has access to getDataFile().
     *
     * @param dataDir the directory from {@code robot.getDataDirectory()}
     */
    public void open(File dataDir) {
        if (!ENABLED) return;
        try {
            File f = new File(dataDir, "features_" + modelName + ".csv");
            out = new PrintStream(new FileOutputStream(f, false), true);
        } catch (Exception e) {
            // If file creation fails, silently disable
            out = null;
        }
    }

    /**
     * Log one tick's feature vector plus prediction and actual.
     *
     * @param round      current round number
     * @param tick       current tick number
     * @param wb         whiteboard (for extracting feature values)
     * @param featureIdx Feature[] index built by FeatureMapping.buildIndex
     * @param inputBuf   the pre-filled input buffer (after FeatureMapping.extract)
     * @param predicted  model prediction output
     * @param actual     actual value (NaN if not available this tick)
     */
    public void log(int round, long tick, Whiteboard wb,
                    Feature[] featureIdx, double[] inputBuf,
                    double predicted, double actual) {
        if (out == null) return;

        if (!headerWritten) {
            writeHeader();
            headerWritten = true;
        }

        StringBuilder sb = new StringBuilder(featureNames.length * 12);
        sb.append(round).append(',').append(tick);
        for (int i = 0; i < inputBuf.length; i++) {
            sb.append(',');
            double v = inputBuf[i];
            if (Double.isNaN(v)) {
                // leave empty for NaN (matches Python's NaN → empty CSV cell)
            } else {
                sb.append(v);
            }
        }
        sb.append(',').append(predicted);
        sb.append(',');
        if (!Double.isNaN(actual)) {
            sb.append(actual);
        }
        out.println(sb.toString());
    }

    private void writeHeader() {
        StringBuilder sb = new StringBuilder(featureNames.length * 20);
        sb.append("round,tick");
        for (String name : featureNames) {
            sb.append(',').append(name);
        }
        sb.append(",predicted,actual");
        out.println(sb.toString());
    }

    /** Flush and close the output stream. Call at battle end. */
    public void close() {
        if (out != null) {
            out.flush();
            out.close();
            out = null;
        }
    }
}

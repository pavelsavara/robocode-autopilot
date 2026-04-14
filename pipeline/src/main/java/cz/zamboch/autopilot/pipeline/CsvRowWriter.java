package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Wraps an OutputStream for CSV row writing. Handles comma separation,
 * NaN for missing features, and type-specific formatting.
 */
public final class CsvRowWriter {
    private final OutputStream out;
    private boolean needsComma;

    public CsvRowWriter(OutputStream out) {
        this.out = out;
    }

    /** Start a new row (reset comma state). */
    public void beginRow() {
        needsComma = false;
    }

    /** Write a double feature with format string (e.g. "%.3f"). */
    public void writeDouble(Whiteboard wb, Feature f, String fmt) {
        sep();
        if (wb.hasFeature(f)) {
            raw(String.format(fmt, wb.getFeature(f)));
        } else {
            raw("NaN");
        }
    }

    /** Write a boolean feature (0/1). */
    public void writeBoolean(Whiteboard wb, Feature f) {
        sep();
        if (wb.hasFeature(f)) {
            raw(wb.getFeature(f) > 0.5 ? "1" : "0");
        } else {
            raw("NaN");
        }
    }

    /** Write an integer feature. */
    public void writeInt(Whiteboard wb, Feature f) {
        sep();
        if (wb.hasFeature(f)) {
            raw(Integer.toString((int) wb.getFeature(f)));
        } else {
            raw("NaN");
        }
    }

    /** Write a raw string value (no feature lookup). */
    public void writeRaw(String value) {
        sep();
        raw(value);
    }

    /** Write column headers. */
    public void writeHeaders(String... names) {
        for (String name : names) {
            sep();
            raw(name);
        }
    }

    /** End the current row with a newline. */
    public void endRow() {
        raw("\n");
        needsComma = false;
    }

    /** Flush pending content to the underlying stream. */
    public void flush() throws IOException {
        out.flush();
    }

    private void sep() {
        if (needsComma) {
            raw(",");
        }
        needsComma = true;
    }

    private void raw(String s) {
        try {
            out.write(s.getBytes());
        } catch (IOException e) {
            throw new UncheckedCsvException(e);
        }
    }

    public static final class UncheckedCsvException extends RuntimeException {
        public UncheckedCsvException(IOException cause) {
            super(cause);
        }
    }
}

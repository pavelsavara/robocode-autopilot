package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Low-level CSV row writer. Handles comma separation, NaN for missing values,
 * and type formatting. Writes directly to OutputStream as bytes.
 */
public final class CsvRowWriter {
    private final OutputStream out;
    private boolean firstColumn;

    public CsvRowWriter(OutputStream out) {
        this.out = new BufferedOutputStream(out, 64 * 1024);
        this.firstColumn = true;
    }

    public void beginRow() {
        firstColumn = true;
    }

    public void endRow() throws IOException {
        out.write('\n');
    }

    public void writeHeader(String name) throws IOException {
        if (!firstColumn) {
            out.write(',');
        }
        firstColumn = false;
        out.write(name.getBytes(StandardCharsets.UTF_8));
    }

    public void writeHeaders(String... names) throws IOException {
        for (String name : names) {
            writeHeader(name);
        }
    }

    public void writeDouble(Whiteboard wb, Feature f) throws IOException {
        if (!firstColumn) {
            out.write(',');
        }
        firstColumn = false;
        double val = wb.getFeature(f);
        if (Double.isNaN(val)) {
            out.write("NaN".getBytes(StandardCharsets.UTF_8));
        } else {
            out.write(Double.toString(val).getBytes(StandardCharsets.UTF_8));
        }
    }

    public void writeRaw(String value) throws IOException {
        if (!firstColumn) {
            out.write(',');
        }
        firstColumn = false;
        out.write(value.getBytes(StandardCharsets.UTF_8));
    }

    public void writeInt(int value) throws IOException {
        if (!firstColumn) {
            out.write(',');
        }
        firstColumn = false;
        out.write(Integer.toString(value).getBytes(StandardCharsets.UTF_8));
    }

    public void writeLong(long value) throws IOException {
        if (!firstColumn) {
            out.write(',');
        }
        firstColumn = false;
        out.write(Long.toString(value).getBytes(StandardCharsets.UTF_8));
    }

    public void flush() throws IOException {
        out.flush();
    }

    public void close() throws IOException {
        out.flush();
        out.close();
    }
}

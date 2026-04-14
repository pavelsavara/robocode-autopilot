package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CsvRowWriter: formatting, NaN handling, type methods.
 */
class CsvRowWriterTest {

    private ByteArrayOutputStream baos;
    private CsvRowWriter row;
    private Whiteboard wb;

    @BeforeEach
    void setUp() {
        baos = new ByteArrayOutputStream();
        row = new CsvRowWriter(baos);
        wb = new Whiteboard();
        wb.onRoundStart(0, 800, 600, 0.1, 10);
    }

    @Test
    void writeDoubleFormatsCorrectly() {
        wb.setFeature(Feature.DISTANCE, 123.456789);
        row.beginRow();
        row.writeDouble(wb, Feature.DISTANCE, "%.2f");
        row.endRow();
        assertEquals("123.46\n", baos.toString());
    }

    @Test
    void writeDoubleNaNWhenMissing() {
        row.beginRow();
        row.writeDouble(wb, Feature.DISTANCE, "%.2f");
        row.endRow();
        assertEquals("NaN\n", baos.toString());
    }

    @Test
    void writeBooleanTrue() {
        wb.setFeature(Feature.OPPONENT_FIRED, 1.0);
        row.beginRow();
        row.writeBoolean(wb, Feature.OPPONENT_FIRED);
        row.endRow();
        assertEquals("1\n", baos.toString());
    }

    @Test
    void writeBooleanFalse() {
        wb.setFeature(Feature.OPPONENT_FIRED, 0.0);
        row.beginRow();
        row.writeBoolean(wb, Feature.OPPONENT_FIRED);
        row.endRow();
        assertEquals("0\n", baos.toString());
    }

    @Test
    void writeBooleanNaN() {
        row.beginRow();
        row.writeBoolean(wb, Feature.OPPONENT_FIRED);
        row.endRow();
        assertEquals("NaN\n", baos.toString());
    }

    @Test
    void writeIntCorrectly() {
        wb.setFeature(Feature.OPPONENT_LATERAL_DIRECTION, -1.0);
        row.beginRow();
        row.writeInt(wb, Feature.OPPONENT_LATERAL_DIRECTION);
        row.endRow();
        assertEquals("-1\n", baos.toString());
    }

    @Test
    void writeIntNaN() {
        row.beginRow();
        row.writeInt(wb, Feature.OPPONENT_LATERAL_DIRECTION);
        row.endRow();
        assertEquals("NaN\n", baos.toString());
    }

    @Test
    void commaSeparation() {
        wb.setFeature(Feature.DISTANCE, 100.0);
        wb.setFeature(Feature.OPPONENT_ENERGY, 50.0);
        row.beginRow();
        row.writeDouble(wb, Feature.DISTANCE, "%.0f");
        row.writeDouble(wb, Feature.OPPONENT_ENERGY, "%.0f");
        row.endRow();
        assertEquals("100,50\n", baos.toString());
    }

    @Test
    void writeRaw() {
        row.beginRow();
        row.writeRaw("hello");
        row.writeRaw("world");
        row.endRow();
        assertEquals("hello,world\n", baos.toString());
    }

    @Test
    void writeHeaders() {
        row.beginRow();
        row.writeHeaders("a", "b", "c");
        row.endRow();
        assertEquals("a,b,c\n", baos.toString());
    }

    @Test
    void flushDelegatesToStream() throws IOException {
        row.beginRow();
        row.writeRaw("test");
        row.endRow();
        row.flush();
        assertTrue(baos.size() > 0);
    }

    @Test
    void multipleRows() {
        row.beginRow();
        row.writeRaw("row1");
        row.endRow();
        row.beginRow();
        row.writeRaw("row2");
        row.endRow();
        assertEquals("row1\nrow2\n", baos.toString());
    }
}

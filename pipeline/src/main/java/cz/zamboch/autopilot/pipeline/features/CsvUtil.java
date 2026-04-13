package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Shared CSV formatting utilities for offline feature processors.
 */
final class CsvUtil {

    private CsvUtil() {}

    static void appendValue(StringBuilder sb, Whiteboard wb, Feature f, String fmt) {
        if (wb.hasFeature(f)) {
            sb.append(String.format(fmt, wb.getFeature(f)));
        } else {
            sb.append("NaN");
        }
    }

    static void appendBoolean(StringBuilder sb, Whiteboard wb, Feature f) {
        if (wb.hasFeature(f)) {
            sb.append(wb.getFeature(f) > 0.5 ? "1" : "0");
        } else {
            sb.append("NaN");
        }
    }

    static void appendInt(StringBuilder sb, Whiteboard wb, Feature f) {
        if (wb.hasFeature(f)) {
            sb.append((int) wb.getFeature(f));
        } else {
            sb.append("NaN");
        }
    }
}

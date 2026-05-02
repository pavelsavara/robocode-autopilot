package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import cz.zamboch.autopilot.pipeline.IOfflineFeatures;

/**
 * Tier 2 battlefield-geometry features (per-tick, TICKS file).
 *
 * Pure spatial properties of the opponent's position relative to the
 * battlefield center and corners. Stateless; no dependencies on other features
 * (only raw Whiteboard state).
 */
public final class BattlefieldGeometryOfflineFeatures implements IOfflineFeatures {

    private static final double ROBOT_HALF_SIZE = 18.0;

    private static final Feature[] OUTPUTS = {
            Feature.OPPONENT_CENTER_DISTANCE,
            Feature.OPPONENT_CORNER_PROXIMITY
    };

    private static final Feature[] DEPS = {};

    public Feature[] getOutputFeatures() { return OUTPUTS; }
    public Feature[] getDependencies() { return DEPS; }

    public void process(Whiteboard wb) {
        if (!wb.isScanAvailableThisTick()) {
            return;
        }
        double oppX = wb.getOpponentX();
        double oppY = wb.getOpponentY();
        int bfW = wb.getBattlefieldWidth();
        int bfH = wb.getBattlefieldHeight();

        wb.setFeature(Feature.OPPONENT_CENTER_DISTANCE,
                Math.hypot(oppX - bfW / 2.0, oppY - bfH / 2.0));

        double cornerInset = ROBOT_HALF_SIZE;
        double[][] corners = {
                {cornerInset, cornerInset},
                {cornerInset, bfH - cornerInset},
                {bfW - cornerInset, cornerInset},
                {bfW - cornerInset, bfH - cornerInset}
        };
        double minDist = Double.POSITIVE_INFINITY;
        for (double[] c : corners) {
            double d = Math.hypot(oppX - c[0], oppY - c[1]);
            if (d < minDist) minDist = d;
        }
        wb.setFeature(Feature.OPPONENT_CORNER_PROXIMITY, minDist);
    }

    public FileType getFileType() { return FileType.TICKS; }

    public void writeColumnNames(CsvRowWriter row) {
        row.writeHeaders("opponent_center_distance", "opponent_corner_proximity");
    }

    public void writeRowValues(CsvRowWriter row, Whiteboard wb) {
        row.writeDouble(wb, Feature.OPPONENT_CENTER_DISTANCE, "%.3f");
        row.writeDouble(wb, Feature.OPPONENT_CORNER_PROXIMITY, "%.3f");
    }
}

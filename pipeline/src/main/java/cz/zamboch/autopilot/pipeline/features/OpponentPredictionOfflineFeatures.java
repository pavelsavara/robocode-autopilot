package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import cz.zamboch.autopilot.pipeline.IOfflineFeatures;

/**
 * Pipeline-only opponent prediction features.
 * Stateless — fire tracking state lives in Whiteboard (lastOpponentFireTick/Power).
 * Depends on OPPONENT_FIRED and OPPONENT_FIRE_POWER from EnergyFeatures.
 */
public final class OpponentPredictionOfflineFeatures implements IOfflineFeatures {

    private static final Feature[] OUTPUTS = {
            Feature.OPPONENT_WALL_AHEAD_DISTANCE,
            Feature.OPPONENT_INFERRED_GUN_HEAT
    };

    private static final Feature[] DEPS = {
            Feature.OPPONENT_FIRED,
            Feature.OPPONENT_FIRE_POWER
    };

    public Feature[] getOutputFeatures() {
        return OUTPUTS;
    }

    public Feature[] getDependencies() {
        return DEPS;
    }

    public void process(Whiteboard wb) {
        if (!wb.isScanAvailableThisTick()) {
            return;
        }

        // Wall ahead distance: ray-cast from opponent position along opponent heading
        double oppX = wb.getOpponentX();
        double oppY = wb.getOpponentY();
        double oppHeading = wb.getOpponentHeading();
        int bfW = wb.getBattlefieldWidth();
        int bfH = wb.getBattlefieldHeight();

        // Direction vector (robocode heading: 0=north, CW)
        double dx = Math.sin(oppHeading);
        double dy = Math.cos(oppHeading);

        // Distance to each wall along this heading (18px robot offset)
        double distToWall = Double.MAX_VALUE;
        if (dx > 0) {
            distToWall = Math.min(distToWall, (bfW - 18 - oppX) / dx);
        } else if (dx < 0) {
            distToWall = Math.min(distToWall, (18 - oppX) / dx);
        }
        if (dy > 0) {
            distToWall = Math.min(distToWall, (bfH - 18 - oppY) / dy);
        } else if (dy < 0) {
            distToWall = Math.min(distToWall, (18 - oppY) / dy);
        }
        wb.setFeature(Feature.OPPONENT_WALL_AHEAD_DISTANCE,
                distToWall == Double.MAX_VALUE ? 0 : distToWall);

        // Inferred gun heat — reads fire state from Whiteboard (set by EnergyFeatures)
        double coolingRate = wb.getGunCoolingRate();
        long lastFireTick = wb.getLastOpponentFireTick();
        if (lastFireTick >= 0) {
            long elapsed = wb.getTick() - lastFireTick;
            double heatFromFire = 1.0 + wb.getLastOpponentFirePower() / 5.0;
            wb.setFeature(Feature.OPPONENT_INFERRED_GUN_HEAT,
                    Math.max(0, heatFromFire - elapsed * coolingRate));
        } else {
            // No fire detected yet — initial gun heat cools from 3.0 (robocode default)
            wb.setFeature(Feature.OPPONENT_INFERRED_GUN_HEAT,
                    Math.max(0, 3.0 - wb.getTick() * coolingRate));
        }
    }

    public FileType getFileType() {
        return FileType.TICKS;
    }

    public void writeColumnNames(CsvRowWriter row) {
        row.writeHeaders("opponent_wall_ahead_distance", "opponent_inferred_gun_heat");
    }

    public void writeRowValues(CsvRowWriter row, Whiteboard wb) {
        row.writeDouble(wb, Feature.OPPONENT_WALL_AHEAD_DISTANCE, "%.3f");
        row.writeDouble(wb, Feature.OPPONENT_INFERRED_GUN_HEAT, "%.4f");
    }
}

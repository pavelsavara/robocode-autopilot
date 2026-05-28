package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Detects opponent fire via scan-to-scan energy drop, corrected for known
 * energy changes from bullet hits, rams, and wall hits.
 * <p>
 * Correction formula:
 * adjustedDrop = observedDrop - ourBulletDamage - ramDamage - wallDamage +
 * opponentBulletGain
 * <p>
 * If 0.1 ≤ adjustedDrop ≤ 3.0 → opponent fired with that power.
 * Uses PREV_SCAN_OPPONENT_ENERGY stored in the Whiteboard as inter-tick state.
 * Resets accumulator features after consumption.
 */
public final class FireFeatures implements IInGameFeatures {
    private double lastProcessedTick = Double.NaN;

    private static final Feature[] DEPS = {
            Feature.TICK, Feature.LAST_SCAN_TICK, Feature.OPPONENT_ENERGY,
            Feature.OUR_BULLET_DAMAGE_TO_OPPONENT,
            Feature.OPPONENT_BULLET_ENERGY_GAIN,
            Feature.RAM_DAMAGE_TO_OPPONENT,
            Feature.OPPONENT_RAM_ENERGY_GAIN,
            Feature.OPPONENT_WALL_HIT_DAMAGE
    };
    private static final Feature[] OUTPUTS = {
            Feature.THEIR_FIRE_POWER, Feature.PREV_SCAN_OPPONENT_ENERGY
    };

    public Feature[] getDependencies() {
        return DEPS;
    }

    public Feature[] getOutputFeatures() {
        return OUTPUTS;
    }

    public FileType getFileType() {
        return FileType.THEIR_WAVES;
    }

    public void process(Whiteboard wb) {
        double tick = wb.getFeature(Feature.TICK);
        double lastScanTick = wb.getFeature(Feature.LAST_SCAN_TICK);

        // Only compute on ticks where a new scan occurred
        if (Double.isNaN(tick) || Double.isNaN(lastScanTick) || tick != lastScanTick) {
            return;
        }

        // Guard against re-processing when ring didn't advance (e.g. robot dead)
        if (tick == lastProcessedTick) {
            return;
        }
        lastProcessedTick = tick;

        double currentEnergy = wb.getFeature(Feature.OPPONENT_ENERGY);
        double prevEnergy = wb.getPreviousTickFeature(Feature.PREV_SCAN_OPPONENT_ENERGY);

        if (!Double.isNaN(prevEnergy)) {
            double drop = prevEnergy - currentEnergy;

            // Subtract known energy changes
            double bulletDmg = wb.getFeature(Feature.OUR_BULLET_DAMAGE_TO_OPPONENT);
            double bulletGain = wb.getFeature(Feature.OPPONENT_BULLET_ENERGY_GAIN);
            double ramDmg = wb.getFeature(Feature.RAM_DAMAGE_TO_OPPONENT);
            double ramGain = wb.getFeature(Feature.OPPONENT_RAM_ENERGY_GAIN);
            double wallDmg = wb.getFeature(Feature.OPPONENT_WALL_HIT_DAMAGE);
            if (Double.isNaN(bulletDmg))
                bulletDmg = 0;
            if (Double.isNaN(bulletGain))
                bulletGain = 0;
            if (Double.isNaN(ramDmg))
                ramDmg = 0;
            if (Double.isNaN(ramGain))
                ramGain = 0;
            if (Double.isNaN(wallDmg))
                wallDmg = 0;

            double adjustedDrop = drop - bulletDmg - ramDmg - wallDmg + bulletGain + ramGain;

            if (adjustedDrop >= 0.1 && adjustedDrop <= 3.0) {
                wb.setFeature(Feature.THEIR_FIRE_POWER, adjustedDrop);
            } else {
                wb.setFeature(Feature.THEIR_FIRE_POWER, Double.NaN);
            }
        }

        wb.setFeature(Feature.PREV_SCAN_OPPONENT_ENERGY, currentEnergy);
    }
}

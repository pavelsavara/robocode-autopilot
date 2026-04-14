package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Computes energy features: opponent energy, fire detection via energy drop heuristic.
 * No dependencies on other features.
 */
public class EnergyFeatures implements IInGameFeatures {

    private static final Feature[] OUTPUTS = {
            Feature.OPPONENT_ENERGY,
            Feature.OPPONENT_FIRED,
            Feature.OPPONENT_FIRE_POWER
    };

    private static final Feature[] DEPS = {};

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

        // Opponent energy (direct from scan)
        wb.setFeature(Feature.OPPONENT_ENERGY, wb.getOpponentEnergy());

        // Energy drop detection — only on consecutive scans
        double prevEnergy = wb.getPrevOpponentEnergy();
        double currEnergy = wb.getOpponentEnergy();
        long prevScanTick = wb.getPrevScanTick();
        long currentTick = wb.getTick();
        boolean consecutiveScans = prevScanTick >= 0 && (currentTick - prevScanTick) == 1;

        if (prevEnergy > 0 && consecutiveScans) {
            double energyDrop = prevEnergy - currEnergy;

            boolean opponentFired = false;
            double inferredPower = 0;

            if (energyDrop >= 0.1   // Rules.MIN_BULLET_POWER
                    && energyDrop <= 3.0   // Rules.MAX_BULLET_POWER
                    && !wb.isWeHitOpponentThisTick()
                    && !wb.isOpponentHitWallThisTick()) {
                opponentFired = true;
                inferredPower = energyDrop;
            }

            wb.setFeature(Feature.OPPONENT_FIRED, opponentFired ? 1.0 : 0.0);
            wb.setFeature(Feature.OPPONENT_FIRE_POWER, opponentFired ? inferredPower : 0.0);

            if (opponentFired) {
                wb.incrementOpponentShotsDetected();
                wb.setLastOpponentFire(wb.getTick(), inferredPower);
            }
        } else {
            wb.setFeature(Feature.OPPONENT_FIRED, 0.0);
            wb.setFeature(Feature.OPPONENT_FIRE_POWER, 0.0);
        }
    }
}

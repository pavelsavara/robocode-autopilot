package cz.zamboch.trivial;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.StrategyComputer;
import cz.zamboch.autopilot.core.strategy.StrategyParams;

/**
 * Energy-ratio based strategy computer. Replaces TrivialStrategyComputer
 * with combat-aware logic:
 *
 * <ul>
 *   <li>Aggression scales with energy ratio</li>
 *   <li>Kill-shot logic prevents wasting energy on near-dead opponents</li>
 *   <li>Opponent strength rating (offline lookup) biases defensive/aggressive</li>
 *   <li>Random wave selection enabled against strong opponents</li>
 * </ul>
 */
public final class EnergyRatioStrategyComputer extends StrategyComputer {

    /** Stable preferred distance (not random). */
    private static final double PREFERRED_DISTANCE = 350.0;

    @Override
    public StrategyParams compute(Whiteboard wb) {
        double ourEnergy = wb.getOurEnergy();
        double opponentEnergy = wb.getOpponentEnergy();

        // Energy ratio [0,1]: 1.0 = we have all the energy
        double energyRatio = (ourEnergy + opponentEnergy) > 0
                ? ourEnergy / (ourEnergy + opponentEnergy)
                : 0.5;

        // Base aggression from energy ratio
        double aggression;
        if (energyRatio > 0.6) {
            aggression = 0.8;
        } else if (energyRatio > 0.4) {
            aggression = 0.5;
        } else {
            aggression = 0.2;
        }

        // Adjust aggression based on offline opponent strength rating
        boolean randomWaveSelection = false;
        if (wb.hasFeature(Feature.OPPONENT_STRENGTH_RATING)) {
            double strength = wb.getFeature(Feature.OPPONENT_STRENGTH_RATING);
            if (strength > 0.7) {
                // Strong opponent → more defensive, random wave selection
                aggression = Math.max(0.1, aggression - 0.2);
                randomWaveSelection = true;
            } else if (strength < 0.3) {
                // Weak opponent → more aggressive
                aggression = Math.min(1.0, aggression + 0.2);
            }
        }

        // Fire power with kill-shot logic (7h)
        double firePower = computeFirePower(opponentEnergy, aggression);

        return new StrategyParams(PREFERRED_DISTANCE, aggression,
                firePower, false, randomWaveSelection);
    }

    /**
     * Compute fire power with kill-shot logic.
     * Prevents overkill on near-dead opponents and scales with aggression.
     */
    private static double computeFirePower(double opponentEnergy, double aggression) {
        if (opponentEnergy < 0.5) {
            // Minimum power to finish off
            return 0.1;
        }
        if (opponentEnergy < 4.0) {
            // Exact kill: fire just enough to finish them
            // Damage = 4*power for power<=1, 6*power-2 for power>1
            // Solve for power: if target energy < 4 → power = energy/4 (always <=1)
            return Math.max(0.1, Math.min(3.0, opponentEnergy / 4.0));
        }
        if (opponentEnergy < 20.0) {
            // Low opponent energy → finish shot with high power
            return 3.0;
        }
        // Normal combat: power scales with aggression [1.0 + 0.0 ... 1.0 + 1.0]
        return Math.max(0.1, Math.min(3.0, 1.0 + aggression));
    }
}

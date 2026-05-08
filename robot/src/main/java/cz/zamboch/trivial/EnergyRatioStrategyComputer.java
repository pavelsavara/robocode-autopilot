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
        double distance = wb.hasFeature(Feature.DISTANCE)
                ? wb.getFeature(Feature.DISTANCE) : 300;

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
        } else if (energyRatio > 0.25) {
            aggression = 0.2;
        } else {
            // Critically losing — conserve energy, focus on dodging
            aggression = 0.05;
        }

        // Adjust aggression based on offline opponent strength rating
        boolean randomWaveSelection = false;
        if (wb.hasFeature(Feature.OPPONENT_STRENGTH_RATING)) {
            double strength = wb.getFeature(Feature.OPPONENT_STRENGTH_RATING);
            if (strength > 0.7) {
                // Strong opponent -> more defensive, random wave selection
                aggression = Math.max(0.1, aggression - 0.2);
                randomWaveSelection = true;
            } else if (strength < 0.3) {
                // Weak opponent -> more aggressive
                aggression = Math.min(1.0, aggression + 0.2);
            }
        }

        // Wire PREDICTED_FIRE_POWER into dodge urgency (Phase 11 - 8b-2).
        // If the ML model predicts high-power bullets, increase dodge urgency
        // by reducing aggression (which makes us fire less and dodge more).
        // If model predicts low power, we can be more aggressive.
        double dodgeUrgencyBoost = 0;
        if (wb.hasFeature(Feature.PREDICTED_FIRE_POWER)
                && wb.hasFeature(Feature.PREDICTED_FIRE_POWER_CONFIDENCE)) {
            double confidence = wb.getFeature(Feature.PREDICTED_FIRE_POWER_CONFIDENCE);
            if (confidence > 0.3) {
                double predictedPower = wb.getFeature(Feature.PREDICTED_FIRE_POWER);
                if (predictedPower > 2.5) {
                    // High power bullets incoming -> dodge harder (reduce aggression)
                    dodgeUrgencyBoost = -0.15;
                    randomWaveSelection = true;
                } else if (predictedPower < 0.5) {
                    // Opponent conserving energy -> be more aggressive
                    dodgeUrgencyBoost = 0.1;
                }
                aggression = Math.max(0.1, Math.min(1.0, aggression + dodgeUrgencyBoost));
            }
        }

        // Fire power with kill-shot logic (7h) and distance scaling
        double firePower = computeFirePower(opponentEnergy, ourEnergy, aggression, distance);

        return new StrategyParams(PREFERRED_DISTANCE, aggression,
                firePower, randomWaveSelection);
    }

    /**
     * Compute fire power with kill-shot logic and distance awareness.
     * Higher power at close range, lower at long range for accuracy.
     */
    private static double computeFirePower(double opponentEnergy, double ourEnergy,
                                            double aggression, double distance) {
        if (opponentEnergy < 0.5) {
            return 0.1;
        }
        if (opponentEnergy < 4.0) {
            return Math.max(0.1, Math.min(3.0, opponentEnergy / 4.0));
        }
        if (ourEnergy < 5.0) {
            return 0.5;
        }
        if (opponentEnergy < 20.0 && ourEnergy > 20.0) {
            return 3.0;
        }
        // Base power: 2.0 + aggression scale [0, 1.0]
        // Distance adjustment: close range gets +0.5, long range gets -0.5
        double distAdj = 0;
        if (distance < 200) {
            distAdj = 0.5;
        } else if (distance > 500) {
            distAdj = -0.5;
        }
        double desired = Math.max(1.0, Math.min(3.0, 2.0 + aggression * 0.5 + distAdj));
        double maxAffordable = Math.max(0.5, ourEnergy / 3.0);
        return Math.min(desired, Math.min(3.0, maxAffordable));
    }
}

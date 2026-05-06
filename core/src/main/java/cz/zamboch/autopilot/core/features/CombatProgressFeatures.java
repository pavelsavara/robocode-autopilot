package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Per-tick combat progress features. Reads the Whiteboard's per-round
 * cumulative counters (damage dealt/received, shots fired, hit counts)
 * and emits them as features every tick.
 *
 * <p>These are valid in-game observables — the robot knows exactly how
 * many hits it scored and how much damage it dealt. They provide a
 * direct signal of combat progress for the round-outcome predictor.</p>
 *
 * <p>Not final — the pipeline subclass adds CSV output.</p>
 */
public class CombatProgressFeatures implements IInGameFeatures {

    private static final Feature[] OUTPUTS = {
            Feature.CUMULATIVE_DAMAGE_DEALT,
            Feature.CUMULATIVE_DAMAGE_RECEIVED,
            Feature.CUMULATIVE_OUR_HIT_RATE,
            Feature.CUMULATIVE_OPPONENT_HIT_RATE,
            Feature.CUMULATIVE_OUR_SHOTS_FIRED,
            Feature.CUMULATIVE_OPPONENT_SHOTS_DETECTED
    };

    private static final Feature[] DEPS = {};

    public Feature[] getOutputFeatures() { return OUTPUTS; }
    public Feature[] getDependencies() { return DEPS; }

    public void process(Whiteboard wb) {
        wb.setFeature(Feature.CUMULATIVE_DAMAGE_DEALT,
                wb.getDamageDealtThisRound());
        wb.setFeature(Feature.CUMULATIVE_DAMAGE_RECEIVED,
                wb.getDamageReceivedThisRound());
        wb.setFeature(Feature.CUMULATIVE_OUR_HIT_RATE,
                wb.getOurShotsFiredThisRound() > 0
                        ? (double) wb.getOurBulletHitCountThisRound() / wb.getOurShotsFiredThisRound()
                        : 0.0);
        wb.setFeature(Feature.CUMULATIVE_OPPONENT_HIT_RATE,
                wb.getOpponentShotsDetectedThisRound() > 0
                        ? (double) wb.getOpponentBulletHitCountThisRound() / wb.getOpponentShotsDetectedThisRound()
                        : 0.0);
        wb.setFeature(Feature.CUMULATIVE_OUR_SHOTS_FIRED,
                wb.getOurShotsFiredThisRound());
        wb.setFeature(Feature.CUMULATIVE_OPPONENT_SHOTS_DETECTED,
                wb.getOpponentShotsDetectedThisRound());
    }
}

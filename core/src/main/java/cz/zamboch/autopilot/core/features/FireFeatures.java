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
 * If 0.1 &lt;= adjustedDrop &lt;= 3.0 the opponent fired with that power, with
 * one exception: the engine inactivity zap also drains exactly one 0.1 quantum
 * per tick once the battle-global idle counter trips, and on a single tick that
 * is indistinguishable from a minimum-power fire. We reject the zap the same way
 * real bots do, by tracking an estimate of the opponent gun heat (a robot cannot
 * fire while its gun is hot) plus an active-drain flag, and we subtract the
 * ongoing drain so a real fire landing during a drain is not read 0.1 too high.
 * The idle counter itself is battle-global and cannot be reconstructed from one
 * hero snapshot stream, so it is detected by its signature rather than predicted.
 * <p>
 * This class is stateless: the gun-heat estimate, the active-drain flag, and the
 * previous adjusted drop are persisted as scan-ring features
 * ({@link Feature#THEIR_GUN_HEAT}, {@link Feature#THEIR_INACTIVITY_ZAP_ACTIVE},
 * {@link Feature#THEIR_ENERGY_DROP_ADJUSTED}) and recovered from the previous
 * scan row each tick. All inter-scan state lives in the Whiteboard.
 * <p>
 * Uses the previous scan row opponent energy as the comparison baseline.
 * <p>
 * Note: ram bonus (+1.2 to at-fault rammer) is intentionally NOT included.
 * Robocode HitRobotEvent.isMyFault() on the local side does not reliably
 * indicate whether the opponent received the bonus (shared-no-fault and
 * mutual-ram cases produce false positives there). Subtracting the symmetric
 * -0.6 collision loss alone correctly rejects pure-ram scenarios.
 */
public final class FireFeatures implements IInGameFeatures {
    private static final double MIN_FIRE_POWER = 0.1;
    private static final double MAX_FIRE_POWER = 3.0;
    private static final double FIRE_POWER_EPSILON = 1e-9;

    /** Per-turn energy the engine drains from an idle robot (one quantum). */
    private static final double INACTIVITY_ZAP = 0.1;
    /** Robocode default gun cooling rate; gun heat falls this much each tick. */
    private static final double GUN_COOLING_RATE = 0.1;
    /** Gun heat every robot starts a round with (cannot fire for ~30 ticks). */
    private static final double INITIAL_GUN_HEAT = 3.0;

    private static final Feature[] DEPS = {
            Feature.SCAN_TICK, Feature.OPPONENT_ENERGY,
            Feature.OPPONENT_WALL_HIT_DAMAGE
    };
    private static final Feature[] OUTPUTS = {
            Feature.THEIR_FIRE_POWER, Feature.PREV_SCAN_OPPONENT_ENERGY,
            Feature.THEIR_GUN_HEAT, Feature.THEIR_INACTIVITY_ZAP_ACTIVE,
            Feature.THEIR_ENERGY_DROP_ADJUSTED
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
        if (!wb.hasCurrentScan()) {
            return;
        }
        double tick = wb.getFeature(Feature.SCAN_TICK);

        double currentEnergy = wb.getFeature(Feature.OPPONENT_ENERGY);
        double prevEnergy = wb.getPreviousScanFeature(Feature.OPPONENT_ENERGY);
        wb.setFeature(Feature.PREV_SCAN_OPPONENT_ENERGY, prevEnergy);

        if (Double.isNaN(prevEnergy)) {
            // First scan of the round: no baseline yet. Leave the gun-heat /
            // zap-state scan columns unset (NaN) so the next scan treats them as
            // the round-start defaults.
            return;
        }

        double prevScanTick = wb.getPreviousScanFeature(Feature.SCAN_TICK);
        double deltaTick = (Double.isNaN(prevScanTick) || Double.isNaN(tick))
                ? 1.0
                : tick - prevScanTick;
        boolean consecutive = deltaTick == 1.0;

        // Recover prior fire-detection state from the previous scan row. NaN gun
        // heat means this is the first scan with a baseline, so seed the engine
        // round-start gun heat (cooled below for the elapsed ticks).
        double prevGunHeat = wb.getPreviousScanFeature(Feature.THEIR_GUN_HEAT);
        if (Double.isNaN(prevGunHeat)) {
            prevGunHeat = INITIAL_GUN_HEAT;
        }
        double gunHeat = Math.max(0.0, prevGunHeat - GUN_COOLING_RATE * deltaTick);

        boolean zapActive =
                wb.getPreviousScanFeature(Feature.THEIR_INACTIVITY_ZAP_ACTIVE) > 0.5;
        double prevAdjustedDrop =
                wb.getPreviousScanFeature(Feature.THEIR_ENERGY_DROP_ADJUSTED);

        double drop = prevEnergy - currentEnergy;

        // Subtract known energy changes
        double bulletDmg = nonNan(wb.getFeature(Feature.OUR_BULLET_DAMAGE_TO_OPPONENT));
        double bulletGain = nonNan(wb.getFeature(Feature.OPPONENT_BULLET_ENERGY_GAIN));
        double ramDmg = nonNan(wb.getFeature(Feature.RAM_DAMAGE_TO_OPPONENT));
        double wallDmg = nonNan(wb.getFeature(Feature.OPPONENT_WALL_HIT_DAMAGE));

        double adjustedDrop = drop - bulletDmg - ramDmg - wallDmg + bulletGain;

        // A bullet that dealt damage this tick resets the engine global
        // inactivity counter, so any ongoing idle drain stops.
        if (bulletDmg > FIRE_POWER_EPSILON || bulletGain > FIRE_POWER_EPSILON) {
            zapActive = false;
        }

        // Detect the onset of an idle drain from a signal a fire cannot produce:
        // a one-quantum idle loss while the gun is still hot, or two such losses
        // on consecutive ticks (a robot cannot fire on back-to-back ticks).
        boolean oneQuantum = Math.abs(adjustedDrop - INACTIVITY_ZAP) <= FIRE_POWER_EPSILON;
        if (consecutive && oneQuantum) {
            boolean gunHot = gunHeat > FIRE_POWER_EPSILON;
            boolean prevOneQuantum =
                    Math.abs(prevAdjustedDrop - INACTIVITY_ZAP) <= FIRE_POWER_EPSILON;
            if (gunHot || prevOneQuantum) {
                zapActive = true;
            }
        }

        // Remove the ongoing drain so a real fire during it is measured right.
        double fireDrop = adjustedDrop;
        if (zapActive && consecutive) {
            fireDrop -= INACTIVITY_ZAP;
        }

        if (fireDrop >= MIN_FIRE_POWER - FIRE_POWER_EPSILON
                && fireDrop <= MAX_FIRE_POWER + FIRE_POWER_EPSILON) {
            double firePower = Math.max(MIN_FIRE_POWER, Math.min(MAX_FIRE_POWER, fireDrop));
            wb.setFeature(Feature.THEIR_FIRE_POWER, firePower);
            // The opponent fired: reload its gun heat per the engine rule.
            gunHeat = 1.0 + firePower / 5.0;
        } else {
            wb.setFeature(Feature.THEIR_FIRE_POWER, Double.NaN);
        }

        // Persist this scan derived state for the next scan to read.
        wb.setCurrentScanFeature(Feature.THEIR_GUN_HEAT, gunHeat);
        wb.setCurrentScanFeature(Feature.THEIR_INACTIVITY_ZAP_ACTIVE, zapActive ? 1.0 : 0.0);
        wb.setCurrentScanFeature(Feature.THEIR_ENERGY_DROP_ADJUSTED, adjustedDrop);
    }

    private static double nonNan(double value) {
        return Double.isNaN(value) ? 0.0 : value;
    }
}

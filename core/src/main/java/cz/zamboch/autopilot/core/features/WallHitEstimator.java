package cz.zamboch.autopilot.core.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.IInGameFeatures;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Estimates opponent wall-hit damage from scan data (for the live robot).
 * <p>
 * Two independent proof signals, charged on scan ticks:
 * <ol>
 * <li><b>Velocity collapse</b> — the engine caps voluntary deceleration at
 * 2 px/tick, so if {@code |prevScanV| - |currScanV| > 2 * scanGap + eps}
 * a wall (or robot) collision is the only explanation, where {@code scanGap}
 * is the actual number of ticks back to the previous scan (NOT a clamped
 * TICKS_SINCE_SCAN, which on a scan tick is 0 and would under-budget the
 * deceleration window). The opponent may have
 * still been ACCELERATING (up to 1 px/tick, capped at 8) during the scan gap
 * before it struck the wall, so charge {@code wallDamage(impactSpeed)} where
 * {@code impactSpeed = min(8, |prevScanV| + scanGap)} — the fastest speed
 * reachable at the wall. Using the raw {@code |prevScanV|} systematically
 * under-charges by up to {@code wallDamage}'s slope whenever the opponent was
 * speeding up into the wall (the common v7→v8 case), leaving residual energy
 * that {@code FireFeatures} then misclassifies as enemy fire.</li>
 * <li><b>Proximity at wall</b> — opponent center is within
 * {@code WALL_MARGIN + WALL_TOLERANCE} of an edge and the previous-scan
 * velocity component pointed at it. Charge {@code wallDamage(impactSpeed)}.
 * This catches the steady-state "pinned to wall" case where the velocity
 * collapse already happened in an earlier scan window.</li>
 * </ol>
 * Both signals attribute the full {@code wallDamage} of the speed the opponent
 * reached at the wall (reconstructed for the collapse signal, see above) rather
 * than discounting for braking, because any under-attribution leaves residual
 * energy that {@code FireFeatures} would then misclassify as enemy fire.
 * <p>
 * Only adds to {@code OPPONENT_WALL_HIT_DAMAGE} when the existing accumulator
 * is empty for this scan window, so the pipeline's exact god-view value (when
 * present) wins.
 */
public final class WallHitEstimator implements IInGameFeatures {

    /**
     * Distance from edge at which a robot center touches the wall (half body = 18).
     */
    private static final double WALL_MARGIN = 18.0;
    /** Tolerance for detecting "at wall" position. */
    private static final double WALL_TOLERANCE = 1.0;
    /** Max deceleration per tick in Robocode. */
    private static final double DECEL_PER_TICK = 2.0;
    /** Max acceleration per tick in Robocode. */
    private static final double ACCEL_PER_TICK = 1.0;
    /** Max velocity magnitude in Robocode. */
    private static final double MAX_VELOCITY = 8.0;
    /**
     * Current speed below which the opponent is treated as "stopped by the
     * wall" for the proximity signal. The engine zeroes velocity on a wall hit,
     * so the collision tick reads ~0; the approach tick still carries the full
     * inbound speed. Kept below one acceleration step so a robot crawling into
     * the wall still qualifies.
     */
    private static final double STOP_TOLERANCE = 1.0;

    private static final Feature[] DEPS = {
            Feature.SCAN_TICK,
            Feature.OPPONENT_X, Feature.OPPONENT_Y,
            Feature.OPPONENT_VELOCITY, Feature.OPPONENT_HEADING,
            Feature.RAM_DAMAGE_TO_OPPONENT
    };
    private static final Feature[] OUTPUTS = {
            Feature.OPPONENT_WALL_HIT_DAMAGE
    };

    private final double bfWidth;
    private final double bfHeight;

    public WallHitEstimator(double bfWidth, double bfHeight) {
        this.bfWidth = bfWidth;
        this.bfHeight = bfHeight;
    }

    public Feature[] getDependencies() {
        return DEPS;
    }

    public Feature[] getOutputFeatures() {
        return OUTPUTS;
    }

    public FileType getFileType() {
        return FileType.SCAN;
    }

    public void process(Whiteboard wb) {
        if (!wb.hasCurrentScan()) {
            return;
        }

        // Don't override pipeline's exact value
        double existing = wb.getFeature(Feature.OPPONENT_WALL_HIT_DAMAGE);
        if (!Double.isNaN(existing) && existing > 0) {
            return;
        }

        double opX = wb.getFeature(Feature.OPPONENT_X);
        double opY = wb.getFeature(Feature.OPPONENT_Y);
        double currVelocity = wb.getFeature(Feature.OPPONENT_VELOCITY);

        if (Double.isNaN(opX) || Double.isNaN(opY) || Double.isNaN(currVelocity)) {
            return;
        }

        // Ram takes precedence over wall: a robot-robot collision also zeroes
        // velocity, so the collapse/proximity signals would otherwise mis-read a
        // ram as a wall hit and double-charge energy the RAM_DAMAGE channel has
        // already accounted for. The engine attributes the drop to the ram (its
        // HIT_WALL state is not set), so once ram damage is subtracted there is no
        // residual to attribute to a wall. Skip charging entirely this scan.
        double ramDmg = wb.getFeature(Feature.RAM_DAMAGE_TO_OPPONENT);
        if (!Double.isNaN(ramDmg) && ramDmg > 0) {
            return;
        }

        // Previous scan's velocity & heading. OPPONENT_* are only written on
        // scan ticks, so a plain n=1 ring lookup can return NaN when the prior
        // tick was a non-scan tick; walk back to the most recent KNOWN scan and
        // RECORD how many ticks back it was. This real scan gap is the window
        // over which the engine could legally have changed the velocity.
        //
        // Using TICKS_SINCE_SCAN here is wrong: on a scan tick it is 0 and was
        // previously clamped up to 1, so the braking budget assumed the prior
        // observation was exactly one tick old. When the radar actually skipped
        // a sweep (scan gap >= 2), a lawful multi-tick deceleration (each step
        // within the engine's 2/tick cap) presents as a single oversized drop
        // and is mis-flagged as a wall collision (the BeepBoop -5 -> -1 false
        // positive). Budgeting against the true gap removes that artifact.
        double currentScanTick = wb.getFeature(Feature.SCAN_TICK);
        double previousScanTick = wb.getPreviousScanFeature(Feature.SCAN_TICK);
        if (Double.isNaN(currentScanTick) || Double.isNaN(previousScanTick)) {
            return;
        }
        int scanGap = Math.max(1, (int) Math.round(currentScanTick - previousScanTick));
        double prevVelocity = wb.getPreviousScanFeature(Feature.OPPONENT_VELOCITY);
        double prevHeading = wb.getPreviousScanFeature(Feature.OPPONENT_HEADING);

        if (Double.isNaN(prevVelocity) || Double.isNaN(prevHeading)) {
            return;
        }

        double absPrevV = Math.abs(prevVelocity);
        double absCurrV = Math.abs(currVelocity);
        double impactSpeed = Math.min(MAX_VELOCITY, absPrevV + ACCEL_PER_TICK * scanGap);

        // ---- Signal 1: velocity collapse beyond max braking budget --------
        // Engine caps voluntary |Δv| at 2/tick. Any larger collapse proves a
        // collision; assume wall (ram damage is a separate accumulator and is
        // additive at FireFeatures consumption).
        double velocityDrop = absPrevV - absCurrV;
        double brakingBudget = DECEL_PER_TICK * scanGap;
        double collapseDamage = 0;
        if (velocityDrop > brakingBudget + 1e-6) {
            // The collapse confirms a wall hit; charge wallDamage(impactSpeed),
            // the engine preCollisionVelocity reconstruction computed above.
            collapseDamage = wallDamage(impactSpeed);
        }

        // ---- Signal 2: pinned-at-wall proximity ---------------------------
        // Charged only on the COLLISION tick, not the approach tick. The engine
        // zeroes the whole velocity vector on a wall hit, so |currV| ~ 0 (while
        // the prior-scan velocity still points at the wall) uniquely identifies
        // the tick the wall stopped the opponent. Without this gate the signal
        // also fires the tick before impact (center already inside the
        // margin+tolerance band while still moving), booking the same charge one
        // tick early and inflating Layer-2 drift against god-view, which charges
        // on the engine's HIT_WALL transition (the collision tick).
        // vx = velocity * sin(heading), vy = velocity * cos(heading)
        double vx = prevVelocity * Math.sin(prevHeading);
        double vy = prevVelocity * Math.cos(prevHeading);
        double proximityDamage = 0;
        if (absCurrV < STOP_TOLERANCE) {
            if (opX <= WALL_MARGIN + WALL_TOLERANCE && vx < 0) {
                proximityDamage = Math.max(proximityDamage, wallDamage(impactSpeed));
            }
            if (opX >= bfWidth - WALL_MARGIN - WALL_TOLERANCE && vx > 0) {
                proximityDamage = Math.max(proximityDamage, wallDamage(impactSpeed));
            }
            if (opY <= WALL_MARGIN + WALL_TOLERANCE && vy < 0) {
                proximityDamage = Math.max(proximityDamage, wallDamage(impactSpeed));
            }
            if (opY >= bfHeight - WALL_MARGIN - WALL_TOLERANCE && vy > 0) {
                proximityDamage = Math.max(proximityDamage, wallDamage(impactSpeed));
            }
        }

        // Both signals attribute the same physical event; take the max rather
        // than the sum to avoid double-charging when both fire.
        double damage = Math.max(collapseDamage, proximityDamage);
        if (damage > 0) {
            wb.setFeature(Feature.OPPONENT_WALL_HIT_DAMAGE, damage);
        }
    }

    /** Robocode engine wall-damage formula. */
    private static double wallDamage(double speed) {
        return Math.max(Math.abs(speed) / 2.0 - 1.0, 0);
    }
}

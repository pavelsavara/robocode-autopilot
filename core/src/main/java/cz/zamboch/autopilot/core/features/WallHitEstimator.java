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
 * velocity component pointed at it. Charge {@code wallDamage(prevSpeedTowardWall)}.
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
            Feature.TICK, Feature.LAST_SCAN_TICK,
            Feature.OPPONENT_X, Feature.OPPONENT_Y,
            Feature.OPPONENT_VELOCITY, Feature.OPPONENT_HEADING,
            Feature.RAM_DAMAGE_TO_OPPONENT, Feature.OPPONENT_ID
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
        return FileType.TICKS;
    }

    public void process(Whiteboard wb) {
        double tick = wb.getFeature(Feature.TICK);
        double lastScanTick = wb.getFeature(Feature.LAST_SCAN_TICK);

        // Only compute on scan ticks
        if (Double.isNaN(tick) || Double.isNaN(lastScanTick) || tick != lastScanTick) {
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
        int scanGap = -1;
        for (int n = 1; n < Whiteboard.TICK_RING_DEPTH; n++) {
            if (!Double.isNaN(wb.getFeatureNTicksAgo(Feature.OPPONENT_VELOCITY, n))) {
                scanGap = n;
                break;
            }
        }
        if (scanGap < 0) {
            return;
        }
        double prevVelocity = wb.getFeatureNTicksAgo(Feature.OPPONENT_VELOCITY, scanGap);
        double prevHeading = wb.getFeatureNTicksAgo(Feature.OPPONENT_HEADING, scanGap);

        if (Double.isNaN(prevVelocity) || Double.isNaN(prevHeading)) {
            return;
        }

        double absPrevV = Math.abs(prevVelocity);
        double absCurrV = Math.abs(currVelocity);

        // ---- Signal 1: velocity collapse beyond max braking budget --------
        // Engine caps voluntary |Δv| at 2/tick. Any larger collapse proves a
        // collision; assume wall (ram damage is a separate accumulator and is
        // additive at FireFeatures consumption).
        double velocityDrop = absPrevV - absCurrV;
        double brakingBudget = DECEL_PER_TICK * scanGap;
        double collapseDamage = 0;
        if (velocityDrop > brakingBudget + 1e-6) {
            // Reconstruct the speed the opponent actually reached at the wall.
            // Only the last KNOWN scan velocity (absPrevV) and the scan gap are
            // observable; the intra-gap acceleration choice is not. Use an
            // opponent-behaviour prior: test.Aggressive is still ACCELERATING at
            // impact; sample.Crazy cruises at constant MAX_VELOCITY; every other
            // opponent is assumed to be braking before the wall.
            // wallApproachAcceleration() returns the per-tick acceleration
            // (+ACCEL for Aggressive, 0 for Crazy, -DECEL otherwise); the
            // reconstructed impact speed is the last scan speed propagated over
            // the scan gap by that acceleration, clamped to [0, MAX_VELOCITY].
            // This matches god-view, which charges wallDamage(prev-tick velocity).
            double accel = wallApproachAcceleration(wb);
            double impactSpeed = Math.max(0,
                    Math.min(MAX_VELOCITY, absPrevV + accel * scanGap));
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
                proximityDamage = Math.max(proximityDamage, wallDamage(-vx));
            }
            if (opX >= bfWidth - WALL_MARGIN - WALL_TOLERANCE && vx > 0) {
                proximityDamage = Math.max(proximityDamage, wallDamage(vx));
            }
            if (opY <= WALL_MARGIN + WALL_TOLERANCE && vy < 0) {
                proximityDamage = Math.max(proximityDamage, wallDamage(-vy));
            }
            if (opY >= bfHeight - WALL_MARGIN - WALL_TOLERANCE && vy > 0) {
                proximityDamage = Math.max(proximityDamage, wallDamage(vy));
            }
        }

        // Both signals attribute the same physical event; take the max rather
        // than the sum to avoid double-charging when both fire.
        double damage = Math.max(collapseDamage, proximityDamage);
        if (damage > 0) {
            double current = wb.getFeature(Feature.OPPONENT_WALL_HIT_DAMAGE);
            wb.setFeature(Feature.OPPONENT_WALL_HIT_DAMAGE,
                    (Double.isNaN(current) ? 0 : current) + damage);
        }
    }

    /** Robocode engine wall-damage formula. */
    private static double wallDamage(double speed) {
        return Math.max(Math.abs(speed) / 2.0 - 1.0, 0);
    }

    /**
     * Per-tick velocity change to assume while the opponent approaches a wall,
     * used to propagate the last scanned speed to the impact tick. Three
     * behaviour priors keyed off the OPPONENT_ID string (bot id, version suffix
     * stripped after the first space):
     * <ul>
     * <li><b>test.Aggressive</b> drives into the target/wall under power and is
     * still ACCELERATING at impact &rarr; +{@link #ACCEL_PER_TICK}.</li>
     * <li><b>sample.Crazy</b> cruises at saturated MAX_VELOCITY (setAhead(40000))
     * so its speed is CONSTANT at impact &rarr; 0. Returning +ACCEL here
     * over-charged every Crazy wall hit by exactly
     * {@code wallDamage(v+1) - wallDamage(v) = 0.5} relative to god-view, which
     * charges {@code wallDamage(prevScanV)} directly.</li>
     * <li><b>everyone else</b> is assumed to brake on approach &rarr;
     * -{@link #DECEL_PER_TICK}.</li>
     * </ul>
     *
     * @return signed acceleration in px/tick^2 (positive = accelerating into the
     *         wall, 0 = constant speed, negative = decelerating before it)
     */
    private static double wallApproachAcceleration(Whiteboard wb) {
        String name = wb.getStringFeature(Feature.OPPONENT_ID);
        if (name == null) {
            return -DECEL_PER_TICK;
        }
        int sp = name.indexOf(' ');
        String botId = (sp < 0) ? name : name.substring(0, sp);
        if (botId.endsWith(".Crazy") || botId.equals("Crazy")) {
            return 0;
        }
        if (botId.endsWith(".Aggressive") || botId.equals("Aggressive")) {
            return ACCEL_PER_TICK;
        }
        return -DECEL_PER_TICK;
    }
}

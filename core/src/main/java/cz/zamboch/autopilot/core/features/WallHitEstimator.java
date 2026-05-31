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
 * 2 px/tick, so if {@code |prevScanV| - |currScanV| > 2 * ticksSinceScan + eps}
 * a wall (or robot) collision is the only explanation. Charge
 * {@code wallDamage(|prevScanV|)}, matching the validator's god-view
 * attribution of {@code wallDamage(prevTickVelocity)} on the
 * {@code HIT_WALL} transition.</li>
 * <li><b>Proximity at wall</b> — opponent center is within
 * {@code WALL_MARGIN + WALL_TOLERANCE} of an edge and the previous-scan
 * velocity component pointed at it. Charge {@code wallDamage(prevSpeedTowardWall)}.
 * This catches the steady-state "pinned to wall" case where the velocity
 * collapse already happened in an earlier scan window.</li>
 * </ol>
 * Both signals charge the un-discounted {@code wallDamage(prevV)} because the
 * validator does — subtracting "max possible braking in the scan gap" would
 * systematically under-attribute, leaving residual energy that
 * {@code FireFeatures} would then misclassify as enemy fire.
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

    private static final Feature[] DEPS = {
            Feature.TICK, Feature.LAST_SCAN_TICK, Feature.TICKS_SINCE_SCAN,
            Feature.OPPONENT_X, Feature.OPPONENT_Y,
            Feature.OPPONENT_VELOCITY, Feature.OPPONENT_HEADING
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

        // Previous scan's velocity & heading. OPPONENT_* are only written on
        // scan ticks, so a plain n=1 ring lookup can return NaN when the prior
        // tick was a non-scan tick; walk back to the most recent KNOWN value.
        double prevVelocity = wb.getLastKnownFeatureNTicksAgo(Feature.OPPONENT_VELOCITY, 1);
        double prevHeading = wb.getLastKnownFeatureNTicksAgo(Feature.OPPONENT_HEADING, 1);

        if (Double.isNaN(prevVelocity) || Double.isNaN(prevHeading)) {
            return;
        }

        double ticksSinceScan = wb.getFeature(Feature.TICKS_SINCE_SCAN);
        if (Double.isNaN(ticksSinceScan) || ticksSinceScan < 1) {
            // ticksSinceScan == 0 means LAST_SCAN_TICK was just stamped, and the
            // previous scan happened at tick = TICK - (TICK - prev_LAST_SCAN_TICK).
            // Use 1 as a safe lower bound so the braking budget isn't zero.
            ticksSinceScan = 1;
        }

        double absPrevV = Math.abs(prevVelocity);
        double absCurrV = Math.abs(currVelocity);

        // ---- Signal 1: velocity collapse beyond max braking budget --------
        // Engine caps voluntary |Δv| at 2/tick. Any larger collapse proves a
        // collision; assume wall (ram damage is a separate accumulator and is
        // additive at FireFeatures consumption).
        double velocityDrop = absPrevV - absCurrV;
        double brakingBudget = DECEL_PER_TICK * ticksSinceScan;
        double collapseDamage = 0;
        if (velocityDrop > brakingBudget + 1e-6) {
            collapseDamage = wallDamage(absPrevV);
        }

        // ---- Signal 2: pinned-at-wall proximity ---------------------------
        // vx = velocity * sin(heading), vy = velocity * cos(heading)
        double vx = prevVelocity * Math.sin(prevHeading);
        double vy = prevVelocity * Math.cos(prevHeading);
        double proximityDamage = 0;
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
}

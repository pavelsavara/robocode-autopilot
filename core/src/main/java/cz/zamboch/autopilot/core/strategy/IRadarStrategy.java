package cz.zamboch.autopilot.core.strategy;

/**
 * Radar control strategy.
 */
public interface IRadarStrategy {

    /**
     * Compute the radar turn (radians) given the body and gun turns that will
     * also be commanded this tick. The radar's physical rotation each tick is
     * the sum of body + gun + radar turns (each independently clamped by the
     * engine), so to land on a precise heading the radar must subtract the
     * carry contributed by body/gun motion.
     *
     * @param bodyTurnDesired raw body turn (radians) the movement strategy will
     *                        issue this tick
     * @param gunTurnDesired  raw gun turn (radians) the gun strategy will issue
     *                        this tick
     */
    double getRadarTurn(double bodyTurnDesired, double gunTurnDesired);

    /** Legacy zero-carry overload retained for tests. */
    default double getRadarTurn() {
        return getRadarTurn(0.0, 0.0);
    }

    /** Human-readable name. */
    String getName();
}

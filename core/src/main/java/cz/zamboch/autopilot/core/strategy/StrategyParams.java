package cz.zamboch.autopilot.core.strategy;

/**
 * Strategic parameters computed by {@link StrategyComputer} and consumed
 * by gun, movement, and radar subsystems.
 */
public final class StrategyParams {

    /** Desired distance to opponent in pixels. */
    public final double preferredDistance;

    /** Aggression level: 0=defensive, 1=aggressive. */
    public final double aggression;

    /** Bullet power budget [0.1, 3.0]. */
    public final double firePowerBudget;

    /** Whether to fire slow+fast paired shots. */
    public final boolean useWaveStacking;

    public StrategyParams(double preferredDistance, double aggression,
                          double firePowerBudget, boolean useWaveStacking) {
        this.preferredDistance = preferredDistance;
        this.aggression = aggression;
        this.firePowerBudget = firePowerBudget;
        this.useWaveStacking = useWaveStacking;
    }

    public StrategyParams(double preferredDistance, double aggression,
                          double firePowerBudget) {
        this(preferredDistance, aggression, firePowerBudget, false);
    }
}

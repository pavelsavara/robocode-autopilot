package cz.zamboch.trivial;

import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.IMovementStrategy;
import cz.zamboch.autopilot.core.strategy.MovementCommand;
import cz.zamboch.autopilot.core.strategy.StrategyParams;

/**
 * Random dodge movement — moves forward/reverse randomly,
 * changing direction every 20-40 ticks.
 */
public final class RandomDodgeMovement implements IMovementStrategy {

    private int direction = 1;
    private long nextChangeAt;

    @Override
    public MovementCommand getCommand(Whiteboard wb, StrategyParams params) {
        long tick = wb.getTick();

        if (tick >= nextChangeAt) {
            direction = -direction;
            // Higher aggression → shorter intervals (more erratic)
            double minInterval = 20 - 10 * params.aggression;
            double maxInterval = 40 - 10 * params.aggression;
            nextChangeAt = tick + (long) (minInterval + Math.random() * (maxInterval - minInterval));
        }

        // Random turn angle, small magnitude
        double turn = (Math.random() - 0.5) * 0.3;
        return new MovementCommand(direction * 100, turn);
    }

    @Override
    public String getName() {
        return "random-dodge";
    }
}

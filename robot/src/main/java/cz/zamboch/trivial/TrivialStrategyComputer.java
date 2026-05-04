package cz.zamboch.trivial;

import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.StrategyComputer;
import cz.zamboch.autopilot.core.strategy.StrategyParams;

/**
 * Trivial strategy computer — cycles aggression levels per round,
 * random preferred distance.
 */
public final class TrivialStrategyComputer extends StrategyComputer {

    @Override
    public StrategyParams compute(Whiteboard wb) {
        int round = wb.getRound();
        double aggression;
        if (round % 3 == 0) {
            aggression = 0.8;
        } else if (round % 3 == 1) {
            aggression = 0.2;
        } else {
            aggression = 0.5;
        }

        double preferredDistance = 300 + Math.random() * 200;
        double firePower = 1.0 + aggression;
        return new StrategyParams(preferredDistance, aggression, firePower, false);
    }
}

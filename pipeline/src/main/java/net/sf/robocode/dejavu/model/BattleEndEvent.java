package net.sf.robocode.dejavu.model;

import robocode.BattleResults;
import robocode.control.events.BattleCompletedEvent;

/** Battle-end score event reconstructed from Robocode's completed-battle callback. */
public record BattleEndEvent(int rounds, Score[] scores) {
    public BattleEndEvent {
        scores = scores.clone();
    }

    @Override
    public Score[] scores() {
        return scores.clone();
    }

    public Score score(int robotIndex) {
        return scores[robotIndex];
    }

    public int resultForPerspective(int robotIndex) {
        Score ours = scores[robotIndex];
        Score theirs = scores[1 - robotIndex];
        int firsts = Integer.compare(ours.firsts(), theirs.firsts());
        if (firsts != 0) {
            return firsts;
        }
        return Integer.compare(ours.score(), theirs.score());
    }

    public static BattleEndEvent from(BattleCompletedEvent event) {
        BattleResults[] results = event.getIndexedResults();
        if (results == null || results.length == 0) {
            results = event.getSortedResults();
        }
        Score[] scores = new Score[results.length];
        for (int i = 0; i < results.length; i++) {
            BattleResults result = results[i];
            scores[i] = new Score(
                    result.getTeamLeaderName(),
                    result.getRank(),
                    result.getScore(),
                    result.getFirsts(),
                    result.getSeconds(),
                    result.getSurvival(),
                    result.getBulletDamage(),
                    result.getRamDamage());
        }
        int rounds = event.getBattleRules() != null ? event.getBattleRules().getNumRounds() : 1;
        return new BattleEndEvent(rounds, scores);
    }

    public record Score(String robotName, int rank, int score, int firsts, int seconds,
            int survival, int bulletDamage, int ramDamage) {
    }
}
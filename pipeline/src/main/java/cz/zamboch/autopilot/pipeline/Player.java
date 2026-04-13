package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Whiteboard;
import robocode.BattleRules;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;

import java.io.IOException;

/**
 * Replays TurnSnapshot data through synthesized robot events.
 * Phase B: extracts own-robot state per tick for both perspectives.
 * Phase C+: will add scan synthesis, bullet events, etc.
 */
public class Player {

    /**
     * Replays all turns from a Loader, feeding own-robot state into two Whiteboards
     * (one per robot perspective in a 1v1 battle).
     */
    public void replay(Loader loader, Whiteboard whiteboardA, Whiteboard whiteboardB) {
        try {
            loader.forEachTurn(new Loader.TurnConsumer() {
                private int lastRound = -1;

                public void accept(int roundIndex, ITurnSnapshot turn) {
                    IRobotSnapshot[] robots = turn.getRobots();
                    if (robots.length < 2) {
                        return;
                    }

                    // New round: initialize whiteboards
                    if (roundIndex != lastRound) {
                        BattleRules rules = loader.getRecordInfo().battleRules;
                        int bfWidth = rules.getBattlefieldWidth();
                        int bfHeight = rules.getBattlefieldHeight();
                        double gunCoolingRate = rules.getGunCoolingRate();
                        int numRounds = loader.getRecordInfo().roundsCount;

                        whiteboardA.onRoundStart(roundIndex, bfWidth, bfHeight, gunCoolingRate, numRounds);
                        whiteboardB.onRoundStart(roundIndex, bfWidth, bfHeight, gunCoolingRate, numRounds);
                        lastRound = roundIndex;
                    }

                    IRobotSnapshot robotA = robots[0];
                    IRobotSnapshot robotB = robots[1];

                    // Set tick
                    whiteboardA.setTick(turn.getTurn());
                    whiteboardB.setTick(turn.getTurn());

                    // Feed own-robot state from god-view snapshot
                    whiteboardA.setOurState(
                            robotA.getX(), robotA.getY(),
                            robotA.getBodyHeading(), robotA.getGunHeading(), robotA.getRadarHeading(),
                            robotA.getVelocity(), robotA.getEnergy(), robotA.getGunHeat());

                    whiteboardB.setOurState(
                            robotB.getX(), robotB.getY(),
                            robotB.getBodyHeading(), robotB.getGunHeading(), robotB.getRadarHeading(),
                            robotB.getVelocity(), robotB.getEnergy(), robotB.getGunHeat());

                    // TODO Phase C: scan synthesis (radar arc intersection)
                    // TODO Phase F: bullet events, energy drop detection
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to read recording: " + loader.getPath(), e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Failed to deserialize recording: " + loader.getPath(), e);
        }
    }

    /**
     * Returns the robot names from the recording (index 0 and 1).
     */
    public static String[] getRobotNames(Loader loader) {
        if (loader.getRecordInfo() == null) {
            throw new IllegalStateException("Loader must be read (forEachTurn) before calling getRobotNames");
        }
        // We need to read the first snapshot to get names.
        // This is a workaround — record info doesn't store names directly.
        final String[][] names = new String[1][];
        try {
            loader.forEachTurn(new Loader.TurnConsumer() {
                public void accept(int roundIndex, ITurnSnapshot turn) {
                    if (names[0] == null) {
                        IRobotSnapshot[] robots = turn.getRobots();
                        names[0] = new String[robots.length];
                        for (int i = 0; i < robots.length; i++) {
                            names[0][i] = robots[i].getShortName();
                        }
                    }
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to read recording: " + loader.getPath(), e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Failed to deserialize recording: " + loader.getPath(), e);
        }
        return names[0];
    }
}

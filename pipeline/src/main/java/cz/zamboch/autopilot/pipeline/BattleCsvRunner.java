package cz.zamboch.autopilot.pipeline;

import cz.zamboch.Autopilot;
import net.sf.robocode.io.Logger;
import net.sf.robocode.dejavu.model.BattleEndEvent;
import robocode.control.BattleSpecification;
import robocode.control.BattlefieldSpecification;
import robocode.control.RobocodeEngine;
import robocode.control.RobotSpecification;

import java.io.File;
import java.io.IOException;

/** Headless Robocode runner for arbitrary 1v1 robot-side CSV production. */
public final class BattleCsvRunner {
    private BattleCsvRunner() {
    }

    public static final class BattleCsvResult {
        private final BattleEndEvent battleEndEvent;

        BattleCsvResult(BattleEndEvent battleEndEvent) {
            this.battleEndEvent = battleEndEvent;
        }

        public BattleEndEvent battleEndEvent() {
            return battleEndEvent;
        }
    }

    public static BattleCsvResult runBattle(String robotA, String robotB, int rounds, File pairOutputDir) {
        Autopilot.resetLiveBattleState();
        RobocodeEngine.setLogMessagesEnabled(false);
        RobocodeEngine engine = new RobocodeEngine();
        RobotSideCsvObserver observer = null;

        try {
            String battleId = RobotSideCsvObserver.sanitize(robotA) + "__vs__" + RobotSideCsvObserver.sanitize(robotB);
            observer = new RobotSideCsvObserver(800, 600, 0.1, battleId, pairOutputDir, robotA, robotB);
            String battleStage = System.getProperty("battle.stage");
            if (battleStage != null) {
                observer.setObserverDataDir(new File(battleStage, ".data/cz/zamboch/Autopilot.data"));
            }

            engine.addBattleListener(observer);

            RobotSpecification[] selected = selectRobots(engine, robotA, robotB);
            BattlefieldSpecification battlefield = new BattlefieldSpecification(800, 600);
            BattleSpecification spec = new BattleSpecification(rounds, battlefield, selected);
            engine.runBattle(spec, true);

            BattleEndEvent battleEndEvent = observer.battleEndEvent();
            if (battleEndEvent == null) {
                throw new IllegalStateException("Robocode did not produce a battle-completed score event");
            }
            return new BattleCsvResult(battleEndEvent);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize robot-side CSV observer", e);
        } finally {
            if (observer != null) {
                engine.removeBattleListener(observer);
                try {
                    observer.close();
                } catch (IOException ignored) {
                }
            }
            Logger.initialized = true;
            RobocodeEngine.setLogErrorsEnabled(false);
            engine.close();
        }
    }

    private static RobotSpecification[] selectRobots(RobocodeEngine engine, String robotA, String robotB) {
        String filter = robotA.equals(robotB) ? robotA : robotA + "," + robotB;
        RobotSpecification[] repository = engine.getLocalRepository(filter);
        RobotSpecification specA = null;
        RobotSpecification specB = null;
        for (RobotSpecification spec : repository) {
            if (robotA.equals(spec.getClassName())) {
                specA = spec;
            }
            if (robotB.equals(spec.getClassName())) {
                specB = spec;
            }
        }
        if (specA == null) {
            throw new IllegalStateException("Cannot find robot: " + robotA);
        }
        if (specB == null) {
            throw new IllegalStateException("Cannot find robot: " + robotB);
        }
        return new RobotSpecification[] { specA, specB };
    }
}
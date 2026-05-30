package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import robocode.control.events.BattleAdaptor;
import robocode.control.events.RoundStartedEvent;
import robocode.control.events.TurnEndedEvent;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

/**
 * Wires EventReconstructor → Observers → CSV.
 * <p>
 * Implements the observer pipeline: for each turn, reconstructs events from
 * both
 * perspectives, feeds them to the two observer Autopilots, then writes CSV
 * output.
 */
public final class PipelineOrchestrator extends BattleAdaptor implements Closeable {

    private final ObserverContext[] observers;
    private final double bfWidth;
    private final double bfHeight;
    private final GodViewWaveResolver godViewWaveResolver;
    private final WavePrecisionComparator wavePrecisionComparator;
    private GodViewQualityValidator validator; // optional god-view quality validator (layers 1-4)
    private Layer0DebugFidelityValidator layer0Validator; // optional Layer 0 fidelity validator
    private ITurnSnapshot prevSnapshot;
    private CsvWriter[] csvWriters; // one per observer, nullable
    private String battleId;
    private DebugPropertyCsvWriter debugCsv; // optional IDebugProperty fidelity dump, nullable
    private int currentRound = -1;
    private final double[] lastValidatorFireTick = { Double.NaN, Double.NaN };
    private final double[] lastValidatorBreakTick = { Double.NaN, Double.NaN };

    public PipelineOrchestrator(double bfWidth, double bfHeight, double gunCoolingRate) {
        this.bfWidth = bfWidth;
        this.bfHeight = bfHeight;
        this.observers = ObserverContext.createPair(bfWidth, bfHeight, gunCoolingRate);
        this.godViewWaveResolver = new GodViewWaveResolver();
        this.wavePrecisionComparator = new WavePrecisionComparator();
    }

    /**
     * Attach CSV writers for output. May be null if CSV output is not needed.
     */
    public void setCsvWriters(CsvWriter writer0, CsvWriter writer1) {
        this.csvWriters = new CsvWriter[] { writer0, writer1 };
    }

    public void setBattleId(String battleId) {
        this.battleId = battleId;
    }

    /**
     * Attach a long-format IDebugProperty fidelity dump writer (in-game.csv /
     * observer.csv). May be null when the {@code debug.csv.dir} property is unset.
     */
    public void setDebugCsv(DebugPropertyCsvWriter debugCsv) {
        this.debugCsv = debugCsv;
    }

    public void setValidator(GodViewQualityValidator validator) {
        this.validator = validator;
    }

    public GodViewQualityValidator validator() {
        return validator;
    }

    public void setLayer0Validator(Layer0DebugFidelityValidator layer0Validator) {
        this.layer0Validator = layer0Validator;
    }

    public Layer0DebugFidelityValidator layer0Validator() {
        return layer0Validator;
    }

    /**
     * Point both observers at a read-only data directory so their Autopilots load
     * the same persisted VCS model the live robot loads (keyed by OPPONENT_ID_HASH,
     * once per battle, into their own VcsStores). Observers never write here.
     */
    public void setObserverDataDir(File dataDir) {
        for (ObserverContext ctx : observers) {
            ctx.setDataDir(dataDir);
        }
    }

    /**
     * Live-mode hook: fired before each round's first turn. Resets per-round state
     * and seeds the event reconstructors from the spawn snapshot so the observer
     * can reconstruct the round's opening scan on turn 1 (the engine sweeps the
     * radar from the spawn heading on turn 1; without it the observer misses that
     * first scan). Sets {@code currentRound} so {@link #processTurn} does not reset
     * again and wipe the seed.
     */
    @Override
    public void onRoundStarted(RoundStartedEvent event) {
        int round = event.getRound();
        if (round != currentRound) {
            if (currentRound >= 0) {
                writeRoundScores(currentRound);
            }
            resetRound(round);
            currentRound = round;
        }
        ITurnSnapshot start = event.getStartSnapshot();
        if (start != null) {
            for (ObserverContext ctx : observers) {
                ctx.seedRoundStart(start);
            }
        }
    }

    @Override
    public void onTurnEnded(TurnEndedEvent event) {
        processTurn(event.getTurnSnapshot());
    }

    /**
     * Process a single turn snapshot. Can be called directly (for replay mode)
     * or via {@link #onTurnEnded(TurnEndedEvent)} (for live mode).
     */
    public void processTurn(ITurnSnapshot curr) {
        int round = curr.getRound();

        // Handle round transitions
        if (round != currentRound) {
            if (currentRound >= 0) {
                writeRoundScores(currentRound);
            }
            resetRound(round);
            currentRound = round;
        }

        IRobotSnapshot[] robots = curr.getRobots();

        // Phase 1: Reconstruct events, feed to observer, run strategy (robot-side)
        for (ObserverContext ctx : observers) {
            ctx.processTick(curr);
        }

        // Phase 1.5: Seed each god-view whiteboard from the freshly-computed
        // robot-side whiteboard (tick/wave/score/string state — NOT the model).
        for (ObserverContext ctx : observers) {
            ctx.godWb().copyFrom(ctx.wb());
        }

        // Phase 2: Capture robot-side wave values (god-view writes a separate wb now)
        double[] robotSideGf = new double[2];
        for (ObserverContext ctx : observers) {
            int pi = ctx.perspectiveIndex();
            robotSideGf[pi] = wavePrecisionComparator.captureRobotSideBreak(pi, ctx.wb());
            wavePrecisionComparator.captureRobotSideFire(pi, ctx.wb());
        }

        // Phase 3: God-view wave resolution (writes the god-view whiteboards)
        boolean[] resolved = godViewWaveResolver.processTick(observers, robots, curr);

        // Phase 4: Compare robot-side vs god-view, record god-view fires
        for (ObserverContext ctx : observers) {
            int pi = ctx.perspectiveIndex();
            if (godViewWaveResolver.firedThisTick(pi)) {
                wavePrecisionComparator.recordGodViewFire(pi);
            }
            wavePrecisionComparator.compareTick(pi, ctx.godWb(), robotSideGf[pi], resolved[pi]);
        }

        // Layer 0: IDebugProperty fidelity — observer's robot-side whiteboard must
        // match the live robot's published debug properties (ALL features).
        if (layer0Validator != null) {
            for (ObserverContext ctx : observers) {
                if (ctx.isDead())
                    continue;
                int pi = ctx.perspectiveIndex();
                layer0Validator.validate(robots[pi], ctx.wb());
            }
        }

        // IDebugProperty fidelity dump (in-game.csv / observer.csv) for offline
        // diffing. Both sides come from the identical Autopilot.doTurn publish path.
        if (debugCsv != null) {
            for (ObserverContext ctx : observers) {
                if (ctx.isDead())
                    continue;
                int pi = ctx.perspectiveIndex();
                long tick = (long) ctx.wb().getFeature(Feature.TICK);
                debugCsv.writeLive(currentRound, pi, tick, robots[pi].getDebugProperties());
                debugCsv.writeObserver(currentRound, pi, tick, ctx.observerDebugProperties());
            }
        }

        // Phase 5: God-view quality validation (layers 1-4) on the god-view whiteboard
        if (validator != null) {
            for (ObserverContext ctx : observers) {
                if (ctx.isDead())
                    continue;
                int pi = ctx.perspectiveIndex();
                int oppIndex = 1 - pi;
                validator.validateSpatial(pi, ctx.godWb(), robots[pi], robots[oppIndex], curr);
                validator.accountEnergy(pi, robots, curr.getBullets());

                // Layer 2: god-view fire (our perspective fired)
                if (godViewWaveResolver.firedThisTick(pi)) {
                    double power = ctx.godWb().getFeature(Feature.OUR_FIRE_POWER);
                    double x = ctx.godWb().getFeature(Feature.OUR_FIRE_X);
                    double y = ctx.godWb().getFeature(Feature.OUR_FIRE_Y);
                    double heading = ctx.godWb().getFeature(Feature.OUR_FIRE_BEARING_ABSOLUTE);
                    long tick = (long) ctx.godWb().getFeature(Feature.OUR_FIRE_TICK);
                    validator.recordGodViewFire(pi, power, x, y, heading, tick);
                }

                // Layer 2: robot-side fire detection (observer detected own fire)
                double fireTick = ctx.wb().getFeature(Feature.OUR_FIRE_TICK);
                if (!Double.isNaN(fireTick) && fireTick != lastValidatorFireTick[pi]) {
                    lastValidatorFireTick[pi] = fireTick;
                    double rsPower = ctx.wb().getFeature(Feature.OUR_FIRE_POWER);
                    double rsX = ctx.wb().getFeature(Feature.OUR_FIRE_X);
                    double rsY = ctx.wb().getFeature(Feature.OUR_FIRE_Y);
                    validator.recordRobotSideFire(pi, rsPower, rsX, rsY, (long) fireTick);
                }

                // Layer 3: wave resolution tracking
                if (resolved[pi]) {
                    validator.recordGodViewWaveResolution(pi);
                }
                if (!Double.isNaN(robotSideGf[pi])) {
                    validator.recordRobotSideWaveResolution(pi);
                }
                // Layer 3: GF comparison when both sides resolved same tick
                if (resolved[pi] && !Double.isNaN(robotSideGf[pi])) {
                    double godViewGf = ctx.godWb().getFeature(Feature.OUR_BREAK_GF);
                    long godViewBreakTick = (long) ctx.godWb().getFeature(Feature.OUR_BREAK_TICK);
                    long robotSideBreakTick = godViewBreakTick; // same tick if both resolved
                    validator.compareWaveBreak(pi, godViewGf, robotSideGf[pi],
                            godViewBreakTick, robotSideBreakTick);
                }
            }
        }

        // Write CSV if configured (god-view whiteboard has authoritative wave features)
        for (ObserverContext ctx : observers) {
            if (!ctx.isDead() && csvWriters != null && csvWriters[ctx.perspectiveIndex()] != null) {
                try {
                    int pi = ctx.perspectiveIndex();
                    csvWriters[pi].writeTickRow(
                            ctx.godWb(), battleId != null ? battleId : "unknown", round);
                    // Write wave rows when resolved
                    if (resolved[pi]) {
                        csvWriters[pi].writeOurWaveRow(
                                ctx.godWb(), battleId != null ? battleId : "unknown", round);
                    }
                    if (resolved[1 - pi]) {
                        csvWriters[pi].writeTheirWaveRow(
                                ctx.godWb(), battleId != null ? battleId : "unknown", round);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(
                            "Failed to write CSV for perspective " + ctx.perspectiveIndex(), e);
                }
            }
        }

        prevSnapshot = curr;
    }

    /** Reset both observers for a new round (round 0, for tests/back-compat). */
    public void resetRound() {
        resetRound(0);
    }

    /**
     * Reset both observers for a new round.
     *
     * @param round zero-based round number (threaded to observers so their
     *              bullet-id sequence matches the live engine's per-round numbering)
     */
    public void resetRound(int round) {
        for (ObserverContext ctx : observers) {
            ctx.resetRound(round);
        }
        godViewWaveResolver.resetRound();
        wavePrecisionComparator.resetRound();
        if (validator != null) {
            validator.resetRound();
        }
        lastValidatorFireTick[0] = Double.NaN;
        lastValidatorFireTick[1] = Double.NaN;
        lastValidatorBreakTick[0] = Double.NaN;
        lastValidatorBreakTick[1] = Double.NaN;
    }

    public ObserverContext[] observers() {
        return observers;
    }

    public GodViewWaveResolver godViewWaveResolver() {
        return godViewWaveResolver;
    }

    public WavePrecisionComparator wavePrecisionComparator() {
        return wavePrecisionComparator;
    }

    public ITurnSnapshot prevSnapshot() {
        return prevSnapshot;
    }

    @Override
    public void close() throws IOException {
        // Write scores for the last round
        if (currentRound >= 0) {
            writeRoundScores(currentRound);
            currentRound = -1;
        }
        if (csvWriters != null) {
            for (CsvWriter w : csvWriters) {
                if (w != null)
                    w.close();
            }
        }
        if (debugCsv != null) {
            debugCsv.close();
            debugCsv = null;
        }
    }

    private void writeRoundScores(int round) {
        if (csvWriters == null)
            return;
        for (ObserverContext ctx : observers) {
            int pi = ctx.perspectiveIndex();
            if (csvWriters[pi] == null)
                continue;

            // Determine round result from prevSnapshot
            double result = 0;
            if (prevSnapshot != null) {
                var robots = prevSnapshot.getRobots();
                double ourEnergy = robots[pi].getEnergy();
                double oppEnergy = robots[1 - pi].getEnergy();
                if (ourEnergy > 0 && oppEnergy <= 0)
                    result = 1;
                else if (ourEnergy <= 0 && oppEnergy > 0)
                    result = -1;
            }
            ctx.godWb().setFeature(Feature.ROUND_RESULT, result);

            // Hit rate from god-view resolver
            double hitRate = godViewWaveResolver.getRoundHitRate(pi);
            ctx.godWb().setFeature(Feature.ROUND_HIT_RATE, Double.isNaN(hitRate) ? 0 : hitRate);

            try {
                csvWriters[pi].writeScoreRow(ctx.godWb(),
                        battleId != null ? battleId : "unknown", round);
            } catch (IOException e) {
                throw new RuntimeException("Failed to write score row for perspective " + pi, e);
            }
        }
    }
}

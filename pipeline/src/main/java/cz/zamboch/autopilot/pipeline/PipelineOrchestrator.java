package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import robocode.control.events.BattleAdaptor;
import robocode.control.events.TurnEndedEvent;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;

import java.io.Closeable;
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
    private PipelineValidator validator; // optional unified validator
    private ITurnSnapshot prevSnapshot;
    private CsvWriter[] csvWriters; // one per observer, nullable
    private String battleId;
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

    public void setValidator(PipelineValidator validator) {
        this.validator = validator;
    }

    public PipelineValidator validator() {
        return validator;
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
            resetRound();
            currentRound = round;
        }

        IRobotSnapshot[] robots = curr.getRobots();

        // Phase 1: Reconstruct events, feed to observer, run strategy (robot-side)
        for (ObserverContext ctx : observers) {
            ctx.processTick(curr);
        }

        // Phase 2: Capture robot-side wave values BEFORE god-view overwrites
        double[] robotSideGf = new double[2];
        double[] savedFirePower = new double[2];
        for (ObserverContext ctx : observers) {
            int pi = ctx.perspectiveIndex();
            robotSideGf[pi] = wavePrecisionComparator.captureRobotSideBreak(pi, ctx.wb());
            wavePrecisionComparator.captureRobotSideFire(pi, ctx.wb());
            // Save robot-side OUR_FIRE_POWER so we can restore after god-view overwrites
            savedFirePower[pi] = ctx.wb().getFeature(Feature.OUR_FIRE_POWER);
        }

        // Phase 3: God-view wave resolution (overwrites OUR_FIRE_* and OUR_BREAK_*)
        boolean[] resolved = godViewWaveResolver.processTick(observers, robots, curr);

        // Phase 4: Compare robot-side vs god-view, record god-view fires
        for (ObserverContext ctx : observers) {
            int pi = ctx.perspectiveIndex();
            if (godViewWaveResolver.firedThisTick(pi)) {
                wavePrecisionComparator.recordGodViewFire(pi);
            }
            wavePrecisionComparator.compareTick(pi, ctx.wb(), robotSideGf[pi], resolved[pi]);
        }

        // Phase 5: Unified validation (if validator attached)
        if (validator != null) {
            for (ObserverContext ctx : observers) {
                int pi = ctx.perspectiveIndex();
                int oppIndex = 1 - pi;
                validator.validateSpatial(pi, ctx.wb(), robots[pi], robots[oppIndex], curr);
                validator.accountEnergy(pi, robots, curr.getBullets());

                // Layer 2: god-view fire (our perspective fired)
                if (godViewWaveResolver.firedThisTick(pi)) {
                    double power = ctx.wb().getFeature(Feature.OUR_FIRE_POWER);
                    double x = ctx.wb().getFeature(Feature.OUR_FIRE_X);
                    double y = ctx.wb().getFeature(Feature.OUR_FIRE_Y);
                    double heading = ctx.wb().getFeature(Feature.OUR_FIRE_BEARING_ABSOLUTE);
                    long tick = (long) ctx.wb().getFeature(Feature.OUR_FIRE_TICK);
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
                    double godViewGf = ctx.wb().getFeature(Feature.OUR_BREAK_GF);
                    long godViewBreakTick = (long) ctx.wb().getFeature(Feature.OUR_BREAK_TICK);
                    long robotSideBreakTick = godViewBreakTick; // same tick if both resolved
                    validator.compareWaveBreak(pi, godViewGf, robotSideGf[pi],
                            godViewBreakTick, robotSideBreakTick);
                }

                // Layer 5: debug property cross-check
                validator.validateDebugProperties(robots[pi], ctx.wb());
            }
        }

        // Write CSV if configured (after god-view has set authoritative features)
        for (ObserverContext ctx : observers) {
            if (!ctx.isDead() && csvWriters != null && csvWriters[ctx.perspectiveIndex()] != null) {
                try {
                    int pi = ctx.perspectiveIndex();
                    csvWriters[pi].writeTickRow(
                            ctx.wb(), battleId != null ? battleId : "unknown", round);
                    // Write wave rows when resolved
                    if (resolved[pi]) {
                        csvWriters[pi].writeOurWaveRow(
                                ctx.wb(), battleId != null ? battleId : "unknown", round);
                    }
                    if (resolved[1 - pi]) {
                        csvWriters[pi].writeTheirWaveRow(
                                ctx.wb(), battleId != null ? battleId : "unknown", round);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(
                            "Failed to write CSV for perspective " + ctx.perspectiveIndex(), e);
                }
            }
        }

        // Restore robot-side OUR_FIRE_POWER so WaveTracker only sees robot's own fire
        // signal next tick
        for (ObserverContext ctx : observers) {
            int pi = ctx.perspectiveIndex();
            ctx.wb().setFeature(Feature.OUR_FIRE_POWER, savedFirePower[pi]);
        }

        prevSnapshot = curr;
    }

    /** Reset both observers for a new round. */
    public void resetRound() {
        for (ObserverContext ctx : observers) {
            ctx.resetRound();
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
            ctx.wb().setFeature(Feature.ROUND_RESULT, result);

            // Hit rate from god-view resolver
            double hitRate = godViewWaveResolver.getRoundHitRate(pi);
            ctx.wb().setFeature(Feature.ROUND_HIT_RATE, Double.isNaN(hitRate) ? 0 : hitRate);

            try {
                csvWriters[pi].writeScoreRow(ctx.wb(),
                        battleId != null ? battleId : "unknown", round);
            } catch (IOException e) {
                throw new RuntimeException("Failed to write score row for perspective " + pi, e);
            }
        }
    }
}

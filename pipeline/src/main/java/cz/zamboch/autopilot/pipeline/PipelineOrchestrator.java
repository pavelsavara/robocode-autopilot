package cz.zamboch.autopilot.pipeline;

import robocode.control.events.BattleAdaptor;
import robocode.control.events.TurnEndedEvent;
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
    private ITurnSnapshot prevSnapshot;
    private CsvWriter[] csvWriters; // one per observer, nullable
    private String battleId;
    private int currentRound = -1;

    public PipelineOrchestrator(double bfWidth, double bfHeight, double gunCoolingRate) {
        this.bfWidth = bfWidth;
        this.bfHeight = bfHeight;
        this.observers = ObserverContext.createPair(bfWidth, bfHeight, gunCoolingRate);
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
            resetRound();
            currentRound = round;
        }

        // Reconstruct events, feed to observer, run strategy
        for (ObserverContext ctx : observers) {
            ctx.processTick(curr);

            // Write CSV if configured
            if (!ctx.isDead() && csvWriters != null && csvWriters[ctx.perspectiveIndex()] != null) {
                try {
                    csvWriters[ctx.perspectiveIndex()].writeTickRow(
                            ctx.wb(), battleId != null ? battleId : "unknown", round);
                } catch (IOException e) {
                    throw new RuntimeException(
                            "Failed to write CSV for perspective " + ctx.perspectiveIndex(), e);
                }
            }
        }

        prevSnapshot = curr;
    }

    /** Reset both observers for a new round. */
    public void resetRound() {
        for (ObserverContext ctx : observers) {
            ctx.resetRound();
        }
    }

    public ObserverContext[] observers() {
        return observers;
    }

    public ITurnSnapshot prevSnapshot() {
        return prevSnapshot;
    }

    @Override
    public void close() throws IOException {
        if (csvWriters != null) {
            for (CsvWriter w : csvWriters) {
                if (w != null)
                    w.close();
            }
        }
    }
}

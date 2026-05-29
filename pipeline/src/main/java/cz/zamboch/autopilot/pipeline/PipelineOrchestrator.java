package cz.zamboch.autopilot.pipeline;

import net.sf.robocode.security.HiddenAccess;
import robocode.Event;
import robocode.RobotStatus;
import robocode.StatusEvent;
import robocode.control.events.BattleAdaptor;
import robocode.control.events.TurnEndedEvent;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Wires EventReconstructor → Observers → CSV.
 * <p>
 * Implements the observer pipeline: for each turn, reconstructs events from both
 * perspectives, feeds them to the two observer Autopilots, then writes CSV output.
 */
public final class PipelineOrchestrator extends BattleAdaptor implements Closeable {

    private final ObserverContext[] observers;
    private final double bfWidth;
    private final double bfHeight;
    private ITurnSnapshot prevSnapshot;
    private CsvWriter[] csvWriters; // one per observer, nullable
    private String battleId;
    private int currentRound;

    public PipelineOrchestrator(double bfWidth, double bfHeight, double gunCoolingRate) {
        this.bfWidth = bfWidth;
        this.bfHeight = bfHeight;
        this.observers = ObserverContext.createPair(bfWidth, bfHeight, gunCoolingRate);
    }

    /**
     * Attach CSV writers for output. May be null if CSV output is not needed.
     */
    public void setCsvWriters(CsvWriter writer0, CsvWriter writer1) {
        this.csvWriters = new CsvWriter[]{writer0, writer1};
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
        IRobotSnapshot[] robots = curr.getRobots();
        int round = curr.getRound();

        // Handle round transitions
        if (round != currentRound) {
            resetRound();
            currentRound = round;
        }

        // Reconstruct events and feed to each observer
        for (ObserverContext ctx : observers) {
            if (ctx.isDead()) continue;

            int myIndex = ctx.perspectiveIndex();
            IRobotSnapshot me = robots[myIndex];

            // Update peer state from snapshot (position, energy, gun heading)
            ctx.peer().updateState(me.getX(), me.getY(), me.getGunHeading(), me.getGunHeat(), me.getEnergy());

            // Build StatusEvent from snapshot (Autopilot.onStatus needs this to populate whiteboard)
            RobotStatus status = HiddenAccess.createStatus(
                    me.getEnergy(), me.getX(), me.getY(),
                    me.getBodyHeading(), me.getGunHeading(), me.getRadarHeading(),
                    me.getVelocity(),
                    0, 0, 0, 0, // bodyTurnRemaining, radarTurnRemaining, gunTurnRemaining, distanceRemaining
                    me.getGunHeat(),
                    1, 0, // others, sentries
                    round, 1, curr.getTurn() // roundNum, numRounds, turn
            );

            // Reconstruct combat events (scans, bullet hits, etc.)
            TickEvents combatEvents = ctx.reconstructor().reconstruct(curr, myIndex, bfWidth, bfHeight);

            // Combine: StatusEvent first, then combat events
            List<Event> allEvents = new ArrayList<>();
            allEvents.add(new StatusEvent(status));
            allEvents.addAll(combatEvents.events());

            ctx.feedEvents(new TickEvents(allEvents));
            ctx.doTurn();

            // Write CSV if configured
            if (csvWriters != null && csvWriters[myIndex] != null) {
                try {
                    csvWriters[myIndex].writeTickRow(ctx.wb(), battleId != null ? battleId : "unknown", round);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to write CSV for perspective " + myIndex, e);
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
                if (w != null) w.close();
            }
        }
    }
}

package cz.zamboch.autopilot.pipeline;

import robocode.Event;

import java.util.List;

/**
 * All reconstructed events for a single tick from the perspective of one robot.
 */
public final class TickEvents {

    private final List<Event> events;

    public TickEvents(List<Event> events) {
        this.events = List.copyOf(events);
    }

    /** Immutable list of events for this tick (may be empty). */
    public List<Event> events() {
        return events;
    }

    /** Convenience: true if no events were reconstructed. */
    public boolean isEmpty() {
        return events.isEmpty();
    }
}

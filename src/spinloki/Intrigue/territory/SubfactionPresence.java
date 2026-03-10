package spinloki.Intrigue.territory;

import java.io.Serializable;

/**
 * Tracks a single subfaction's presence within a territory.
 * Pure state — no game logic. The {@link TerritoryManager} drives transitions.
 *
 * State transitions are discrete: NONE → SCOUTING → ESTABLISHED → DOMINANT.
 * The {@code daysSinceStateChange} counter is available for the manager to
 * decide when a transition should occur (e.g. SCOUTING for 30 days → ESTABLISHED).
 */
public class SubfactionPresence implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String subfactionId;
    private PresenceState state;
    private float daysSinceStateChange;

    public SubfactionPresence(String subfactionId, PresenceState initialState) {
        this.subfactionId = subfactionId;
        this.state = initialState;
        this.daysSinceStateChange = 0f;
    }

    public String getSubfactionId() {
        return subfactionId;
    }

    public PresenceState getState() {
        return state;
    }

    /**
     * Transition to a new state. Resets the day counter.
     */
    public void setState(PresenceState newState) {
        this.state = newState;
        this.daysSinceStateChange = 0f;
    }

    public float getDaysSinceStateChange() {
        return daysSinceStateChange;
    }

    /**
     * Advance the day counter. Called by TerritoryManager each day-tick.
     */
    public void advanceDays(float days) {
        this.daysSinceStateChange += days;
    }

    @Override
    public String toString() {
        return subfactionId + ": " + state + " (" + String.format("%.1f", daysSinceStateChange) + " days)";
    }
}


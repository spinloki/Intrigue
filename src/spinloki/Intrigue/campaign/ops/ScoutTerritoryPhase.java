package spinloki.Intrigue.campaign.ops;

import java.io.Serializable;

/**
 * Phase representing scouting of a territory.
 * A simple timed phase — scouts spend time in the region.
 * Always succeeds after the timer completes (no combat).
 */
public class ScoutTerritoryPhase implements OpPhase, Serializable {

    private final float durationDays;
    private float elapsed = 0f;
    private boolean done = false;

    public ScoutTerritoryPhase(float durationDays) {
        this.durationDays = durationDays;
    }

    @Override
    public void advance(float days) {
        if (done) return;
        elapsed += days;
        if (elapsed >= durationDays) {
            done = true;
        }
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public String getStatus() {
        if (!done) {
            float remaining = Math.max(0, durationDays - elapsed);
            return String.format("Scouting territory (%.0f days remaining)", remaining);
        }
        return "Scouting complete";
    }

    public boolean didSucceed() {
        return done; // always succeeds if it completes
    }
}


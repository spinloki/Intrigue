package spinloki.Intrigue.campaign.ops;

import java.io.Serializable;

/**
 * Simple phase that completes after a set number of days.
 * Used as a placeholder for ops that don't yet have concrete game-side phases.
 */
public class TimedPhase implements OpPhase, Serializable {
    private final String label;
    private final float duration;
    private float elapsed = 0f;

    public TimedPhase(String label, float duration) {
        this.label = label;
        this.duration = duration;
    }

    @Override public void advance(float days) { elapsed += days; }
    @Override public boolean isDone() { return elapsed >= duration; }
    @Override public String getStatus() { return isDone() ? label + " complete" : label + " ongoing"; }
}


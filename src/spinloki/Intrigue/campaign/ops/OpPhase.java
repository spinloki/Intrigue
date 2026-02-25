package spinloki.Intrigue.campaign.ops;

import java.io.Serializable;

/**
 * A single phase/step within an IntrigueOp.
 *
 * Phases are advanced each frame by the owning op and signal completion
 * via {@link #isDone()}. Phases are Serializable so they survive save/load.
 */
public interface OpPhase extends Serializable {

    /**
     * Called each frame while this phase is active.
     * @param days elapsed campaign days this frame
     */
    void advance(float days);

    /**
     * @return true when this phase has completed its work
     */
    boolean isDone();

    /**
     * @return a short human-readable status string for debug/intel display
     */
    String getStatus();
}


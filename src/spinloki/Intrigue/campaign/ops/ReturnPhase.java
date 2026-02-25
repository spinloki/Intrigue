package spinloki.Intrigue.campaign.ops;

/**
 * Phase 3 of a RaidOp: a simple cooldown representing the initiator
 * returning home and re-establishing themselves after the operation.
 */
public class ReturnPhase implements OpPhase {

    private final float durationDays;
    private float elapsed = 0f;

    /**
     * @param durationDays how long the return/cooldown takes
     */
    public ReturnPhase(float durationDays) {
        this.durationDays = durationDays;
    }

    @Override
    public void advance(float days) {
        elapsed += days;
    }

    @Override
    public boolean isDone() {
        return elapsed >= durationDays;
    }

    @Override
    public String getStatus() {
        float remaining = Math.max(0, durationDays - elapsed);
        return String.format("Returning home (%.1f days remaining)", remaining);
    }
}


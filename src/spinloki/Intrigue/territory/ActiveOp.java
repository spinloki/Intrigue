package spinloki.Intrigue.territory;

import java.io.Serializable;

/**
 * An in-progress operation within a territory. Pure data — no Starsector imports.
 *
 * <p>Tracked by {@link TerritoryState}. The decision layer advances the timer and
 * resolves the outcome probabilistically. The execution layer (TerritoryManager)
 * may spawn a visual Intel for the operation and override the outcome if the player
 * intervenes.</p>
 */
public class ActiveOp implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum OpType {
        /** Cross-system patrol: fleet travels to another system and returns. */
        PATROL
    }

    public enum OpOutcome {
        /** Operation still in progress. */
        PENDING,
        /** Operation completed successfully (generates a boon). */
        SUCCESS,
        /** Operation failed (generates a problem). */
        FAILURE
    }

    private static long nextId = 1;

    private final long opId;
    private final OpType type;
    private final String subfactionId;
    private final String originSystemId;
    private final String targetSystemId;
    private final float totalDays;
    private final float successChance;

    private float daysRemaining;
    private OpOutcome outcome = OpOutcome.PENDING;

    public ActiveOp(OpType type, String subfactionId,
                    String originSystemId, String targetSystemId,
                    float totalDays, float successChance) {
        this.opId = nextId++;
        this.type = type;
        this.subfactionId = subfactionId;
        this.originSystemId = originSystemId;
        this.targetSystemId = targetSystemId;
        this.totalDays = totalDays;
        this.daysRemaining = totalDays;
        this.successChance = successChance;
    }

    /** Advance the timer by one day. Returns true if the timer just expired. */
    public boolean advanceDay() {
        if (outcome != OpOutcome.PENDING) return false;
        daysRemaining -= 1f;
        return daysRemaining <= 0f;
    }

    /** Resolve the operation probabilistically using the given random source. */
    public void resolve(java.util.Random rand) {
        if (outcome != OpOutcome.PENDING) return;
        outcome = rand.nextFloat() < successChance ? OpOutcome.SUCCESS : OpOutcome.FAILURE;
    }

    /** Override the outcome (used when the player intervenes). */
    public void overrideOutcome(OpOutcome forced) {
        this.outcome = forced;
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public long getOpId() { return opId; }
    public OpType getType() { return type; }
    public String getSubfactionId() { return subfactionId; }
    public String getOriginSystemId() { return originSystemId; }
    public String getTargetSystemId() { return targetSystemId; }
    public float getTotalDays() { return totalDays; }
    public float getDaysRemaining() { return daysRemaining; }
    public float getSuccessChance() { return successChance; }
    public OpOutcome getOutcome() { return outcome; }
    public boolean isPending() { return outcome == OpOutcome.PENDING; }

    @Override
    public String toString() {
        return type + " [" + subfactionId + "] " + originSystemId + " → " + targetSystemId +
                " (" + String.format("%.0f", daysRemaining) + "d left, " +
                (outcome == OpOutcome.PENDING ? String.format("%.0f%%", successChance * 100) : outcome) + ")";
    }
}

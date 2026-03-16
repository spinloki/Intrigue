package spinloki.Intrigue.territory;

import java.io.Serializable;

/**
 * An active entanglement between a subfaction pair in a territory.
 * Pure data — no Starsector imports, no self-managing behavior.
 *
 * <p>Tracked by {@link TerritoryState} in a map keyed by {@link SubfactionPair}.
 * At most one entanglement per pair. Creating a new entanglement on a pair that
 * already has one replaces it.</p>
 *
 * <p>{@code TerritoryState} ticks the timer, checks expiry, queries hostility,
 * and decides what ops to launch based on the entanglement type. The entanglement
 * itself does none of that — it is inert data.</p>
 */
public class ActiveEntanglement implements Serializable {

    private static final long serialVersionUID = 1L;

    private final EntanglementType type;
    private final SubfactionPair pair;
    private float daysRemaining;

    /**
     * Optional: a third subfaction involved in the entanglement's logic but not
     * part of the keyed pair. Used by {@link EntanglementType#PROXY_SUPPORT} (the target)
     * and {@link EntanglementType#RETALIATION_COALITION} (the aggressor).
     * Null when not applicable.
     */
    private final String thirdPartyId;

    /** Human-readable reason this entanglement was created (shown in intel). */
    private final String triggerReason;

    public ActiveEntanglement(EntanglementType type, SubfactionPair pair,
                              float daysRemaining, String thirdPartyId,
                              String triggerReason) {
        this.type = type;
        this.pair = pair;
        this.daysRemaining = daysRemaining;
        this.thirdPartyId = thirdPartyId;
        this.triggerReason = triggerReason;
    }

    /** Convenience: create an entanglement with no third party. */
    public ActiveEntanglement(EntanglementType type, SubfactionPair pair,
                              float daysRemaining, String triggerReason) {
        this(type, pair, daysRemaining, null, triggerReason);
    }

    /**
     * Advance the timer by one day. Returns true if the entanglement just expired
     * (timer reached zero). Condition-based entanglements (daysRemaining < 0) never
     * expire from this method — they are removed explicitly by TerritoryState.
     */
    public boolean advanceDay() {
        if (daysRemaining < 0f) return false;
        daysRemaining -= 1f;
        return daysRemaining <= 0f;
    }

    public EntanglementType getType() { return type; }
    public SubfactionPair getPair() { return pair; }
    public float getDaysRemaining() { return daysRemaining; }
    public String getThirdPartyId() { return thirdPartyId; }
    public String getTriggerReason() { return triggerReason; }

    /** Whether this entanglement has a finite timer (as opposed to condition-based). */
    public boolean isTimerBased() { return daysRemaining >= 0f; }

    /** Whether this entanglement makes its pair hostile. Delegates to type metadata. */
    public boolean setsHostile() { return type.setsHostile; }

    /** Whether this entanglement suppresses baseline hostility for its pair. */
    public boolean suppressesHostile() { return type.suppressesHostile; }

    @Override
    public String toString() {
        return type + " " + pair +
                (thirdPartyId != null ? " [third=" + thirdPartyId + "]" : "") +
                (isTimerBased() ? " (" + (int) daysRemaining + "d)" : " (condition)");
    }
}

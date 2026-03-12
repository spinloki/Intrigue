package spinloki.Intrigue.territory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Tracks a single subfaction's presence within a territory.
 * Pure state — no game logic. The {@link TerritoryManager} drives transitions.
 *
 * <p>Maintains a factor ledger of active {@link PresenceFactor}s whose net balance
 * determines whether this subfaction's presence is stable, growing, or shrinking.</p>
 */
public class SubfactionPresence implements Serializable {

    private static final long serialVersionUID = 2L;

    private final String subfactionId;
    private PresenceState state;
    private float daysSinceStateChange;

    /** Active factors contributing leverage or pressure. */
    private final List<PresenceFactor> factors;

    public SubfactionPresence(String subfactionId, PresenceState initialState) {
        this.subfactionId = subfactionId;
        this.state = initialState;
        this.daysSinceStateChange = 0f;
        this.factors = new ArrayList<>();
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

    // ── Factor ledger ────────────────────────────────────────────────────

    /** Add a factor to the ledger. */
    public void addFactor(PresenceFactor factor) {
        factors.add(factor);
    }

    /** Remove all factors of a given type (used when recalculating intrinsic/conditional factors). */
    public void removeFactorsByType(PresenceFactor.FactorType type) {
        factors.removeIf(f -> f.getType() == type);
    }

    /** Remove all factors of a given type from a specific source. */
    public void removeFactorsByTypeAndSource(PresenceFactor.FactorType type, String sourceId) {
        factors.removeIf(f -> f.getType() == type &&
                (sourceId == null ? f.getSourceId() == null :
                        sourceId.equals(f.getSourceId())));
    }

    /**
     * Tick all expiring factors by one day and prune any that have expired.
     * Returns the list of expired factors (caller may need to react to them).
     */
    public List<PresenceFactor> tickFactors() {
        List<PresenceFactor> expired = new ArrayList<>();
        Iterator<PresenceFactor> it = factors.iterator();
        while (it.hasNext()) {
            PresenceFactor f = it.next();
            if (f.tickDay()) {
                expired.add(f);
                it.remove();
            }
        }
        return expired;
    }

    /**
     * Net balance: sum of leverage weights minus sum of pressure weights.
     * Positive = trending toward promotion. Negative = trending toward demotion.
     */
    public int getNetBalance() {
        int balance = 0;
        for (PresenceFactor f : factors) {
            balance += f.signedWeight();
        }
        return balance;
    }

    /** Total leverage (sum of all leverage factor weights). */
    public int getTotalLeverage() {
        int total = 0;
        for (PresenceFactor f : factors) {
            if (f.getPolarity() == PresenceFactor.Polarity.LEVERAGE) {
                total += f.getWeight();
            }
        }
        return total;
    }

    /** Total pressure (sum of all pressure factor weights). */
    public int getTotalPressure() {
        int total = 0;
        for (PresenceFactor f : factors) {
            if (f.getPolarity() == PresenceFactor.Polarity.PRESSURE) {
                total += f.getWeight();
            }
        }
        return total;
    }

    /** Read-only view of the active factor list. */
    public List<PresenceFactor> getFactors() {
        return Collections.unmodifiableList(factors);
    }

    @Override
    public String toString() {
        int lev = getTotalLeverage();
        int pres = getTotalPressure();
        int net = getNetBalance();
        return subfactionId + ": " + state +
                " (" + String.format("%.1f", daysSinceStateChange) + " days" +
                ", L=" + lev + " P=" + pres + " net=" + net +
                ", " + factors.size() + " factors)";
    }
}


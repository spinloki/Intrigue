package spinloki.Intrigue.territory;

import java.io.Serializable;

/**
 * A single factor contributing leverage (positive) or pressure (negative)
 * to a subfaction's presence in a territory. Factors are weights on a scale —
 * the net balance of all active factors determines whether presence grows or shrinks.
 *
 * <p>Three duration categories:</p>
 * <ul>
 *   <li><b>Intrinsic</b> — Always present, derived from current state. Recalculated each tick.
 *       Uses {@code daysRemaining = -1}.</li>
 *   <li><b>Conditional</b> — Active while a game-world condition holds. Checked each tick.
 *       Uses {@code daysRemaining = -1}.</li>
 *   <li><b>Expiring</b> — Triggered by event, fades after N days. Counts down automatically.
 *       Uses {@code daysRemaining > 0}.</li>
 * </ul>
 *
 * <p>Pure Java — no Starsector imports.</p>
 */
public class PresenceFactor implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Whether this factor pushes toward promotion or demotion. */
    public enum Polarity {
        LEVERAGE,
        PRESSURE
    }

    /**
     * All recognized factor types. Each has a default polarity, but the effective
     * polarity and weight come from config ({@code intrigue_settings.json}).
     */
    public enum FactorType {
        // Intrinsic (recalculated each tick from current state)
        LOGISTICAL_STRAIN,

        // Conditional (active while a game-world condition holds)
        HOSTILE_PRESENCE,
        NEUTRAL_PRESENCE,
        SECURE_COMMS,
        STATION_CRIPPLED,

        // Expiring (triggered by events, count down over time)
        PATROL_SUCCESS,
        PATROL_FAILURE,
        COMBAT_SETBACK,
        STATION_RAIDED,
        DESERTION,
        DESERTION_QUELLED,
        DESERTION_SPIRALED,
        PLAYER_MISSION,
        EVACUATION_BONUS
    }

    private final FactorType type;
    private final Polarity polarity;
    private final int weight;
    private float daysRemaining; // -1 = permanent/conditional (managed externally)
    private final String sourceId; // optional: which subfaction/op/entity caused this

    public PresenceFactor(FactorType type, Polarity polarity, int weight,
                          float daysRemaining, String sourceId) {
        this.type = type;
        this.polarity = polarity;
        this.weight = weight;
        this.daysRemaining = daysRemaining;
        this.sourceId = sourceId;
    }

    /** Convenience: create a permanent/conditional factor (never expires on its own). */
    public PresenceFactor(FactorType type, Polarity polarity, int weight, String sourceId) {
        this(type, polarity, weight, -1f, sourceId);
    }

    /**
     * Advance the timer by one day. Returns true if this factor just expired
     * (was expiring and reached zero). Permanent/conditional factors always return false.
     */
    public boolean tickDay() {
        if (daysRemaining < 0f) return false; // permanent/conditional
        daysRemaining -= 1f;
        return daysRemaining <= 0f;
    }

    /** Whether this factor has a finite duration (expires over time). */
    public boolean isExpiring() {
        return daysRemaining >= 0f;
    }

    /** Whether this factor has expired (only meaningful for expiring factors). */
    public boolean isExpired() {
        return daysRemaining >= 0f && daysRemaining <= 0f;
    }

    /** The signed contribution to net balance: positive for leverage, negative for pressure. */
    public int signedWeight() {
        return polarity == Polarity.LEVERAGE ? weight : -weight;
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public FactorType getType() { return type; }
    public Polarity getPolarity() { return polarity; }
    public int getWeight() { return weight; }
    public float getDaysRemaining() { return daysRemaining; }
    public String getSourceId() { return sourceId; }

    @Override
    public String toString() {
        String duration = daysRemaining < 0f ? "permanent" : String.format("%.0fd left", daysRemaining);
        return type + " (" + polarity + " " + weight + ", " + duration +
                (sourceId != null ? ", src=" + sourceId : "") + ")";
    }
}

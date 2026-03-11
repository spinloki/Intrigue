package spinloki.Intrigue.territory;

/**
 * Discrete states for a subfaction's presence in a territory.
 * State transitions have explicit triggers rather than continuous numeric drift.
 */
public enum PresenceState {
    /** Not present in this territory. */
    NONE,
    /** Early exploration — scouts are probing the territory. */
    SCOUTING,
    /** Has established a base and is actively operating. */
    ESTABLISHED,
    /** Strengthened position — expanded operations, higher logistical burden. */
    FORTIFIED,
    /** Controls the territory — dominant force, draws coordinated opposition. */
    DOMINANT
}

